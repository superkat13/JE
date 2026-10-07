package com.pineapple.sageos2.speech.voicerepair

import com.pineapple.sageos2.core.SageListeningMode
import com.pineapple.sageos2.speech.CommandRecognizerBackend
import com.pineapple.sageos2.speech.SpeechInputListener
import com.pineapple.sageos2.speech.SpeechPort
import com.pineapple.sageos2.speech.WakeHit
import java.util.concurrent.atomic.AtomicBoolean

data class VoiceRepairOrchestratorConfig(
    val timeoutMs: Long = VoiceRepairPolicy.DEFAULT_TIMEOUT_MS,
    val maxAttempts: Int = VoiceRepairPolicy.MAX_ATTEMPTS
)

interface VoiceRepairController {
    fun startRepair(testPhrase: String)
    fun cancel()
    fun submitExpectedPhrase(phrase: String)
}

class VoiceRepairOrchestrator(
    private val speechPort: SpeechPort,
    private val manager: VoiceRepairSessionManager,
    private val config: VoiceRepairOrchestratorConfig = VoiceRepairOrchestratorConfig(),
    private val clockMs: () -> Long = System::currentTimeMillis
) : VoiceRepairController, SpeechInputListener {
    private val started = AtomicBoolean(false)
    private var expectedPhrase: String = ""

    override fun startRepair(testPhrase: String) {
        if (started.get()) return
        expectedPhrase = testPhrase.trim()
        started.set(true)
        val session = manager.startSession(expectedPhrase, config.timeoutMs)
        manager.update(session.copy(state = VoiceRepairState.TESTING_EXPECTED, steps = session.steps + VoiceRepairStep("test_start", clockMs(), "begin first test")))
        // In full integration, speech would be captured via diagnostic path; this is scaffolding
    }

    override fun cancel() {
        started.set(false)
        manager.cancel()
    }

    override fun submitExpectedPhrase(phrase: String) {
        expectedPhrase = phrase.trim()
    }

    override fun onWakeDetected(hit: WakeHit) = Unit
    override fun onTranscriptFinal(turnId: Long, generation: Long, text: String) = Unit
    override fun onRecognitionError(turnId: Long, generation: Long, code: Int) = Unit
}
