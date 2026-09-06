package com.pineapple.sageos2.core

data class SageRuntimeSnapshot(
    val state: SageRuntimeState,
    val activeTurnId: Long,
    val recognizerGeneration: Long
)

class SageTurnCoordinator(
    private val router: SageCommandRouter = SageCommandRouter()
) {
    private var state = SageRuntimeState.STOPPED
    private var activeTurnId = 0L
    private var recognizerGeneration = 0L
    private var nextTurnId = 1L
    private var followUpAfterSpeech = false

    @Synchronized
    fun snapshot() = SageRuntimeSnapshot(state, activeTurnId, recognizerGeneration)

    @Synchronized
    fun handle(event: SageEvent): List<SageEffect> = when (event) {
        SageEvent.Start -> start()
        SageEvent.Stop -> stop()
        is SageEvent.WakeDetected -> onWake(event)
        is SageEvent.WakeAcknowledgementSpoken -> onWakeAckSpoken(event)
        is SageEvent.TranscriptFinal -> onTranscript(event)
        is SageEvent.TextSubmitted -> onText(event)
        is SageEvent.ResponseReady -> onResponse(event)
        is SageEvent.BrainFailed -> onBrainFailed(event)
        is SageEvent.SpeechFinished -> onSpeechFinished(event)
        is SageEvent.EchoGuardElapsed -> onEchoGuardElapsed(event)
    }

    private fun start(): List<SageEffect> {
        if (state != SageRuntimeState.STOPPED && state != SageRuntimeState.ERROR) return emptyList()
        activeTurnId = 0L
        recognizerGeneration += 1
        state = SageRuntimeState.IDLE_WAKE
        return listOf(SageEffect.StartWakeListening(recognizerGeneration))
    }

    private fun stop(): List<SageEffect> {
        val effects = mutableListOf<SageEffect>(SageEffect.StopListening)
        if (activeTurnId != 0L) effects += SageEffect.CancelTurn(activeTurnId)
        state = SageRuntimeState.STOPPED
        activeTurnId = 0L
        return effects
    }

    private fun onWake(event: SageEvent.WakeDetected): List<SageEffect> {
        if (event.recognizerGeneration != recognizerGeneration) {
            return listOf(SageEffect.IgnoreStaleCallback("wake generation ${event.recognizerGeneration} != $recognizerGeneration"))
        }

        if (state == SageRuntimeState.THINKING_FAST || state == SageRuntimeState.THINKING_DEEP) {
            return listOf(SageEffect.SpeakTransient("I'm thinking"))
        }

        if (state != SageRuntimeState.IDLE_WAKE && state != SageRuntimeState.FOLLOW_UP_LISTENING) {
            return listOf(SageEffect.RecordDiagnostic("wake ignored in state $state"))
        }

        activeTurnId = nextTurnId++
        state = SageRuntimeState.ACKNOWLEDGING_WAKE
        return listOf(SageEffect.StopListening, SageEffect.Speak(activeTurnId, "Yes"))
    }

    private fun onWakeAckSpoken(event: SageEvent.WakeAcknowledgementSpoken): List<SageEffect> {
        if (event.turnId != activeTurnId || state != SageRuntimeState.ACKNOWLEDGING_WAKE) return stale("wake acknowledgement")
        recognizerGeneration += 1
        state = SageRuntimeState.COMMAND_LISTENING
        return listOf(SageEffect.StartCommandListening(activeTurnId, recognizerGeneration))
    }

    private fun onTranscript(event: SageEvent.TranscriptFinal): List<SageEffect> {
        if (event.turnId != activeTurnId) return stale("turn ${event.turnId} != $activeTurnId")
        if (event.recognizerGeneration != recognizerGeneration) return stale("transcript generation ${event.recognizerGeneration} != $recognizerGeneration")
        if (state != SageRuntimeState.COMMAND_LISTENING && state != SageRuntimeState.FOLLOW_UP_LISTENING) return stale("transcript in $state")
        return dispatch(activeTurnId, event.text)
    }

    private fun onText(event: SageEvent.TextSubmitted): List<SageEffect> {
        if (activeTurnId != 0L && state != SageRuntimeState.IDLE_WAKE && state != SageRuntimeState.STOPPED) {
            return listOf(SageEffect.RecordDiagnostic("text queued until active turn closes"))
        }
        activeTurnId = nextTurnId++
        return dispatch(activeTurnId, event.text)
    }

    private fun dispatch(turnId: Long, text: String): List<SageEffect> {
        val decision = router.route(text)
        return when (decision.route) {
            SageRoute.OWNER_WORKFLOW -> {
                state = SageRuntimeState.IDLE_WAKE
                activeTurnId = 0L
                recognizerGeneration += 1
                listOf(
                    SageEffect.StopListening,
                    SageEffect.LaunchOwnerWorkflow(turnId, requireNotNull(decision.workflowId)),
                    SageEffect.StartWakeListening(recognizerGeneration)
                )
            }
            SageRoute.FAST_DEVICE -> {
                state = SageRuntimeState.THINKING_FAST
                listOf(SageEffect.StopListening, SageEffect.ExecuteFast(turnId, decision.normalizedText))
            }
            SageRoute.DEEP_REASONING -> {
                state = SageRuntimeState.THINKING_DEEP
                listOf(SageEffect.StopListening, SageEffect.QueryDeepBrain(turnId, decision.normalizedText))
            }
        }
    }

    private fun onResponse(event: SageEvent.ResponseReady): List<SageEffect> {
        if (event.turnId != activeTurnId || (state != SageRuntimeState.THINKING_FAST && state != SageRuntimeState.THINKING_DEEP)) return stale("response")
        followUpAfterSpeech = event.allowFollowUp
        state = SageRuntimeState.SPEAKING
        return listOf(SageEffect.Speak(activeTurnId, event.text))
    }

    private fun onBrainFailed(event: SageEvent.BrainFailed): List<SageEffect> {
        if (event.turnId != activeTurnId) return stale("brain failure")
        followUpAfterSpeech = true
        state = SageRuntimeState.SPEAKING
        return listOf(
            SageEffect.RecordDiagnostic("brain failed: ${event.reason}"),
            SageEffect.Speak(activeTurnId, "I hit a brain problem, but I'm still here.")
        )
    }

    private fun onSpeechFinished(event: SageEvent.SpeechFinished): List<SageEffect> {
        if (event.turnId != activeTurnId || state != SageRuntimeState.SPEAKING) return stale("speech finished")
        state = SageRuntimeState.ECHO_GUARD
        return listOf(SageEffect.StartEchoGuard(activeTurnId))
    }

    private fun onEchoGuardElapsed(event: SageEvent.EchoGuardElapsed): List<SageEffect> {
        if (event.turnId != activeTurnId || state != SageRuntimeState.ECHO_GUARD) return stale("echo guard")
        return if (followUpAfterSpeech) {
            recognizerGeneration += 1
            state = SageRuntimeState.FOLLOW_UP_LISTENING
            listOf(SageEffect.StartFollowUpListening(activeTurnId, recognizerGeneration))
        } else {
            activeTurnId = 0L
            recognizerGeneration += 1
            state = SageRuntimeState.IDLE_WAKE
            listOf(SageEffect.StartWakeListening(recognizerGeneration))
        }
    }

    private fun stale(reason: String) = listOf(SageEffect.IgnoreStaleCallback(reason))
}
