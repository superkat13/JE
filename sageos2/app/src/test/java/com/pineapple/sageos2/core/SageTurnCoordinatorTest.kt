package com.pineapple.sageos2.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SageTurnCoordinatorTest {
    @Test
    fun wakeFlowSaysYesThenListensForCommand() {
        val coordinator = SageTurnCoordinator()
        coordinator.handle(SageEvent.Start)
        val wakeGeneration = coordinator.snapshot().recognizerGeneration

        val wakeEffects = coordinator.handle(SageEvent.WakeDetected(wakeGeneration))
        val turn = coordinator.snapshot().activeTurnId

        assertEquals(SageRuntimeState.ACKNOWLEDGING_WAKE, coordinator.snapshot().state)
        assertTrue(wakeEffects.contains(SageEffect.Speak(turn, "Yes")))

        val ackEffects = coordinator.handle(SageEvent.WakeAcknowledgementSpoken(turn))
        assertEquals(SageRuntimeState.COMMAND_LISTENING, coordinator.snapshot().state)
        assertEquals(SageListeningMode.COMMAND, coordinator.snapshot().listeningMode)
        assertTrue(ackEffects.any { it is SageEffect.SetListeningMode && it.mode == SageListeningMode.COMMAND })
    }

    @Test
    fun thinkingUsesWakeOnlyAndAcknowledgesWakeWithoutCancellingThought() {
        val coordinator = SageTurnCoordinator()
        coordinator.handle(SageEvent.Start)
        var generation = coordinator.snapshot().recognizerGeneration
        coordinator.handle(SageEvent.WakeDetected(generation))
        val turn = coordinator.snapshot().activeTurnId
        coordinator.handle(SageEvent.WakeAcknowledgementSpoken(turn))
        generation = coordinator.snapshot().recognizerGeneration
        coordinator.handle(SageEvent.TranscriptFinal(turn, generation, "explain quantum tunneling"))

        val thinking = coordinator.snapshot()
        assertEquals(SageRuntimeState.THINKING_DEEP, thinking.state)
        assertEquals(SageListeningMode.WAKE_ONLY, thinking.listeningMode)

        val effects = coordinator.handle(SageEvent.WakeDetected(thinking.recognizerGeneration))
        assertEquals(SageRuntimeState.THINKING_DEEP, coordinator.snapshot().state)
        assertTrue(effects.contains(SageEffect.SpeakTransient("I'm thinking")))
    }

    @Test
    fun chickenTonightTriggerIsSilentAndRoutesToWorkflow() {
        val coordinator = SageTurnCoordinator()
        coordinator.handle(SageEvent.Start)
        var generation = coordinator.snapshot().recognizerGeneration
        coordinator.handle(SageEvent.WakeDetected(generation))
        val turn = coordinator.snapshot().activeTurnId
        coordinator.handle(SageEvent.WakeAcknowledgementSpoken(turn))
        generation = coordinator.snapshot().recognizerGeneration

        val effects = coordinator.handle(
            SageEvent.TranscriptFinal(turn, generation, "Do you feel like chicken tonight?")
        )

        assertTrue(effects.any { it == SageEffect.LaunchOwnerWorkflow(turn, "chicken_tonight") })
        assertTrue(effects.none { it is SageEffect.Speak })
        assertEquals(SageRuntimeState.IDLE_WAKE, coordinator.snapshot().state)
        assertEquals(SageListeningMode.WAKE_ONLY, coordinator.snapshot().listeningMode)
    }

    @Test
    fun staleRecognitionCallbackCannotExecute() {
        val coordinator = SageTurnCoordinator()
        coordinator.handle(SageEvent.Start)
        val staleGeneration = coordinator.snapshot().recognizerGeneration - 1
        val effects = coordinator.handle(SageEvent.WakeDetected(staleGeneration))
        assertTrue(effects.single() is SageEffect.IgnoreStaleCallback)
        assertEquals(SageRuntimeState.IDLE_WAKE, coordinator.snapshot().state)
    }

    @Test
    fun typedMessageWhileBusyIsQueuedAndDispatchedNext() {
        val coordinator = SageTurnCoordinator()
        coordinator.handle(SageEvent.Start)

        val firstEffects = coordinator.handle(SageEvent.TextSubmitted("Explain gravity"))
        val firstTurn = coordinator.snapshot().activeTurnId
        assertTrue(firstEffects.any { it is SageEffect.QueryDeepBrain })
        assertEquals(SageRuntimeState.THINKING_DEEP, coordinator.snapshot().state)

        val queued = coordinator.handle(SageEvent.TextSubmitted("Open YouTube"))
        assertTrue(queued.contains(SageEffect.TypedInputQueued(1)))
        assertEquals(1, coordinator.snapshot().queuedTextCount)

        coordinator.handle(SageEvent.ResponseReady(firstTurn, "Gravity answer", allowFollowUp = false))
        coordinator.handle(SageEvent.SpeechFinished(firstTurn))
        val nextEffects = coordinator.handle(SageEvent.EchoGuardElapsed(firstTurn))

        assertEquals(0, coordinator.snapshot().queuedTextCount)
        assertEquals(SageRuntimeState.THINKING_FAST, coordinator.snapshot().state)
        assertTrue(nextEffects.any { it is SageEffect.ExecuteFast && it.command == "open youtube" })
    }

    @Test
    fun staleCommandCallbackIsInvalidatedWhenThinkingStarts() {
        val coordinator = SageTurnCoordinator()
        coordinator.handle(SageEvent.Start)
        var generation = coordinator.snapshot().recognizerGeneration
        coordinator.handle(SageEvent.WakeDetected(generation))
        val turn = coordinator.snapshot().activeTurnId
        coordinator.handle(SageEvent.WakeAcknowledgementSpoken(turn))
        generation = coordinator.snapshot().recognizerGeneration
        coordinator.handle(SageEvent.TranscriptFinal(turn, generation, "why is the sky blue"))

        val stale = coordinator.handle(SageEvent.TranscriptFinal(turn, generation, "late duplicate"))
        assertTrue(stale.single() is SageEffect.IgnoreStaleCallback)
    }

    @Test
    fun deviceCommandsTakeFastPath() {
        val router = SageCommandRouter()
        assertEquals(SageRoute.FAST_DEVICE, router.route("Open YouTube").route)
        assertEquals(SageRoute.DEEP_REASONING, router.route("Why is the sky blue?").route)
    }
}
