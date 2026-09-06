package com.pineapple.sageos2.runtime

import com.pineapple.sageos2.action.FastActionEngine
import com.pineapple.sageos2.action.FastActionJob
import com.pineapple.sageos2.action.FastActionRequest
import com.pineapple.sageos2.apps.EmptyOwnerAppProvider
import com.pineapple.sageos2.apps.OwnerAppProvider
import com.pineapple.sageos2.brain.BrainEngine
import com.pineapple.sageos2.brain.BrainJob
import com.pineapple.sageos2.brain.BrainRequest
import com.pineapple.sageos2.core.*
import com.pineapple.sageos2.identity.EmptySageCoreProvider
import com.pineapple.sageos2.identity.SageCoreProvider
import com.pineapple.sageos2.identity.TwinContextRenderer
import com.pineapple.sageos2.memory.*
import com.pineapple.sageos2.mode.DefaultSageModeController
import com.pineapple.sageos2.mode.SageModeController
import com.pineapple.sageos2.speech.SpeechInputListener
import com.pineapple.sageos2.speech.SpeechPort
import com.pineapple.sageos2.speech.WakeHit
import com.pineapple.sageos2.workflow.WorkflowEngine
import java.util.UUID

class SageRuntime(
    private val coordinator: SageTurnCoordinator,
    private val speech: SpeechPort,
    private val brain: BrainEngine,
    private val fastActions: FastActionEngine,
    private val workflows: WorkflowEngine,
    private val scheduler: RuntimeScheduler,
    private val observer: RuntimeObserver = NoOpRuntimeObserver,
    private val sageCore: SageCoreProvider = EmptySageCoreProvider,
    private val twinMemory: TwinMemoryProvider = EmptyTwinMemoryProvider,
    private val conversationHistory: ConversationHistoryProvider = EmptyConversationHistoryProvider,
    private val ownerApps: OwnerAppProvider = EmptyOwnerAppProvider,
    private val modes: SageModeController = DefaultSageModeController,
    private val twinContextRenderer: TwinContextRenderer = TwinContextRenderer(),
    private val echoGuardMs: Long = 450L
) {
    init {
        require(echoGuardMs >= 0L)
        speech.attach(object : SpeechInputListener {
            override fun onWakeDetected(hit: WakeHit) = submit(SageEvent.WakeDetected(hit.generation, hit.profileId, hit.modeId, hit.acknowledgement))
            override fun onTranscriptFinal(turnId: Long, generation: Long, text: String) = submit(SageEvent.TranscriptFinal(turnId, generation, text))
            override fun onRecognitionError(turnId: Long, generation: Long, code: Int) = submit(SageEvent.RecognitionFailed(turnId, generation, code))
            override fun onSpeechDiagnostic(message: String) = observer.onDiagnostic("speech: $message")
        })
    }

    private var brainJob: BrainJob? = null
    private var fastActionJob: FastActionJob? = null
    private var echoGuardHandle: ScheduledHandle? = null
    private var followUpExpiryHandle: ScheduledHandle? = null

    @Synchronized fun start() { submit(SageEvent.Start) }
    @Synchronized fun stop() { submit(SageEvent.Stop); speech.shutdown() }
    @Synchronized fun submit(event: SageEvent) {
        recordUserEvent(event)
        process(coordinator.handle(event))
        observer.onStateChanged(coordinator.snapshot())
    }
    fun snapshot() = coordinator.snapshot()

    private fun recordUserEvent(event: SageEvent) {
        val store = conversationHistory as? ConversationHistoryStore ?: return
        when (event) {
            is SageEvent.TextSubmitted -> if (event.text.isNotBlank()) store.record(ConversationEntry(UUID.randomUUID().toString(), coordinator.snapshot().activeTurnId, ConversationSpeaker.OWNER, ConversationInput.TEXT, event.text.trim(), System.currentTimeMillis()))
            is SageEvent.TranscriptFinal -> if (event.text.isNotBlank()) store.record(ConversationEntry(UUID.randomUUID().toString(), event.turnId, ConversationSpeaker.OWNER, ConversationInput.VOICE, event.text.trim(), System.currentTimeMillis()))
            else -> Unit
        }
    }

    private fun process(effects: List<SageEffect>) = effects.forEach { effect ->
        try { process(effect) } catch (t: Throwable) { observer.onUnhandledFailure("effect failed: ${effect::class.simpleName}", t) }
    }

    private fun process(effect: SageEffect) {
        when (effect) {
            is SageEffect.SetListeningMode -> speech.setListening(effect.mode, effect.generation, effect.turnId)
            is SageEffect.ActivateMode -> modes.activate(effect.profileId, effect.modeId)
            is SageEffect.EmitTextResponse -> {
                recordSageResponse(effect.turnId, effect.text, ConversationInput.TEXT)
                observer.onTextResponse(effect.turnId, effect.text)
            }
            is SageEffect.Speak -> {
                if (coordinator.snapshot().state == SageRuntimeState.SPEAKING) recordSageResponse(effect.turnId, effect.text, ConversationInput.VOICE)
                speech.speak(effect.turnId, effect.text) {
                    val snapshot = coordinator.snapshot()
                    if (snapshot.activeTurnId == effect.turnId && snapshot.state == SageRuntimeState.ACKNOWLEDGING_WAKE) submit(SageEvent.WakeAcknowledgementSpoken(effect.turnId))
                    else submit(SageEvent.SpeechFinished(effect.turnId))
                }
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
                val core = sageCore.current()
                val memory = twinMemory.snapshot()
                val history = conversationHistory.recent(24)
                val apps = ownerApps.snapshot()
                val mode = modes.current()
                val context = twinContextRenderer.render(core, memory, history, apps, mode)
                brainJob = brain.start(BrainRequest(effect.turnId, effect.prompt, core, memory, history, apps, mode, context)) { result ->
                    result.fold(
                        onSuccess = { response ->
                            response.provenance.limitation?.let { observer.onDiagnostic("brain limitation [${response.provenance.engine}]: $it") }
                            submit(SageEvent.ResponseReady(response.turnId, response.text, true))
                        },
                        onFailure = { submit(SageEvent.BrainFailed(effect.turnId, it.message ?: it::class.simpleName.orEmpty())) }
                    )
                }
            }
            is SageEffect.LaunchOwnerWorkflow -> workflows.launch(effect.turnId, effect.workflowId)
            is SageEffect.StartEchoGuard -> {
                echoGuardHandle?.cancel()
                echoGuardHandle = scheduler.schedule(echoGuardMs) { submit(SageEvent.EchoGuardElapsed(effect.turnId)) }
            }
            is SageEffect.ScheduleFollowUpExpiry -> {
                followUpExpiryHandle?.cancel()
                followUpExpiryHandle = scheduler.schedule(effect.delayMs) { submit(SageEvent.FollowUpExpired(effect.turnId)) }
            }
            is SageEffect.TypedInputQueued -> observer.onTypedInputQueued(effect.depth)
            is SageEffect.TypedInputRejected -> observer.onTypedInputRejected(effect.reason)
            is SageEffect.RecordDiagnostic -> observer.onDiagnostic(effect.message)
            is SageEffect.IgnoreStaleCallback -> observer.onDiagnostic("stale callback: ${effect.reason}")
            is SageEffect.CancelTurn -> {
                if (brainJob?.turnId == effect.turnId) { brainJob?.cancel(); brainJob = null }
                if (fastActionJob?.turnId == effect.turnId) { fastActionJob?.cancel(); fastActionJob = null }
                echoGuardHandle?.cancel(); echoGuardHandle = null
                followUpExpiryHandle?.cancel(); followUpExpiryHandle = null
            }
        }
    }

    private fun recordSageResponse(turnId: Long, text: String, input: ConversationInput) {
        (conversationHistory as? ConversationHistoryStore)?.record(
            ConversationEntry(UUID.randomUUID().toString(), turnId, ConversationSpeaker.SAGE, input, text, System.currentTimeMillis())
        )
    }
}
