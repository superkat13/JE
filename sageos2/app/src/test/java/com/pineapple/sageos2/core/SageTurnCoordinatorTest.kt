package com.pineapple.sageos2.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SageTurnCoordinatorTest {
    @Test fun wakeFlowSaysYesThenListensForCommand() {
        val c = SageTurnCoordinator(); c.handle(SageEvent.Start)
        val g = c.snapshot().recognizerGeneration
        val wake = c.handle(SageEvent.WakeDetected(g)); val turn = c.snapshot().activeTurnId
        assertTrue(wake.contains(SageEffect.Speak(turn, "Yes")))
        c.handle(SageEvent.WakeAcknowledgementSpoken(turn))
        assertEquals(SageRuntimeState.COMMAND_LISTENING, c.snapshot().state)
    }

    @Test fun customWakeProfileCanActivateModeWithoutCreatingAnotherSage() {
        val c = SageTurnCoordinator(); c.handle(SageEvent.Start)
        val g = c.snapshot().recognizerGeneration
        val effects = c.handle(SageEvent.WakeDetected(g, "sage_glitch", "red_queen", "Yes"))
        assertTrue(effects.contains(SageEffect.ActivateMode("sage_glitch", "red_queen")))
        assertTrue(effects.any { it is SageEffect.Speak && it.text == "Yes" })
    }

    @Test fun thinkingUsesWakeOnlyAndAcknowledgesWakeWithoutCancellingThought() {
        val c = SageTurnCoordinator(); c.handle(SageEvent.Start)
        var g = c.snapshot().recognizerGeneration; c.handle(SageEvent.WakeDetected(g)); val turn = c.snapshot().activeTurnId
        c.handle(SageEvent.WakeAcknowledgementSpoken(turn)); g = c.snapshot().recognizerGeneration
        c.handle(SageEvent.TranscriptFinal(turn, g, "explain quantum tunneling"))
        val effects = c.handle(SageEvent.WakeDetected(c.snapshot().recognizerGeneration))
        assertTrue(effects.contains(SageEffect.SpeakTransient("I'm thinking")))
    }

    @Test fun recognitionFailureRecoversIntoFollowUpInsteadOfSticking() {
        val c = SageTurnCoordinator(); c.handle(SageEvent.Start)
        val wg = c.snapshot().recognizerGeneration; c.handle(SageEvent.WakeDetected(wg)); val turn = c.snapshot().activeTurnId
        c.handle(SageEvent.WakeAcknowledgementSpoken(turn)); val cg = c.snapshot().recognizerGeneration
        val effects = c.handle(SageEvent.RecognitionFailed(turn, cg, 7))
        assertEquals(SageRuntimeState.SPEAKING, c.snapshot().state)
        assertTrue(effects.any { it is SageEffect.Speak && it.text == "I didn't catch that." })
    }

    @Test fun chickenTonightTriggerIsSilentAndRoutesToWorkflow() {
        val c = SageTurnCoordinator(); c.handle(SageEvent.Start)
        var g = c.snapshot().recognizerGeneration; c.handle(SageEvent.WakeDetected(g)); val turn = c.snapshot().activeTurnId
        c.handle(SageEvent.WakeAcknowledgementSpoken(turn)); g = c.snapshot().recognizerGeneration
        val effects = c.handle(SageEvent.TranscriptFinal(turn, g, "Do you feel like chicken tonight?"))
        assertTrue(effects.any { it == SageEffect.LaunchOwnerWorkflow(turn, "chicken_tonight") })
        assertTrue(effects.none { it is SageEffect.Speak })
    }
}
