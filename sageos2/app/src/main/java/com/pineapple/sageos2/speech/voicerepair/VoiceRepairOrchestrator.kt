package com.pineapple.sageos2.speech.voicerepair

import com.pineapple.sageos2.core.SageListeningMode
import com.pineapple.sageos2.speech.CommandRecognizerBackend
import com.pineapple.sageos2.speech.SpeechInputListener
import com.pineapple.sageos2.speech.SpeechPort
import com.pineapple.sageos2.speech.WakeHit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class VoiceRepairOrchestratorConfig(
    val timeoutMs: Long = VoiceRepairPolicy.DEFAULT_TIMEOUT_MS,
    val maxAttempts: Int = VoiceRepairPolicy.MAX_ATTEMPTS
)

interface VoiceRepairController {
    fun startRepair(testPhrase: String)
    fun cancel()
    fun submitExpectedPhrase(phrase: String)
    fun resetRequested(): Boolean
    fun resetCompleted(): Boolean
    fun createExport(): VoiceRepairExport?
}

class VoiceRepairOrchestrator(
    private val speechPort: SpeechPort,
    private val manager: VoiceRepairSessionManager,
    private val config: VoiceRepairOrchestratorConfig = VoiceRepairOrchestratorConfig(),
    private val clockMs: () -> Long = System::currentTimeMillis
) : VoiceRepairController, SpeechInputListener {
    private val started = AtomicBoolean(false)
    private val resetRequestedFlag = AtomicBoolean(false)
    private val resetCompletedFlag = AtomicBoolean(false)
    private val generation = AtomicLong(0L)
    private var expectedPhrase: String = ""
    private var firstRecognized: String? = null
    private var secondRecognized: String? = null
    private var repairApplied = false

    override fun startRepair(testPhrase: String) {
        if (started.get()) return
        expectedPhrase = testPhrase.trim()
        started.set(true)
        val session = manager.startSession(expectedPhrase, config.timeoutMs)
        manager.update(
            session.copy(
                state = VoiceRepairState.TESTING_EXPECTED,
                steps = session.steps + VoiceRepairStep("test_start", clockMs(), "begin first test")
            )
        )
    }

    override fun cancel() {
        started.set(false)
        resetRequestedFlag.set(false)
        resetCompletedFlag.set(false)
        manager.cancel()
    }

    override fun submitExpectedPhrase(phrase: String) {
        expectedPhrase = phrase.trim()
    }

    override fun resetRequested(): Boolean = resetRequestedFlag.get()
    override fun resetCompleted(): Boolean = resetCompletedFlag.get()

    override fun createExport(): VoiceRepairExport? {
        val session = manager.current() ?: return null
        return VoiceRepairExport(
            sessionId = session.id,
            startedAtMs = session.startedAtMs,
            endedAtMs = session.endedAtMs,
            state = session.state,
            cause = session.cause,
            testPhrase = session.testPhrase,
            expected = session.testPhrase,
            recognizedBefore = firstRecognized,
            recognizedAfter = secondRecognized,
            repairAction = session.repairAction,
            repairApplied = repairApplied,
            verified = session.state == VoiceRepairState.SUCCESS,
            steps = session.steps,
            notes = "explicit test export"
        )
    }

    override fun onWakeDetected(hit: WakeHit) = Unit
    override fun onTranscriptFinal(turnId: Long, generation: Long, text: String) {
        val sess = manager.current() ?: return
        if (sess.state == VoiceRepairState.TESTING_EXPECTED && firstRecognized == null) {
            firstRecognized = text
            manager.update(sess.copy(steps = sess.steps + VoiceRepairStep("first_result", clockMs(), "recognized=$text")))
        } else if (sess.state == VoiceRepairState.RETESTING && secondRecognized == null) {
            secondRecognized = text
            val verified = text.trim().lowercase() == sess.testPhrase.trim().lowercase()
            val endState = if (verified) VoiceRepairState.SUCCESS else VoiceRepairState.FAILED
            val cause = if (verified) VoiceRepairCause.NONE else VoiceRepairCause.WRONG_TRANSCRIPT
            manager.update(
                sess.copy(
                    state = endState,
                    cause = cause,
                    secondTest = sess.secondTest?.copy(recognized = text) ?: VoiceRepairTestResult(sess.testPhrase, text),
                    endedAtMs = clockMs(),
                    steps = sess.steps + VoiceRepairStep("retest_result", clockMs(), "verified=$verified")
                )
            )
        }
    }

    override fun onRecognitionError(turnId: Long, generation: Long, code: Int) {
        val sess = manager.current() ?: return
        if (sess.state == VoiceRepairState.TESTING_EXPECTED) {
            val cause = if (code == 7) VoiceRepairCause.NO_SPEECH else VoiceRepairCause.RECOGNIZER_LIFECYCLE_FAILURE
            manager.update(sess.copy(state = VoiceRepairState.REPAIRING, cause = cause, steps = sess.steps + VoiceRepairStep("first_error", clockMs(), "code=$code")))
        }
    }
}
