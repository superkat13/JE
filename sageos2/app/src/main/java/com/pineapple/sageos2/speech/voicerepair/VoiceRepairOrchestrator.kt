package com.pineapple.sageos2.speech.voicerepair

import com.pineapple.sageos2.runtime.RuntimeScheduler
import com.pineapple.sageos2.runtime.ScheduledHandle

/** Dedicated diagnostic channel. Implementations must serialize calls on the Android main thread.
 * acquire reserves an idle microphone window; capture results must NEVER enter normal command routing.
 * release cancels outstanding work for this owner only and restores its previous listening mode.
 * reset completes only after resource teardown/recreation, not after merely posting a runnable.
 */
interface VoiceDiagnosticPort {
    fun acquire(owner: String): Boolean
    fun capture(owner: String, expected: String, result: (VoiceRepairTestResult) -> Unit)
    fun reset(owner: String, completed: (Boolean) -> Unit)
    fun release(owner: String)
}

data class VoiceRepairOrchestratorConfig(val timeoutMs: Long = 60_000L)

/** All callbacks carry both session ownership and operation identity, including timeout/reset. */
class VoiceRepairOrchestrator(
    private val port: VoiceDiagnosticPort,
    private val manager: VoiceRepairSessionManager,
    private val scheduler: RuntimeScheduler,
    private val config: VoiceRepairOrchestratorConfig = VoiceRepairOrchestratorConfig(),
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val onChanged: (VoiceRepairSession) -> Unit = {}
) {
    private var owner: String? = null
    private var operation = 0L
    private var deadline: ScheduledHandle? = null

    init { require(config.timeoutMs > 0) }

    /** Returns false for an occupied runtime, without interrupting its current turn. */
    @Synchronized fun startRepair(testPhrase: String): Boolean {
        val phrase = testPhrase.trim()
        require(phrase.isNotEmpty() && phrase.length <= 200) { "Type a short test phrase (1–200 characters)." }
        if (owner != null) return false
        val session = manager.startSession(phrase, config.timeoutMs)
        val acquired = try { port.acquire(session.id) } catch (_: Exception) { false }
        if (!acquired) {
            publish(manager.update(session.copy(state = VoiceRepairState.FAILED,
                cause = VoiceRepairCause.BUSY_RUNTIME, endedAtMs = clockMs())))
            return false
        }
        owner = session.id
        deadline = scheduler.schedule(config.timeoutMs) { timeout(session.id) }
        capture(session.id, retest = false)
        return true
    }

    @Synchronized fun cancel() {
        if (owner == null) return
        manager.cancel()?.let(::publish)
        release()
    }

    @Synchronized fun interrupt() {
        if (owner == null) return
        manager.markInterrupted()?.let(::publish)
        release()
    }

    @Synchronized fun resetRequested(): Boolean = manager.current()?.attemptCount == 1
    @Synchronized fun resetCompleted(): Boolean = manager.current()?.repairAppliedAtMs != null

    private fun capture(id: String, retest: Boolean) {
        val current = live(id) ?: return
        val next = manager.update(current.copy(
            state = if (retest) VoiceRepairState.RETESTING else VoiceRepairState.TESTING_EXPECTED,
            steps = current.steps + VoiceRepairStep(if (retest) "retest_start" else "test_start", clockMs())))
        publish(next)
        if (live(id) == null) return
        val token = ++operation
        try { port.capture(id, next.testPhrase) { result -> captured(id, token, retest, result) } }
        catch (_: Exception) { fail(id, VoiceRepairCause.UNSUPPORTED_REPAIR) }
    }

    @Synchronized private fun captured(id: String, token: Long, retest: Boolean, result: VoiceRepairTestResult) {
        if (token != operation) return
        val current = live(id) ?: return
        ++operation // duplicate callbacks cannot trigger a second reset or complete a later phase
        val evidence = result.copy(expected = current.testPhrase)
        val updated = manager.update(if (retest) current.copy(secondTest = evidence) else current.copy(firstTest = evidence))
        publish(updated)
        if (result.errorCode == null && matches(current.testPhrase, result.recognized)) {
            finish(id, if (retest) VoiceRepairState.SUCCESS else VoiceRepairState.HEALTHY, VoiceRepairCause.NONE)
            return
        }
        val cause = classify(result)
        if (!retest && VoiceRepairPolicy.canApplyRepair(cause)) reset(id, cause)
        else fail(id, cause)
    }

    private fun reset(id: String, cause: VoiceRepairCause) {
        val current = live(id) ?: return
        if (current.attemptCount >= VoiceRepairPolicy.MAX_ATTEMPTS) { fail(id, cause); return }
        publish(manager.update(current.copy(state = VoiceRepairState.REPAIRING, cause = cause,
            attemptCount = current.attemptCount + 1, repairAction = VoiceRepairAction.RECREATE_RECOGNIZER,
            steps = current.steps + VoiceRepairStep("reset_requested", clockMs()))))
        if (live(id) == null) return
        val token = ++operation
        try { port.reset(id) { success -> resetFinished(id, token, success) } }
        catch (_: Exception) { fail(id, VoiceRepairCause.UNSUPPORTED_REPAIR) }
    }

    @Synchronized private fun resetFinished(id: String, token: Long, success: Boolean) {
        if (token != operation) return
        val current = live(id) ?: return
        ++operation
        if (!success) { fail(id, VoiceRepairCause.UNSUPPORTED_REPAIR); return }
        publish(manager.update(current.copy(repairAppliedAtMs = clockMs(),
            steps = current.steps + VoiceRepairStep("reset_completed", clockMs()))))
        capture(id, retest = true)
    }

    @Synchronized private fun timeout(id: String) {
        if (owner != id) return
        val current = manager.current() ?: return
        if (current.state in TERMINAL) { publish(current); release(); return }
        fail(id, VoiceRepairCause.DEADLINE_EXCEEDED)
    }

    private fun live(id: String): VoiceRepairSession? {
        if (owner != id) return null
        val current = manager.current() ?: return null
        if (current.id != id) return null
        if (current.state in TERMINAL) { publish(current); release(); return null }
        return current
    }

    private fun fail(id: String, cause: VoiceRepairCause) = finish(id, VoiceRepairState.FAILED, cause)

    private fun finish(id: String, state: VoiceRepairState, cause: VoiceRepairCause) {
        val current = live(id) ?: return
        publish(manager.update(current.copy(state = state, cause = cause, endedAtMs = clockMs())))
        release()
    }

    private fun release() {
        val id = owner ?: return
        owner = null
        ++operation
        deadline?.cancel()
        deadline = null
        // Ownership is retired before release so synchronous/late adapter callbacks cannot re-enter.
        try { port.release(id) } catch (_: Exception) { /* adapter must independently ensure cleanup */ }
    }

    private fun publish(session: VoiceRepairSession) { onChanged(session) }

    @Synchronized fun createExport(): VoiceRepairExport? {
        val s = manager.current() ?: return null
        return VoiceRepairExport.from(s)
    }

    companion object {
        private val TERMINAL = setOf(VoiceRepairState.SUCCESS, VoiceRepairState.HEALTHY, VoiceRepairState.FAILED,
            VoiceRepairState.CANCELLED, VoiceRepairState.INTERRUPTED)
        private fun matches(expected: String, actual: String?) = actual?.trim()?.equals(expected.trim(), ignoreCase = true) == true
        private fun classify(result: VoiceRepairTestResult) = when (result.errorCode) {
            null -> if (result.recognized.isNullOrBlank()) VoiceRepairCause.NO_SPEECH else VoiceRepairCause.WRONG_TRANSCRIPT
            6, 7 -> VoiceRepairCause.NO_SPEECH
            9, 12, 13 -> VoiceRepairCause.MISSING_PERMISSION_MODEL
            3, 5, 8, 11 -> VoiceRepairCause.RECOGNIZER_LIFECYCLE_FAILURE
            else -> VoiceRepairCause.UNSUPPORTED_REPAIR
        }
    }
}
