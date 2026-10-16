package com.pineapple.sageos2.speech.voicerepair

import com.pineapple.sageos2.runtime.RuntimeScheduler
import com.pineapple.sageos2.runtime.ScheduledHandle

/** Main-thread host workflow. Expected text never enters the runtime's command router. */
class VoiceRepairWorkflow(
    private val controller: VoiceRepairOrchestrator,
    private val scheduler: RuntimeScheduler,
    private val reserve: () -> Boolean,
    private val release: () -> Unit,
    private val changed: () -> Unit
) {
    var awaitingPhrase = false
        private set
    var waitingForIdle = false
        private set
    var running = false
        private set
    var message = ""
        private set
    private var poll: ScheduledHandle? = null
    private var generation = 0L
    private var reserved = false
    /** Reconcile wake even when the owner cancels before acquiring a physical lease. */
    private var needsRuntimeRestore = false

    fun request(): String {
        if (awaitingPhrase || waitingForIdle || running) return "A hearing test is already open. Finish or cancel it first."
        // A previous session's deferred terminal callback must never release this
        // session's mic lease or restoration flag.
        ++generation
        poll?.cancel()
        poll = null
        awaitingPhrase = true
        needsRuntimeRestore = true
        message = "Type a short expected phrase in the hearing test box. Then tap Start test and wait for Speak now. Test words never run as commands."
        changed()
        return message
    }

    fun submitPhrase(raw: String): Boolean {
        val phrase = raw.trim()
        if (!awaitingPhrase) return false
        if (phrase.isEmpty() || phrase.length > 200) {
            message = "Type a short phrase of 1–200 characters."
            changed()
            return false
        }
        awaitingPhrase = false
        waitingForIdle = true
        message = "Waiting for my current reply to finish. You can cancel."
        changed()
        val token = ++generation
        // Always defer: local routing must finish its reply before controller admission.
        poll = scheduler.schedule(100) { admit(token, phrase, 0) }
        return true
    }

    private fun admit(token: Long, phrase: String, checks: Int) {
        if (token != generation || !waitingForIdle) return
        if (!runCatching { reserve() }.getOrDefault(false)) {
            if (checks >= 300) {
                waitingForIdle = false
                message = "I could not get an idle microphone window. Nothing was changed. Try again after the current task finishes."
                // Even without a physical lease the pending diagnostic gate may have
                // blocked wake callbacks. Retire that generation and restore listening.
                releaseReservation()
                changed()
            } else poll = scheduler.schedule(100) { admit(token, phrase, checks + 1) }
            return
        }
        reserved = true
        waitingForIdle = false
        running = true
        try {
            if (!controller.startRepair(phrase)) {
                running = false
                message = "The microphone is busy. No repair was verified."
                releaseReservation()
            }
        } catch (_: Exception) {
            runCatching { controller.interrupt() }
            running = false
            message = "The hearing test could not start. No repair was verified."
            releaseReservation()
        }
        changed()
    }

    fun sessionChanged(session: VoiceRepairSession) {
        message = VoiceRepairPresentation.render(session)
        if (session.state in TERMINAL) {
            // onChanged fires before controller.release: defer runtime restoration until it returns.
            val token = generation
            scheduler.schedule(0) { if (token == generation) { running = false; releaseReservation(); changed() } }
        }
        changed()
    }

    fun cancel(): String {
        val active = awaitingPhrase || waitingForIdle || running
        ++generation
        poll?.cancel()
        poll = null
        awaitingPhrase = false
        waitingForIdle = false
        val saved = runCatching { controller.cancel() }.isSuccess
        running = false
        releaseReservation()
        message = if (!saved) "Cancelled hearing test. The microphone was released, but the session could not be saved."
            else if (active) "Cancelled voice repair. No repair has been verified." else "No active voice repair session to cancel."
        changed()
        return message
    }

    fun interrupt() {
        ++generation
        poll?.cancel()
        poll = null
        val pending = awaitingPhrase || waitingForIdle
        awaitingPhrase = false
        waitingForIdle = false
        val saved = runCatching { controller.interrupt() }.isSuccess
        running = false
        releaseReservation()
        if (!saved) message = "Hearing test interrupted. The microphone was released, but the session could not be saved."
        else if (pending) message = "Hearing test interrupted before capture. No repair was verified."
        changed()
    }

    private fun releaseReservation() {
        if (!needsRuntimeRestore) return
        needsRuntimeRestore = false
        reserved = false
        release()
    }

    companion object {
        val TERMINAL = setOf(VoiceRepairState.SUCCESS, VoiceRepairState.HEALTHY, VoiceRepairState.FAILED,
            VoiceRepairState.CANCELLED, VoiceRepairState.INTERRUPTED)
    }
}

