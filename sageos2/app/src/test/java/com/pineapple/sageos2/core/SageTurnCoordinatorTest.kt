package com.pineapple.sageos2.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SageTurnCoordinatorTest {
    @Test
    fun wakeFlowSaysYesThenListensForCommand() {
        val coordinator = SageTurnCoordinator()
        coordinator.handle(SageEvent.Start)
        val generation = coordinator.snapshot().recognizerGeneration

        val wakeEffects = coordinator.handle(SageEvent.WakeDetected(generation))
        val turn = coordinator.snapshot().activeTurnId

        assertEquals(SageRuntimeState.ACKNOWLEDGING_WAKE, coordinator.snapshot().state)
        assertTrue(wakeEffects.contains(SageEffect.Speak(turn, "Yes")))

        val ackEffects = coordinator.handle(SageEvent.WakeAcknowledgementSpoken(turn))
        assertEquals(SageRuntimeState.COMMAND_LISTENING, coordinator.snapshot().state)
        assertTrue(ackEffects.any { it is SageEffect.StartCommandListening })
    }

    @Test
    fun wakeWhileThinkingAcknowledgesWithoutCancellingThought() {
        val coordinator = SageTurnCoordinator()
        coordinator.handle(SageEvent.Start)
        val generation = coordinator.snapshot().recognizerGeneration
        coordinator.handle(SageEvent.WakeDetected(generation))
        val turn = coordinator.snapshot().activeTurnId
        coordinator.handle(SageEvent.WakeAcknowledgementSpoken(turn))
        val commandGeneration = coordinator.snapshot().recognizerGeneration
        coordinator.handle(SageEvent.TranscriptFinal(turn, commandGeneration, "explain quantum tunneling"))

        val effects = coordinator.handle(SageEvent.WakeDetected(commandGeneration))
        assertEquals(SageRuntimeState.THINKING_DEEP, coordinator.snapshot().state)
        assertTrue(effects.contains(SageEffect.SpeakTransient("I'm thinking")))
    }

    @Test
    fun chickenTonightTriggerIsSilentAndRoutesToWorkflow() {
        val coordinator = SageTurnCoordinator()
        coordinator.handle(SageEvent.Start)
        val generation = coordinator.snapshot().recognizerGeneration
        coordinator.handle(SageEvent.WakeDetected(generation))
        val turn = coordinator.snapshot().activeTurnId
        coordinator.handle(SageEvent.WakeAcknowledgementSpoken(turn))
        val commandGeneration = coordinator.snapshot().recognizerGeneration

        val effects = coordinator.handle(
            SageEvent.TranscriptFinal(turn, commandGeneration, "Do you feel like chicken tonight?")
        )

        assertTrue(effects.any { it == SageEffect.LaunchOwnerWorkflow(turn, "chicken_tonight") })
        assertTrue(effects.none { it is SageEffect.Speak })
        assertEquals(SageRuntimeState.IDLE_WAKE, coordinator.snapshot().state)
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
    fun deviceCommandsTakeFastPath() {
        val router = SageCommandRouter()
        assertEquals(SageRoute.FAST_DEVICE, router.route("Open YouTube").route)
        assertEquals(SageRoute.DEEP_REASONING, router.route("Why is the sky blue?").route)
    }
}
