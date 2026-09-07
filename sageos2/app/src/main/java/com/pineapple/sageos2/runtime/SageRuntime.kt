package com.pineapple.sageos2.runtime

import com.pineapple.sageos2.action.FastActionEngine
import com.pineapple.sageos2.action.FastActionJob
import com.pineapple.sageos2.action.FastActionRequest
import com.pineapple.sageos2.apps.EmptyOwnerAppProvider
import com.pineapple.sageos2.apps.OwnerAppProvider
import com.pineapple.sageos2.brain.*
import com.pineapple.sageos2.capability.CapabilityBroker
import com.pineapple.sageos2.capability.CapabilityResult
import com.pineapple.sageos2.capability.DeviceAction
import com.pineapple.sageos2.capability.EmptyCapabilityBroker
import com.pineapple.sageos2.continuity.TaskCheckpoint
import com.pineapple.sageos2.continuity.TaskContinuityContextRenderer
import com.pineapple.sageos2.continuity.TaskContinuityStore
import com.pineapple.sageos2.continuity.TaskRecoveryManager
import com.pineapple.sageos2.continuity.TaskState
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
import java.util.concurrent.Executors
import java.util.concurrent.Future

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
    private val capabilities: CapabilityBroker = EmptyCapabilityBroker,
    private val taskContinuity: TaskContinuityStore? = null,
    private val twinContextRenderer: TwinContextRenderer = TwinContextRenderer(),
    private val echoGuardMs: Long = 450L,
    private val maxToolCallsPerTurn: Int = 4
) {
    init {
        require(echoGuardMs >= 0L)
        require(maxToolCallsPerTurn in 1..16)
        speech.attach(object : SpeechInputListener {
            override fun onWakeDetected(hit: WakeHit) = submit(SageEvent.WakeDetected(hit.generation, hit.profileId, hit.modeId, hit.acknowledgement))
            override fun onTranscriptFinal(turnId: Long, generation: Long, text: String) = submit(SageEvent.TranscriptFinal(turnId, generation, text))
            override fun onRecognitionError(turnId: Long, generation: Long, code: Int) = submit(SageEvent.RecognitionFailed(turnId, generation, code))
            override fun onSpeechDiagnostic(message: String) = observer.onDiagnostic("speech: $message")
        })
    }

    private var brainJob: BrainJob? = null
    private var fastActionJob: FastActionJob? = null
    private var capabilityJob: Future<*>? = null
    private var echoGuardHandle: ScheduledHandle? = null
    private var followUpExpiryHandle: ScheduledHandle? = null
    private val toolCallsByTurn = mutableMapOf<Long, Int>()
    private val capabilityExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "sage-capability").apply { isDaemon = true }
    }

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
                checkpointTurnStarted(effect.turnId, effect.prompt)
                startBrain(effect.turnId, effect.prompt)
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
                capabilityJob?.cancel(true); capabilityJob = null
                toolCallsByTurn.remove(effect.turnId)
                checkpointTurn(effect.turnId, TaskState.CANCELLED, "Turn cancelled before completion.", "")
                echoGuardHandle?.cancel(); echoGuardHandle = null
                followUpExpiryHandle?.cancel(); followUpExpiryHandle = null
            }
        }
    }

    private fun startBrain(turnId: Long, prompt: String) {
        brainJob?.cancel()
        val core = sageCore.current()
        val memory = twinMemory.snapshot()
        val history = conversationHistory.recent(24)
        val apps = ownerApps.snapshot()
        val mode = modes.current()
        val twinContext = twinContextRenderer.render(core, memory, history, apps, mode)
        val taskContext = TaskContinuityContextRenderer.render(taskContinuity?.active().orEmpty())
        val toolContext = BrainToolContextRenderer.render(capabilities.snapshot())
        val context = "$twinContext\n\n$taskContext\n\n$toolContext"
        brainJob = brain.start(BrainRequest(turnId, prompt, core, memory, history, apps, mode, context)) { result ->
            result.fold(
                onSuccess = { response -> handleBrainResponse(response) },
                onFailure = {
                    checkpointTurn(turnId, TaskState.FAILED, "Brain failed before the turn completed.", "Review diagnostics and retry from the stored owner prompt.")
                    submit(SageEvent.BrainFailed(turnId, it.message ?: it::class.simpleName.orEmpty()))
                }
            )
        }
    }

    @Synchronized
    private fun handleBrainResponse(response: BrainResponse) {
        brainJob = null
        val snapshot = coordinator.snapshot()
        if (snapshot.activeTurnId != response.turnId) {
            observer.onDiagnostic("stale Brain response ignored before tool parsing: turn=${response.turnId}")
            return
        }

        response.provenance.limitation?.let { observer.onDiagnostic("brain limitation [${response.provenance.engine}]: $it") }

        val directive = try {
            BrainToolDirectiveParser.parse(response.text)
        } catch (t: Throwable) {
            toolCallsByTurn.remove(response.turnId)
            checkpointTurn(response.turnId, TaskState.FAILED, "Brain produced an invalid structured tool response.", "Retry reasoning from the stored owner prompt.")
            submit(SageEvent.BrainFailed(response.turnId, "invalid structured tool response: ${t.message ?: t::class.simpleName}"))
            return
        }

        if (directive == null) {
            toolCallsByTurn.remove(response.turnId)
            checkpointTurn(response.turnId, TaskState.COMPLETED, "Owner turn completed successfully.", "")
            submit(SageEvent.ResponseReady(response.turnId, response.text, true))
            return
        }

        val nextCount = (toolCallsByTurn[response.turnId] ?: 0) + 1
        if (nextCount > maxToolCallsPerTurn) {
            toolCallsByTurn.remove(response.turnId)
            checkpointTurn(response.turnId, TaskState.FAILED, "Structured tool call ceiling reached.", "Continue from the stored owner prompt with a shorter tool plan.")
            submit(SageEvent.BrainFailed(response.turnId, "structured tool call limit exceeded"))
            return
        }
        toolCallsByTurn[response.turnId] = nextCount

        val action = DeviceAction(directive.name, directive.arguments)
        checkpointTurn(
            response.turnId,
            TaskState.ACTIVE,
            "Executing structured capability ${action.name} (step $nextCount of $maxToolCallsPerTurn).",
            "Wait for the capability result, then continue reasoning without replaying this action.",
            mapOf("phase" to "capability", "lastAction" to action.name, "toolCount" to nextCount.toString())
        )
        capabilityJob?.cancel(true)
        capabilityJob = capabilityExecutor.submit {
            val result = runCatching { capabilities.execute(action) }
                .getOrElse { CapabilityResult(false, "Capability execution failed: ${it.message ?: it::class.java.simpleName}") }
            continueAfterCapability(response.turnId, action, result)
        }
    }

    @Synchronized
    private fun continueAfterCapability(turnId: Long, action: DeviceAction, result: CapabilityResult) {
        capabilityJob = null
        if (coordinator.snapshot().activeTurnId != turnId) {
            observer.onDiagnostic("stale capability result ignored: turn=$turnId action=${action.name}")
            return
        }
        observer.onDiagnostic("capability result: turn=$turnId action=${action.name} success=${result.success}")
        checkpointTurn(
            turnId,
            TaskState.ACTIVE,
            "Capability ${action.name} returned success=${result.success}.",
            "Continue reasoning from the capability result; do not replay the completed capability call.",
            mapOf("phase" to "brain_after_capability", "lastAction" to action.name, "lastActionSuccess" to result.success.toString())
        )
        startBrain(turnId, BrainToolContextRenderer.renderResult(action, result))
    }

    private fun checkpointTurnStarted(turnId: Long, ownerPrompt: String) {
        val store = taskContinuity ?: return
        val cleanPrompt = ownerPrompt.replace(Regex("\\s+"), " ").trim().take(4_000)
        store.upsert(
            TaskCheckpoint(
                taskId = runtimeTaskId(turnId),
                title = cleanPrompt.take(80).ifBlank { "Sage owner turn $turnId" },
                state = TaskState.ACTIVE,
                summary = "Deep Brain reasoning started for this owner turn.",
                nextStep = "Generate a final response or one exact structured capability call.",
                updatedAtMs = System.currentTimeMillis(),
                metadata = mapOf(
                    "kind" to TaskRecoveryManager.RUNTIME_TURN_KIND,
                    "turnId" to turnId.toString(),
                    "ownerPrompt" to cleanPrompt,
                    "phase" to "brain"
                )
            )
        )
    }

    private fun checkpointTurn(
        turnId: Long,
        state: TaskState,
        summary: String,
        nextStep: String,
        metadata: Map<String, String> = emptyMap()
    ) {
        val store = taskContinuity ?: return
        val id = runtimeTaskId(turnId)
        val existing = store.get(id) ?: return
        store.upsert(
            existing.copy(
                state = state,
                summary = summary,
                nextStep = nextStep,
                updatedAtMs = System.currentTimeMillis(),
                metadata = existing.metadata + metadata
            )
        )
    }

    private fun runtimeTaskId(turnId: Long) = "runtime:turn:$turnId"

    private fun recordSageResponse(turnId: Long, text: String, input: ConversationInput) {
        (conversationHistory as? ConversationHistoryStore)?.record(
            ConversationEntry(UUID.randomUUID().toString(), turnId, ConversationSpeaker.SAGE, input, text, System.currentTimeMillis())
        )
    }
}
