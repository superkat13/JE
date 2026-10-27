package com.pineapple.sageos2.runtime

import com.pineapple.sageos2.action.FastActionEngine
import com.pineapple.sageos2.action.FastActionJob
import com.pineapple.sageos2.action.FastActionRequest
import com.pineapple.sageos2.action.FastCommand
import com.pineapple.sageos2.action.FastCommandParser
import com.pineapple.sageos2.apps.EmptyOwnerAppProvider
import com.pineapple.sageos2.apps.OwnerAppProvider
import com.pineapple.sageos2.apps.OwnerAppResolver
import com.pineapple.sageos2.apps.OwnerAppStartupSession
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
    private val maxToolCallsPerTurn: Int = GoalCompletionPolicy.DEFAULT_MAX_TOOL_CALLS,
    private val maxVerificationRounds: Int = GoalCompletionPolicy.DEFAULT_MAX_VERIFICATION_ROUNDS,
    private val brainResponseTimeoutMs: Long = 120_000L,
    private val brainLoadTimeoutMs: Long = 30_000L,
    private val brainFirstTokenTimeoutMs: Long = 60_000L,
    private val brainStallTimeoutMs: Long = 30_000L
) {
    init {
        require(echoGuardMs >= 0L)
        require(maxToolCallsPerTurn in 1..16)
        require(maxVerificationRounds in 1..6)
        require(brainResponseTimeoutMs > 0L)
        require(brainLoadTimeoutMs > 0L)
        require(brainFirstTokenTimeoutMs > 0L)
        require(brainStallTimeoutMs > 0L)
        speech.attach(object : SpeechInputListener {
            override fun onWakeDetected(hit: WakeHit) = submit(SageEvent.WakeDetected(hit.generation, hit.profileId, hit.modeId, hit.acknowledgement, hit.command))
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
    private var brainTimeoutHandle: ScheduledHandle? = null
    private var brainStageTimeoutHandle: ScheduledHandle? = null
    private val toolCallsByTurn = mutableMapOf<Long, Int>()
    private val verificationTargetToolCountByTurn = mutableMapOf<Long, Int>()
    private val verificationRoundsByTurn = mutableMapOf<Long, Int>()
    private val ownerGoalByTurn = mutableMapOf<Long, String>()
    private val recoveryReplayGuardByTurn = mutableMapOf<Long, MutableList<RecoveryCompletionPolicy.ReplayGuard>>()
    private val recoveryReplayBlocksByTurn = mutableMapOf<Long, Int>()
    private val startupByTurn = mutableMapOf<Long, OwnerAppStartupSession>()
    private val capabilityExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "sage-capability").apply { isDaemon = true }
    }

    @Synchronized fun start() { submit(SageEvent.Start) }
    @Synchronized fun stop() { submit(SageEvent.Stop); speech.shutdown() }

    fun resumeRecoveredTask(task: TaskCheckpoint) {
        val metadata = task.metadata
        val ownerPrompt = metadata["ownerPrompt"]?.trim().orEmpty()
        if (ownerPrompt.isBlank()) {
            observer.onDiagnostic("recovery skipped: ${task.taskId} has no stored owner prompt")
            return
        }
        submit(SageEvent.RecoverTask(
            recoveredTaskId = task.taskId,
            ownerPrompt = ownerPrompt,
            priorPhase = metadata["phase"],
            lastAction = metadata["lastAction"],
            lastActionSignature = metadata["lastActionSignature"],
            lastActionSuccess = metadata["lastActionSuccess"],
            completedToolCalls = metadata["toolCount"]?.toIntOrNull() ?: 0,
            recoveryDepth = metadata["recoveryDepth"]?.toIntOrNull() ?: 1,
            replayGuardMetadata = RecoveryCompletionPolicy.replayGuardMetadata(
                RecoveryCompletionPolicy.replayGuards(metadata)
            )
        ))
    }
    @Synchronized fun submit(event: SageEvent) {
        process(coordinator.handle(event))
        observer.onStateChanged(coordinator.snapshot())
    }
    fun snapshot() = coordinator.snapshot()

    private fun process(effects: List<SageEffect>) = effects.forEach { effect ->
        try { process(effect) } catch (t: Throwable) { observer.onUnhandledFailure("effect failed: ${effect::class.simpleName}", t) }
    }

    private fun process(effect: SageEffect) {
        when (effect) {
            is SageEffect.SetListeningMode -> speech.setListening(effect.mode, effect.generation, effect.turnId)
            is SageEffect.ActivateMode -> modes.activate(effect.profileId, effect.modeId)
            is SageEffect.RecordOwnerInput -> recordOwnerInput(effect)
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
                        onFailure = {
                            val detail = it.message?.trim().orEmpty().ifBlank { "That device action did not complete." }
                            observer.onDiagnostic("fast action failed: turn=${effect.turnId} detail=$detail")
                            submit(SageEvent.ResponseReady(effect.turnId, detail, allowFollowUp = false))
                        }
                    )
                }
            }
            is SageEffect.QueryDeepBrain -> {
                ownerGoalByTurn.putIfAbsent(effect.turnId, effect.prompt)
                // Record mutations now so a later read-only call cannot hide them from recovery.
                recoveryReplayGuardByTurn[effect.turnId] = mutableListOf()
                checkpointTurnStarted(effect.turnId, effect.prompt)
                val open = FastCommandParser().parse(effect.prompt) as? FastCommand.OpenApp
                val app = open?.let { OwnerAppResolver().resolve(it.appName, ownerApps.snapshot()) }
                if (app != null && app.startupProcedure.isNotBlank()) {
                    startupByTurn[effect.turnId] = OwnerAppStartupSession(effect.prompt, app)
                }
                startBrain(effect.turnId, effect.prompt)
            }
            is SageEffect.QueryRecoveredBrain -> {
                val restoredToolCount = when {
                    effect.completedToolCalls > 0 -> effect.completedToolCalls.coerceAtMost(maxToolCallsPerTurn)
                    !effect.lastAction.isNullOrBlank() -> 1
                    else -> 0
                }
                ownerGoalByTurn[effect.turnId] = effect.ownerPrompt
                if (restoredToolCount > 0) toolCallsByTurn[effect.turnId] = restoredToolCount
                val guards = RecoveryCompletionPolicy.replayGuards(effect.replayGuardMetadata).toMutableList()
                RecoveryCompletionPolicy.replayGuard(effect.lastAction, effect.lastActionSignature)
                    ?.takeIf { it !in guards }?.let { guards.add(it) }
                recoveryReplayGuardByTurn[effect.turnId] = guards
                checkpointRecoveredTurnStarted(effect.turnId, effect, restoredToolCount)
                startBrain(
                    effect.turnId,
                    RecoveryCompletionPolicy.recoveryPrompt(
                        recoveredTaskId = effect.recoveredTaskId,
                        ownerGoal = effect.ownerPrompt,
                        priorPhase = effect.priorPhase,
                        lastAction = effect.lastAction,
                        lastActionSignature = effect.lastActionSignature,
                        lastActionSuccess = effect.lastActionSuccess,
                        completedToolCalls = restoredToolCount,
                        recoveryDepth = effect.recoveryDepth
                    )
                )
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
                clearBrainTimeouts()
                clearGoalRuntimeState(effect.turnId)
                startupByTurn.remove(effect.turnId)
                checkpointTurn(effect.turnId, TaskState.CANCELLED, "Turn cancelled before completion.", "")
                echoGuardHandle?.cancel(); echoGuardHandle = null
                followUpExpiryHandle?.cancel(); followUpExpiryHandle = null
            }
        }
    }

    private fun startBrain(turnId: Long, prompt: String) {
        brainJob?.cancel()
        val preRequestHealth = runCatching { brain.health() }.getOrNull()
        val coldStart = preRequestHealth?.lastLatencyMs == null
        val startup = startupByTurn[turnId]
        val baseProfile = BrainRequestPolicy.forPrompt(prompt, coldStart = coldStart)
        val requestProfile = if (startup == null) baseProfile else baseProfile.copy(outputTokens = 48)
        observer.onDiagnostic(
            "brain request profile: cold=$coldStart budget=${requestProfile.combinedCharacterBudget} output=${requestProfile.outputTokens}"
        )
        val core = sageCore.current()
        val memory = twinMemory.snapshot()
        val history = conversationHistory.recent(24).withoutOwnerTurn(turnId)
        val apps = ownerApps.snapshot()
        val mode = modes.current()
        val twinContext = twinContextRenderer.render(
            core,
            memory,
            history,
            if (startup == null) apps else EmptyOwnerAppProvider.snapshot(),
            mode,
            includeOperationalDetails = requestProfile.includeToolContext,
            currentRequest = prompt
        )
        val fullContext = if (requestProfile.includeTwinContext) {
            buildString {
                append(requestProfile.systemGuide)
                append("\n\n")
                append(twinContext)
                if (requestProfile.includeTaskContext && startup == null) {
                    append("\n\n")
                    append(TaskContinuityContextRenderer.render(taskContinuity?.active().orEmpty()))
                }
                if (requestProfile.includeToolContext && startup == null) {
                    append("\n\n")
                    append(BrainToolContextRenderer.render(capabilities.snapshot()))
                }
            }
        } else {
            requestProfile.systemGuide
        }
        val context = try { if (requestProfile.includeTwinContext) {
            BrainPromptBudget.fitSystemContext(
                fullContext,
                ownerPrompt = prompt,
                combinedCharacterBudget = requestProfile.combinedCharacterBudget,
                requiredContext = startup?.render().orEmpty()
            )
        } else {
            fullContext
        } } catch (error: IllegalArgumentException) {
            if (startup == null) throw error
            failStartup(turnId, "The saved app startup is too long for this local turn. Shorten its steps in Owner Apps before trying again. No further action was run.")
            return
        }
        clearBrainTimeouts()
        brainTimeoutHandle = scheduler.schedule(brainResponseTimeoutMs) { onBrainTimeout(turnId) }
        val startedJob = brain.start(BrainRequest(
            turnId = turnId,
            prompt = prompt,
            sageCore = core.takeIf { requestProfile.includeTwinContext },
            twinMemory = memory.takeIf { requestProfile.includeTwinContext },
            conversationHistory = history.takeIf { requestProfile.includeTwinContext },
            ownerApps = apps.takeIf { requestProfile.includeTwinContext },
            mode = mode.takeIf { requestProfile.includeTwinContext },
            twinContextText = context,
            maxOutputTokens = requestProfile.outputTokens,
            deterministic = requestProfile.deterministic,
            expectedLiteral = requestProfile.expectedLiteral,
            onProgress = ::onBrainProgress
        )) { result -> finishBrainAttempt(turnId, result) }
        val snapshot = coordinator.snapshot()
        if (snapshot.activeTurnId == turnId && snapshot.state == SageRuntimeState.THINKING_DEEP) {
            brainJob = startedJob
        } else {
            startedJob.cancel()
        }
    }

    @Synchronized
    private fun handleBrainResponse(response: BrainResponse) {
        brainJob = null
        clearBrainTimeouts()
        val snapshot = coordinator.snapshot()
        if (snapshot.activeTurnId != response.turnId) {
            observer.onDiagnostic("stale Brain response ignored before tool parsing: turn=${response.turnId}")
            return
        }

        response.provenance.limitation?.let { observer.onDiagnostic("brain limitation [${response.provenance.engine}]: $it") }
        observer.onDiagnostic(buildString {
            append("brain completed: engine=").append(response.provenance.engine)
            response.provenance.model?.let { append(" model=").append(it) }
            if (response.provenance.attempts.isNotEmpty()) {
                append(" evidence=").append(response.provenance.attempts.joinToString(", "))
            }
        })

        val directive = try {
            BrainToolDirectiveParser.parse(response.text)
        } catch (t: Throwable) {
            clearGoalRuntimeState(response.turnId)
            startupByTurn.remove(response.turnId)
            checkpointTurn(response.turnId, TaskState.FAILED, "Brain produced an invalid structured tool response.", "Retry reasoning from the stored owner prompt.")
            submit(SageEvent.BrainFailed(response.turnId, "invalid structured tool response: ${t.message ?: t::class.simpleName}"))
            return
        }

        if (directive == null) {
            val toolCount = toolCallsByTurn[response.turnId] ?: 0
            val verificationTarget = verificationTargetToolCountByTurn.remove(response.turnId)

            if (verificationTarget != null && toolCount == verificationTarget) {
                when (val verification = GoalCompletionPolicy.parseVerificationResponse(response.text)) {
                    null -> observer.onDiagnostic(
                        "goal verification protocol missing: turn=${response.turnId} tools=$toolCount"
                    )
                    is GoalCompletionPolicy.VerificationResult -> when (verification.status) {
                        GoalCompletionPolicy.VerificationStatus.VERIFIED -> {
                            val rounds = verificationRoundsByTurn[response.turnId] ?: 0
                            checkpointTurn(
                                response.turnId,
                                TaskState.COMPLETED,
                                "Owner goal completed and explicitly verified after $toolCount tool call(s).",
                                "",
                                mapOf(
                                    "phase" to "completed",
                                    "verified" to "true",
                                    "toolCount" to toolCount.toString(),
                                    "verificationRounds" to rounds.toString()
                                )
                            )
                            clearGoalRuntimeState(response.turnId)
                            startupByTurn.remove(response.turnId)
                            submit(SageEvent.ResponseReady(response.turnId, verification.ownerFacingText, true))
                            return
                        }
                        GoalCompletionPolicy.VerificationStatus.UNVERIFIED -> {
                            val rounds = verificationRoundsByTurn[response.turnId] ?: 0
                            checkpointTurn(
                                response.turnId,
                                TaskState.WAITING,
                                "Sage could not verify the final state with available evidence.",
                                "Resume from the stored owner goal when new evidence or another safe verification path is available.",
                                mapOf(
                                    "phase" to "verification_waiting",
                                    "verified" to "false",
                                    "toolCount" to toolCount.toString(),
                                    "verificationRounds" to rounds.toString()
                                )
                            )
                            val unverified = GoalCompletionPolicy.unverifiedFinal(verification.ownerFacingText)
                            clearGoalRuntimeState(response.turnId)
                            startupByTurn.remove(response.turnId)
                            submit(SageEvent.ResponseReady(response.turnId, unverified, false))
                            return
                        }
                    }
                }
            }

            if (toolCount > 0) {
                val nextRound = (verificationRoundsByTurn[response.turnId] ?: 0) + 1
                if (nextRound <= maxVerificationRounds) {
                    verificationRoundsByTurn[response.turnId] = nextRound
                    verificationTargetToolCountByTurn[response.turnId] = toolCount
                    val ownerGoal = ownerGoalByTurn[response.turnId]
                        ?: taskContinuity?.get(runtimeTaskId(response.turnId))?.metadata?.get("ownerPrompt")
                        ?: "Complete owner turn ${response.turnId}"
                    checkpointTurn(
                        response.turnId,
                        TaskState.ACTIVE,
                        "Tool-backed work finished a reasoning pass; verifying the owner's requested result.",
                        "Verify the result with direct available evidence. Correct it if needed before replying.",
                        mapOf(
                            "phase" to "verification",
                            "verified" to "false",
                            "toolCount" to toolCount.toString(),
                            "verificationRound" to nextRound.toString()
                        )
                    )
                    observer.onDiagnostic(
                        "goal verification: turn=${response.turnId} round=$nextRound tools=$toolCount"
                    )
                    startBrain(
                        response.turnId,
                        GoalCompletionPolicy.verificationPrompt(ownerGoal, toolCount, nextRound)
                    )
                    return
                }

                val unverified = GoalCompletionPolicy.unverifiedFinal(response.text)
                checkpointTurn(
                    response.turnId,
                    TaskState.WAITING,
                    "Sage completed the available actions but exhausted the bounded verification loop.",
                    "Resume verification from the stored owner goal; do not replay completed side effects.",
                    mapOf(
                        "phase" to "verification_waiting",
                        "verified" to "false",
                        "toolCount" to toolCount.toString(),
                        "verificationRounds" to (verificationRoundsByTurn[response.turnId] ?: 0).toString()
                    )
                )
                clearGoalRuntimeState(response.turnId)
                startupByTurn.remove(response.turnId)
                submit(SageEvent.ResponseReady(response.turnId, unverified, false))
                return
            }

            checkpointTurn(
                response.turnId,
                TaskState.COMPLETED,
                "Owner turn completed without external actions.",
                "",
                mapOf("phase" to "completed", "verified" to "not_required")
            )
            clearGoalRuntimeState(response.turnId)
            startupByTurn.remove(response.turnId)
            submit(SageEvent.ResponseReady(response.turnId, response.text, true))
            return
        }

        // A verification pass that emits another tool has discovered unfinished work.
        // Clear the target so the next prose response must be verified again after that action.
        verificationTargetToolCountByTurn.remove(response.turnId)

        val action = DeviceAction(directive.name, directive.arguments)
        if (handleBlockedRecoveryReplay(response.turnId, action)) return

        val nextCount = (toolCallsByTurn[response.turnId] ?: 0) + 1
        if (nextCount > maxToolCallsPerTurn) {
            clearGoalRuntimeState(response.turnId)
            startupByTurn.remove(response.turnId)
            checkpointTurn(response.turnId, TaskState.FAILED, "Structured tool call ceiling reached.", "Continue from the stored owner prompt with a shorter tool plan.")
            submit(SageEvent.BrainFailed(response.turnId, "structured tool call limit exceeded"))
            return
        }
        toolCallsByTurn[response.turnId] = nextCount

        if (startupByTurn[response.turnId]?.supports(action) == false) {
            failStartup(response.turnId, "That saved startup needs an unsupported action (${action.name}). I stopped before running it.")
            return
        }
        // Persist protection before execution: another crash can leave this action's result unknown.
        recoveryReplayGuardByTurn[response.turnId]?.let { guards ->
            RecoveryCompletionPolicy.replayGuard(action.name, RecoveryCompletionPolicy.actionSignature(action))
                ?.takeIf { it !in guards }?.let { guards.add(it) }
        }
        checkpointTurn(
            response.turnId,
            TaskState.ACTIVE,
            "Executing structured capability ${action.name} (step $nextCount of $maxToolCallsPerTurn).",
            "Wait for the capability result, then continue reasoning without replaying this action.",
            mapOf(
                "phase" to "capability",
                "lastAction" to action.name,
                "lastActionSignature" to RecoveryCompletionPolicy.actionSignature(action),
                "lastActionSuccess" to "unknown",
                "toolCount" to nextCount.toString()
            )
        )
        capabilityJob?.cancel(true)
        capabilityJob = capabilityExecutor.submit {
            val result = runCatching { capabilities.execute(action) }
                .getOrElse { CapabilityResult(false, "Capability execution failed: ${it.message ?: it::class.java.simpleName}") }
            continueAfterCapability(response.turnId, action, result)
        }
    }

    private fun handleBlockedRecoveryReplay(turnId: Long, action: DeviceAction): Boolean {
        if (coordinator.snapshot().activeTurnOrigin != TurnOrigin.RECOVERY) return false
        val guards = recoveryReplayGuardByTurn[turnId] ?: return false
        if (guards.none { RecoveryCompletionPolicy.shouldBlockReplay(it, action) }) return false

        val blocks = (recoveryReplayBlocksByTurn[turnId] ?: 0) + 1
        val toolCount = (toolCallsByTurn[turnId] ?: 1).coerceAtLeast(1)
        val nextRound = (verificationRoundsByTurn[turnId] ?: 0) + 1
        recoveryReplayBlocksByTurn[turnId] = blocks

        if (blocks > RecoveryCompletionPolicy.MAX_REPLAY_BLOCKS || nextRound > maxVerificationRounds) {
            checkpointTurn(
                turnId,
                TaskState.WAITING,
                "Recovered goal stopped before blindly replaying ${action.name}.",
                "Obtain fresh evidence before retrying the interrupted side effect.",
                mapOf(
                    "phase" to "recovery_waiting",
                    "verified" to "false",
                    "replayBlocked" to "true",
                    "lastAction" to action.name
                )
            )
            val text = GoalCompletionPolicy.unverifiedFinal(
                "I recovered the task, but I could not safely prove whether ${action.name} already happened, so I did not repeat it."
            )
            clearGoalRuntimeState(turnId)
            startupByTurn.remove(turnId)
            submit(SageEvent.ResponseReady(turnId, text, false))
            return true
        }

        verificationRoundsByTurn[turnId] = nextRound
        verificationTargetToolCountByTurn[turnId] = toolCount
        checkpointTurn(
            turnId,
            TaskState.ACTIVE,
            "Blocked blind replay of interrupted side effect ${action.name}; verification required.",
            "Verify the current real state without repeating the protected side effect.",
            mapOf(
                "phase" to "recovery_verification",
                "verified" to "false",
                "replayBlocked" to "true",
                "verificationRound" to nextRound.toString()
            )
        )
        observer.onDiagnostic("recovery replay blocked: turn=$turnId action=${action.name} round=$nextRound")
        startBrain(
            turnId,
            RecoveryCompletionPolicy.replayBlockedVerificationPrompt(
                ownerGoal = ownerGoalByTurn[turnId].orEmpty(),
                blockedAction = action,
                completedToolCalls = toolCount,
                verificationRound = nextRound
            )
        )
        return true
    }

    @Synchronized
    private fun finishBrainAttempt(turnId: Long, result: Result<BrainResponse>) {
        brainJob = null
        clearBrainTimeouts()
        result.fold(
            onSuccess = { response -> handleBrainResponse(response) },
            onFailure = {
                startupByTurn.remove(turnId)
                clearGoalRuntimeState(turnId)
                checkpointTurn(turnId, TaskState.FAILED, "Brain failed before the turn completed.", "Review diagnostics and retry from the stored owner prompt.")
                submit(SageEvent.BrainFailed(turnId, it.message ?: it::class.simpleName.orEmpty()))
            }
        )
    }

    @Synchronized
    private fun continueAfterCapability(turnId: Long, action: DeviceAction, result: CapabilityResult) {
        capabilityJob = null
        if (coordinator.snapshot().activeTurnId != turnId) {
            observer.onDiagnostic("stale capability result ignored: turn=$turnId action=${action.name}")
            return
        }
        observer.onDiagnostic("capability result: turn=$turnId action=${action.name} success=${result.success}")
        // Tool success reports only this call's result. It cannot authorize an interrupted mutation.
        startupByTurn[turnId]?.let { startup ->
            if (!result.success) {
                failStartup(turnId, "I stopped the saved app startup: ${result.detail}")
                return
            }
            startup.recordCompleted(action)
        }
        checkpointTurn(
            turnId,
            TaskState.ACTIVE,
            "Capability ${action.name} returned success=${result.success}.",
            "Continue reasoning from the capability result; do not replay the completed capability call.",
            mapOf(
                "phase" to "brain_after_capability",
                "lastAction" to action.name,
                "lastActionSignature" to RecoveryCompletionPolicy.actionSignature(action),
                "lastActionSuccess" to result.success.toString(),
                "toolCount" to (toolCallsByTurn[turnId] ?: 0).toString()
            )
        )
        startBrain(turnId, BrainToolContextRenderer.renderResult(action, result))
    }

    private fun failStartup(turnId: Long, detail: String) {
        startupByTurn.remove(turnId)
        clearGoalRuntimeState(turnId)
        clearBrainTimeouts()
        checkpointTurn(turnId, TaskState.FAILED, detail, "Review saved startup steps before retrying; do not replay completed actions.")
        observer.onDiagnostic("owner app startup stopped: turn=$turnId detail=$detail")
        submit(SageEvent.ResponseReady(turnId, detail, false))
    }

    private fun checkpointRecoveredTurnStarted(
        turnId: Long,
        effect: SageEffect.QueryRecoveredBrain,
        restoredToolCount: Int
    ) {
        val store = taskContinuity ?: return
        val id = runtimeTaskId(turnId)
        TaskRecoveryManager(store).supersedeOlderRuntimeTasks(id)
        val cleanPrompt = effect.ownerPrompt.replace(Regex("\\s+"), " ").trim().take(4_000)
        val metadata = mutableMapOf(
            "kind" to TaskRecoveryManager.RUNTIME_TURN_KIND,
            "turnId" to turnId.toString(),
            "ownerPrompt" to cleanPrompt,
            "phase" to "goal_recovery",
            "verified" to "false",
            "recoveredFrom" to effect.recoveredTaskId,
            "recoveryDepth" to effect.recoveryDepth.toString(),
            "toolCount" to restoredToolCount.toString()
        )
        effect.priorPhase?.takeIf { it.isNotBlank() }?.let { metadata["recoveryPriorPhase"] = it }
        effect.lastAction?.takeIf { it.isNotBlank() }?.let { metadata["lastAction"] = it }
        effect.lastActionSignature?.takeIf { it.isNotBlank() }?.let { metadata["lastActionSignature"] = it }
        effect.lastActionSuccess?.takeIf { it.isNotBlank() }?.let { metadata["lastActionSuccess"] = it }
        metadata.putAll(RecoveryCompletionPolicy.replayGuardMetadata(
            recoveryReplayGuardByTurn[turnId].orEmpty()
        ))
        store.upsert(
            TaskCheckpoint(
                taskId = id,
                title = cleanPrompt.take(80).ifBlank { "Recovered Sage owner turn $turnId" },
                state = TaskState.ACTIVE,
                summary = "Recovered interrupted owner goal; Sage is verifying current state before continuing.",
                nextStep = "Resume from current evidence without blindly replaying the prior side effect.",
                updatedAtMs = System.currentTimeMillis(),
                metadata = metadata
            )
        )
    }

    private fun checkpointTurnStarted(turnId: Long, ownerPrompt: String) {
        val store = taskContinuity ?: return
        val id = runtimeTaskId(turnId)
        TaskRecoveryManager(store).supersedeOlderRuntimeTasks(id)
        val cleanPrompt = ownerPrompt.replace(Regex("\\s+"), " ").trim().take(4_000)
        store.upsert(
            TaskCheckpoint(
                taskId = runtimeTaskId(turnId),
                title = cleanPrompt.take(80).ifBlank { "Sage owner turn $turnId" },
                state = TaskState.ACTIVE,
                summary = "Owner goal accepted; Sage owns the turn until the result is finished or explicitly left waiting.",
                nextStep = "Reason, act when needed, then verify any tool-backed result before replying.",
                updatedAtMs = System.currentTimeMillis(),
                metadata = mapOf(
                    "kind" to TaskRecoveryManager.RUNTIME_TURN_KIND,
                    "turnId" to turnId.toString(),
                    "ownerPrompt" to cleanPrompt,
                    "phase" to "goal_reasoning",
                    "verified" to "false"
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
                metadata = existing.metadata + metadata + recoveryReplayGuardByTurn[turnId]?.let {
                    RecoveryCompletionPolicy.replayGuardMetadata(it)
                }.orEmpty()
            )
        )
    }

    private fun runtimeTaskId(turnId: Long) = "runtime:turn:$turnId"

    private fun clearGoalRuntimeState(turnId: Long) {
        toolCallsByTurn.remove(turnId)
        verificationTargetToolCountByTurn.remove(turnId)
        verificationRoundsByTurn.remove(turnId)
        ownerGoalByTurn.remove(turnId)
        recoveryReplayGuardByTurn.remove(turnId)
        recoveryReplayBlocksByTurn.remove(turnId)
    }

    @Synchronized
    private fun onBrainProgress(progress: BrainProgress) {
        observer.onBrainProgress(progress)
        val snapshot = coordinator.snapshot()
        if (snapshot.activeTurnId != progress.turnId || snapshot.state != SageRuntimeState.THINKING_DEEP) return
        when (progress.stage) {
            BrainProgressStage.PREPARING -> Unit
            BrainProgressStage.LOADING_MODEL -> scheduleBrainStageTimeout(
                progress.turnId,
                brainLoadTimeoutMs,
                "model loading"
            )
            BrainProgressStage.READING_CONTEXT -> scheduleBrainStageTimeout(
                progress.turnId,
                brainFirstTokenTimeoutMs,
                "first response token"
            )
            BrainProgressStage.GENERATING -> {
                val hasToken = (progress.telemetry?.generatedTokens ?: 0) > 0
                scheduleBrainStageTimeout(
                    progress.turnId,
                    if (hasToken) brainStallTimeoutMs else brainFirstTokenTimeoutMs,
                    if (hasToken) "response generation" else "first response token"
                )
            }
        }
    }

    @Synchronized
    private fun scheduleBrainStageTimeout(turnId: Long, timeoutMs: Long, stage: String) {
        brainStageTimeoutHandle?.cancel()
        brainStageTimeoutHandle = scheduler.schedule(timeoutMs) {
            onBrainTimeout(turnId, stage, timeoutMs)
        }
    }

    private fun clearBrainTimeouts() {
        brainTimeoutHandle?.cancel()
        brainTimeoutHandle = null
        brainStageTimeoutHandle?.cancel()
        brainStageTimeoutHandle = null
    }

    @Synchronized
    private fun onBrainTimeout(
        turnId: Long,
        stage: String = "response",
        timeoutMs: Long = brainResponseTimeoutMs
    ) {
        val snapshot = coordinator.snapshot()
        if (snapshot.activeTurnId != turnId || snapshot.state != SageRuntimeState.THINKING_DEEP) return
        if (brainJob?.turnId == turnId) brainJob?.cancel()
        startupByTurn.remove(turnId)
        clearGoalRuntimeState(turnId)
        brainJob = null
        clearBrainTimeouts()
        checkpointTurn(
            turnId,
            TaskState.FAILED,
            "Brain timed out during $stage before the turn completed.",
            "Retry from the stored owner prompt; do not replay a completed capability call."
        )
        submit(SageEvent.BrainFailed(
            turnId,
            "local Brain $stage timed out after ${timeoutMs / 1_000L} seconds"
        ))
    }

    private fun recordOwnerInput(effect: SageEffect.RecordOwnerInput) {
        val store = conversationHistory as? ConversationHistoryStore ?: return
        val input = when (effect.origin) {
            TurnOrigin.TEXT -> ConversationInput.TEXT
            TurnOrigin.VOICE_WAKE, TurnOrigin.PUSH_TO_TALK -> ConversationInput.VOICE
            TurnOrigin.NONE, TurnOrigin.RECOVERY -> ConversationInput.SYSTEM
        }
        store.record(
            ConversationEntry(
                id = UUID.randomUUID().toString(),
                turnId = effect.turnId,
                speaker = ConversationSpeaker.OWNER,
                input = input,
                text = effect.text,
                timestampEpochMs = System.currentTimeMillis()
            )
        )
    }

    private fun ConversationHistorySnapshot.withoutOwnerTurn(turnId: Long): ConversationHistorySnapshot = copy(
        entries = entries.filterNot { it.turnId == turnId && it.speaker == ConversationSpeaker.OWNER }
    )

    private fun recordSageResponse(turnId: Long, text: String, input: ConversationInput) {
        (conversationHistory as? ConversationHistoryStore)?.record(
            ConversationEntry(UUID.randomUUID().toString(), turnId, ConversationSpeaker.SAGE, input, text, System.currentTimeMillis())
        )
    }
}
