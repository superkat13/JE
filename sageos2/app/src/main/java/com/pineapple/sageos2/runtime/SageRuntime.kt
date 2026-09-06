package com.pineapple.sageos2.runtime

import com.pineapple.sageos2.action.FastActionEngine
import com.pineapple.sageos2.action.FastActionJob
import com.pineapple.sageos2.action.FastActionRequest
import com.pineapple.sageos2.brain.BrainEngine
import com.pineapple.sageos2.brain.BrainJob
import com.pineapple.sageos2.brain.BrainRequest
import com.pineapple.sageos2.core.SageEffect
import com.pineapple.sageos2.core.SageEvent
import com.pineapple.sageos2.core.SageRuntimeState
import com.pineapple.sageos2.core.SageTurnCoordinator
import com.pineapple.sageos2.identity.EmptySageCoreProvider
import com.pineapple.sageos2.identity.SageCoreProvider
import com.pineapple.sageos2.speech.SpeechInputListener
import com.pineapple.sageos2.speech.SpeechPort
import com.pineapple.sageos2.workflow.WorkflowEngine

class SageRuntime(
    private val coordinator: SageTurnCoordinator,
    private val speech: SpeechPort,
    private val brain: BrainEngine,
    private val fastActions: FastActionEngine,
    private val workflows: WorkflowEngine,
    private val scheduler: RuntimeScheduler,
    private val observer: RuntimeObserver = NoOpRuntimeObserver,
    private val sageCore: SageCoreProvider = EmptySageCoreProvider,
    private val echoGuardMs: Long = 450L
) {
    init {
        require(echoGuardMs >= 0L) { "echoGuardMs must be non-negative" }
        speech.attach(object : SpeechInputListener {
            override fun onWakeDetected(generation: Long) = submit(SageEvent.WakeDetected(generation))
            override fun onTranscriptFinal(turnId: Long, generation: Long, text: String) = submit(SageEvent.TranscriptFinal(turnId, generation, text))
            override fun onRecognitionError(turnId: Long, generation: Long, code: Int) = submit(SageEvent.RecognitionFailed(turnId, generation, code))
            override fun onSpeechDiagnostic(message: String) = observer.onDiagnostic("speech: $message")
        })
    }

    private var brainJob: BrainJob? = null
    private var fastActionJob: FastActionJob? = null
    private var echoGuardHandle: ScheduledHandle? = null

    @Synchronized fun start() { submit(SageEvent.Start) }
    @Synchronized fun stop() { submit(SageEvent.Stop); speech.shutdown() }
    @Synchronized fun submit(event: SageEvent) { process(coordinator.handle(event)) }
    fun snapshot() = coordinator.snapshot()

    private fun process(effects: List<SageEffect>) = effects.forEach { effect ->
        try { process(effect) } catch (t: Throwable) {
            observer.onUnhandledFailure("effect failed: ${effect::class.simpleName}", t)
        }
    }

    private fun process(effect: SageEffect) {
        when (effect) {
            is SageEffect.SetListeningMode -> speech.setListening(effect.mode, effect.generation, effect.turnId)
            is SageEffect.Speak -> speech.speak(effect.turnId, effect.text) {
                val snapshot = coordinator.snapshot()
                if (snapshot.activeTurnId == effect.turnId && snapshot.state == SageRuntimeState.ACKNOWLEDGING_WAKE) submit(SageEvent.WakeAcknowledgementSpoken(effect.turnId))
                else submit(SageEvent.SpeechFinished(effect.turnId))
            }
            is SageEffect.SpeakTransient -> speech.speakTransient(effect.text)
            is SageEffect.ExecuteFast -> {
                fastActionJob?.cancel()
                fastActionJob = fastActions.start(FastActionRequest(effect.turnId, effect.command)) { result ->
                    result.fold(
                        onSuccess = { submit(SageEvent.ResponseReady(it.turnId, it.text, it.allowFollowUp)) },
                        onFailure = { submit(SageEvent.BrainFailed(effect.turnId, "fast action: ${it.message ?: it::class.simpleName}")) }
                    )
                }
            }
            is SageEffect.QueryDeepBrain -> {
                brainJob?.cancel()
                brainJob = brain.start(BrainRequest(effect.turnId, effect.prompt, sageCore.current())) { result ->
                    result.fold(
                        onSuccess = { submit(SageEvent.ResponseReady(it.turnId, it.text, true)) },
                        onFailure = { submit(SageEvent.BrainFailed(effect.turnId, it.message ?: it::class.simpleName.orEmpty())) }
                    )
                }
            }
            is SageEffect.LaunchOwnerWorkflow -> workflows.launch(effect.turnId, effect.workflowId)
            is SageEffect.StartEchoGuard -> {
                echoGuardHandle?.cancel()
                echoGuardHandle = scheduler.schedule(echoGuardMs) { submit(SageEvent.EchoGuardElapsed(effect.turnId)) }
            }
            is SageEffect.TypedInputQueued -> observer.onTypedInputQueued(effect.depth)
            is SageEffect.TypedInputRejected -> observer.onTypedInputRejected(effect.reason)
            is SageEffect.RecordDiagnostic -> observer.onDiagnostic(effect.message)
            is SageEffect.IgnoreStaleCallback -> observer.onDiagnostic("stale callback: ${effect.reason}")
            is SageEffect.CancelTurn -> {
                if (brainJob?.turnId == effect.turnId) { brainJob?.cancel(); brainJob = null }
                if (fastActionJob?.turnId == effect.turnId) { fastActionJob?.cancel(); fastActionJob = null }
                echoGuardHandle?.cancel(); echoGuardHandle = null
            }
        }
    }
}