object VoiceRepairPresentation {
    fun render(session: VoiceRepairSession): String = buildString {
        appendLine(when (session.state) {
            VoiceRepairState.TESTING_EXPECTED -> if (captureReady(session)) "Speak now: say the expected phrase."
                else "Preparing microphone. Do not speak until it is ready."
            VoiceRepairState.RETESTING -> if (captureReady(session)) "Speak now: say the same phrase again for the retest."
                else "Preparing microphone for retest. Do not speak until it is ready."
            VoiceRepairState.REPAIRING -> "The recognizer failed to start reliably. Recreating it once."
            VoiceRepairState.SUCCESS -> "The retest matched after recreating the recognizer. Repair verified by this phrase test."
            VoiceRepairState.HEALTHY -> "The first phrase matched. No repair was needed or performed."
            VoiceRepairState.CANCELLED -> "Hearing test cancelled. No repair was verified."
            VoiceRepairState.INTERRUPTED -> "Hearing test interrupted. No repair was verified; it will not restart automatically."
            VoiceRepairState.FAILED -> "Hearing test stopped. No repair was verified."
            else -> "Preparing the hearing test."
        })
        appendLine("Expected: “${session.testPhrase}”")
        session.firstTest?.let { appendLine(evidence("First test", it)) }
        appendLine(if (session.repairAppliedAtMs != null) "Changed: recreated the command recognizer." else "Changed: no completed repair.")
        session.secondTest?.let { appendLine(evidence("Retest", it)) }
        if (session.cause != VoiceRepairCause.NONE) appendLine(when (session.cause) {
            VoiceRepairCause.NO_SPEECH -> "No speech was recognized."
            VoiceRepairCause.WRONG_TRANSCRIPT -> "The recognized words did not match. A reset is not a supported repair for this mismatch."
            VoiceRepairCause.MISSING_PERMISSION_MODEL -> "Microphone permission or the speech model is unavailable. Check microphone access and speech readiness."
            VoiceRepairCause.RECOGNIZER_LIFECYCLE_FAILURE -> "The recognizer reported a lifecycle or start failure."
            VoiceRepairCause.BUSY_RUNTIME -> "The microphone is busy with another task."
            VoiceRepairCause.DEADLINE_EXCEEDED -> "The test deadline expired."
            VoiceRepairCause.READY_TIMEOUT -> "The command recognizer did not become ready to listen before the readiness timeout. No repair was verified."
            VoiceRepairCause.CANCELLED_BY_OWNER -> "You cancelled the test."
            VoiceRepairCause.SLOW_DOWNSTREAM_INFERENCE -> "The delay is in reasoning after recognition."
            else -> "No supported repair completed."
        })
        if (session.state == VoiceRepairState.FAILED || session.state == VoiceRepairState.INTERRUPTED)
            append("Use Save test export to save this explicit test locally, including the test words.")
    }.trim()

    /** Only display Speak now after readiness for this exact capture, never a previous test. */
    private fun captureReady(session: VoiceRepairSession): Boolean {
        val start = session.steps.indexOfLast { it.name == "test_start" || it.name == "retest_start" }
        return start >= 0 && session.steps.drop(start + 1).any { it.name == "ready" }
    }

    private fun evidence(label: String, result: VoiceRepairTestResult): String =
        "$label heard: ${result.recognized?.let { "“$it”" } ?: "no words"} (${result.backend}, ${result.elapsedMs} ms${result.errorCode?.let { ", recognizer error $it" } ?: ""})."
}
