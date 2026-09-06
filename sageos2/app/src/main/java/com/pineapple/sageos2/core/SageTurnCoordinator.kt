package com.pineapple.sageos2.core

import java.util.ArrayDeque

data class SageRuntimeSnapshot(
    val state: SageRuntimeState,
    val listeningMode: SageListeningMode,
    val activeTurnId: Long,
    val recognizerGeneration: Long,
    val queuedTextCount: Int
)

class SageTurnCoordinator(
    private val router: SageCommandRouter = SageCommandRouter(),
    private val typedQueueCapacity: Int = 16
) {
    init { require(typedQueueCapacity > 0) { "typedQueueCapacity must be positive" } }

    private var state = SageRuntimeState.STOPPED
    private var listeningMode = SageListeningMode.OFF
    private var activeTurnId = 0L
    private var recognizerGeneration = 0L
    private var nextTurnId = 1L
    private var followUpAfterSpeech = false
    private val pendingTypedInputs = ArrayDeque<String>()

    @Synchronized fun snapshot() = SageRuntimeSnapshot(state, listeningMode, activeTurnId, recognizerGeneration, pendingTypedInputs.size)

    @Synchronized fun handle(event: SageEvent): List<SageEffect> = when (event) {
        SageEvent.Start -> start()
        SageEvent.Stop -> stop()
        is SageEvent.WakeDetected -> onWake(event)
        is SageEvent.WakeAcknowledgementSpoken -> onWakeAckSpoken(event)
        is SageEvent.TranscriptFinal -> onTranscript(event)
        is SageEvent.RecognitionFailed -> onRecognitionFailed(event)
        is SageEvent.TextSubmitted -> onText(event)
        is SageEvent.ResponseReady -> onResponse(event)
        is SageEvent.BrainFailed -> onBrainFailed(event)
        is SageEvent.SpeechFinished -> onSpeechFinished(event)
        is SageEvent.EchoGuardElapsed -> onEchoGuardElapsed(event)
    }

    private fun start(): List<SageEffect> {
        if (state != SageRuntimeState.STOPPED && state != SageRuntimeState.ERROR) return emptyList()
        activeTurnId = 0L
        state = SageRuntimeState.IDLE_WAKE
        return listOf(changeListening(SageListeningMode.WAKE_ONLY))
    }

    private fun stop(): List<SageEffect> {
        val effects = mutableListOf<SageEffect>(changeListening(SageListeningMode.OFF))
        if (activeTurnId != 0L) effects += SageEffect.CancelTurn(activeTurnId)
        pendingTypedInputs.clear(); followUpAfterSpeech = false; state = SageRuntimeState.STOPPED; activeTurnId = 0L
        return effects
    }

    private fun onWake(event: SageEvent.WakeDetected): List<SageEffect> {
        if (event.recognizerGeneration != recognizerGeneration || listeningMode != SageListeningMode.WAKE_ONLY) return stale("wake generation/mode mismatch")
        if (state == SageRuntimeState.THINKING_FAST || state == SageRuntimeState.THINKING_DEEP) return listOf(SageEffect.SpeakTransient("I'm thinking"))
        if (state != SageRuntimeState.IDLE_WAKE && state != SageRuntimeState.FOLLOW_UP_LISTENING) return listOf(SageEffect.RecordDiagnostic("wake ignored in state $state"))
        activeTurnId = nextTurnId++
        state = SageRuntimeState.ACKNOWLEDGING_WAKE
        return listOf(
            changeListening(SageListeningMode.OFF),
            SageEffect.ActivateMode(event.profileId, event.modeId),
            SageEffect.Speak(activeTurnId, event.acknowledgement.ifBlank { "Yes" })
        )
    }

    private fun onWakeAckSpoken(event: SageEvent.WakeAcknowledgementSpoken): List<SageEffect> {
        if (event.turnId != activeTurnId || state != SageRuntimeState.ACKNOWLEDGING_WAKE) return stale("wake acknowledgement")
        state = SageRuntimeState.COMMAND_LISTENING
        return listOf(changeListening(SageListeningMode.COMMAND, activeTurnId))
    }

    private fun onTranscript(event: SageEvent.TranscriptFinal): List<SageEffect> {
        if (!validRecognition(event.turnId, event.recognizerGeneration)) return stale("transcript turn/generation mismatch")
        val expected = when (state) {
            SageRuntimeState.COMMAND_LISTENING -> SageListeningMode.COMMAND
            SageRuntimeState.FOLLOW_UP_LISTENING -> SageListeningMode.FOLLOW_UP
            else -> null
        }
        if (expected == null || listeningMode != expected) return stale("transcript in $state/$listeningMode")
        if (event.text.isBlank()) return onRecognitionFailed(SageEvent.RecognitionFailed(event.turnId, event.recognizerGeneration, -1))
        return dispatch(activeTurnId, event.text)
    }

    private fun onRecognitionFailed(event: SageEvent.RecognitionFailed): List<SageEffect> {
        if (!validRecognition(event.turnId, event.recognizerGeneration)) return stale("recognition error turn/generation mismatch")
        if (state != SageRuntimeState.COMMAND_LISTENING && state != SageRuntimeState.FOLLOW_UP_LISTENING) return stale("recognition error in $state")
        followUpAfterSpeech = true
        state = SageRuntimeState.SPEAKING
        return listOf(
            changeListening(SageListeningMode.OFF),
            SageEffect.RecordDiagnostic("recognition failed code=${event.code}"),
            SageEffect.Speak(activeTurnId, "I didn't catch that.")
        )
    }

    private fun onText(event: SageEvent.TextSubmitted): List<SageEffect> {
        val cleaned = event.text.trim()
        if (cleaned.isEmpty()) return listOf(SageEffect.TypedInputRejected("empty input"))
        val canDispatchImmediately = state == SageRuntimeState.IDLE_WAKE || state == SageRuntimeState.STOPPED || state == SageRuntimeState.FOLLOW_UP_LISTENING
        if (canDispatchImmediately) { activeTurnId = nextTurnId++; return dispatch(activeTurnId, cleaned) }
        if (pendingTypedInputs.size >= typedQueueCapacity) return listOf(SageEffect.TypedInputRejected("typed queue full"))
        pendingTypedInputs.addLast(cleaned)
        return listOf(SageEffect.TypedInputQueued(pendingTypedInputs.size))
    }

    private fun dispatch(turnId: Long, text: String): List<SageEffect> {
        followUpAfterSpeech = false
        val decision = router.route(text)
        return when (decision.route) {
            SageRoute.OWNER_WORKFLOW -> {
                state = SageRuntimeState.IDLE_WAKE; activeTurnId = 0L
                listOf(changeListening(SageListeningMode.WAKE_ONLY), SageEffect.LaunchOwnerWorkflow(turnId, requireNotNull(decision.workflowId)))
            }
            SageRoute.FAST_DEVICE -> {
                state = SageRuntimeState.THINKING_FAST
                listOf(changeListening(SageListeningMode.WAKE_ONLY, turnId), SageEffect.ExecuteFast(turnId, decision.normalizedText))
            }
            SageRoute.DEEP_REASONING -> {
                state = SageRuntimeState.THINKING_DEEP
                listOf(changeListening(SageListeningMode.WAKE_ONLY, turnId), SageEffect.QueryDeepBrain(turnId, decision.normalizedText))
            }
        }
    }

    private fun onResponse(event: SageEvent.ResponseReady): List<SageEffect> {
        if (event.turnId != activeTurnId || (state != SageRuntimeState.THINKING_FAST && state != SageRuntimeState.THINKING_DEEP)) return stale("response")
        followUpAfterSpeech = event.allowFollowUp; state = SageRuntimeState.SPEAKING
        return listOf(changeListening(SageListeningMode.OFF), SageEffect.Speak(activeTurnId, event.text))
    }

    private fun onBrainFailed(event: SageEvent.BrainFailed): List<SageEffect> {
        if (event.turnId != activeTurnId) return stale("brain failure")
        followUpAfterSpeech = true; state = SageRuntimeState.SPEAKING
        return listOf(changeListening(SageListeningMode.OFF), SageEffect.RecordDiagnostic("brain failed: ${event.reason}"), SageEffect.Speak(activeTurnId, "I hit a brain problem, but I'm still here."))
    }

    private fun onSpeechFinished(event: SageEvent.SpeechFinished): List<SageEffect> {
        if (event.turnId != activeTurnId || state != SageRuntimeState.SPEAKING) return stale("speech finished")
        state = SageRuntimeState.ECHO_GUARD
        return listOf(SageEffect.StartEchoGuard(activeTurnId))
    }

    private fun onEchoGuardElapsed(event: SageEvent.EchoGuardElapsed): List<SageEffect> {
        if (event.turnId != activeTurnId || state != SageRuntimeState.ECHO_GUARD) return stale("echo guard")
        if (pendingTypedInputs.isNotEmpty()) {
            val next = pendingTypedInputs.removeFirst(); activeTurnId = nextTurnId++
            return listOf(SageEffect.RecordDiagnostic("dispatching queued typed input")) + dispatch(activeTurnId, next)
        }
        return if (followUpAfterSpeech) {
            state = SageRuntimeState.FOLLOW_UP_LISTENING
            listOf(changeListening(SageListeningMode.FOLLOW_UP, activeTurnId))
        } else {
            activeTurnId = 0L; state = SageRuntimeState.IDLE_WAKE
            listOf(changeListening(SageListeningMode.WAKE_ONLY))
        }
    }

    private fun validRecognition(turnId: Long, generation: Long) = turnId == activeTurnId && generation == recognizerGeneration

    private fun changeListening(mode: SageListeningMode, turnId: Long = 0L): SageEffect.SetListeningMode {
        recognizerGeneration += 1; listeningMode = mode
        return SageEffect.SetListeningMode(mode, recognizerGeneration, turnId)
    }

    private fun stale(reason: String) = listOf(SageEffect.IgnoreStaleCallback(reason))
}
