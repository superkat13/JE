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
        assertEquals(SageListeningMode.COMMAND, c.snapshot().listeningMode)
    }

    @Test fun thinkingUsesWakeOnlyAndAcknowledgesWakeWithoutCancellingThought() {
        val c = SageTurnCoordinator(); c.handle(SageEvent.Start)
        var g = c.snapshot().recognizerGeneration; c.handle(SageEvent.WakeDetected(g)); val turn = c.snapshot().activeTurnId
        c.handle(SageEvent.WakeAcknowledgementSpoken(turn)); g = c.snapshot().recognizerGeneration
        c.handle(SageEvent.TranscriptFinal(turn, g, "explain quantum tunneling"))
        assertEquals(SageRuntimeState.THINKING_DEEP, c.snapshot().state)
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
        c.handle(SageEvent.SpeechFinished(turn)); c.handle(SageEvent.EchoGuardElapsed(turn))
        assertEquals(SageRuntimeState.FOLLOW_UP_LISTENING, c.snapshot().state)
    }

    @Test fun chickenTonightTriggerIsSilentAndRoutesToWorkflow() {
        val c = SageTurnCoordinator(); c.handle(SageEvent.Start)
        var g = c.snapshot().recognizerGeneration; c.handle(SageEvent.WakeDetected(g)); val turn = c.snapshot().activeTurnId
        c.handle(SageEvent.WakeAcknowledgementSpoken(turn)); g = c.snapshot().recognizerGeneration
        val effects = c.handle(SageEvent.TranscriptFinal(turn, g, "Do you feel like chicken tonight?"))
        assertTrue(effects.any { it == SageEffect.LaunchOwnerWorkflow(turn, "chicken_tonight") })
        assertTrue(effects.none { it is SageEffect.Speak })
    }

    @Test fun staleRecognitionCallbackCannotExecute() {
        val c = SageTurnCoordinator(); c.handle(SageEvent.Start)
        val effects = c.handle(SageEvent.WakeDetected(c.snapshot().recognizerGeneration - 1))
        assertTrue(effects.single() is SageEffect.IgnoreStaleCallback)
    }

    @Test fun typedMessageWhileBusyIsQueuedAndDispatchedNext() {
        val c = SageTurnCoordinator(); c.handle(SageEvent.Start)
        c.handle(SageEvent.TextSubmitted("Explain gravity")); val first = c.snapshot().activeTurnId
        assertEquals(SageRuntimeState.THINKING_DEEP, c.snapshot().state)
        assertTrue(c.handle(SageEvent.TextSubmitted("Open YouTube")).contains(SageEffect.TypedInputQueued(1)))
        c.handle(SageEvent.ResponseReady(first, "Gravity answer", false)); c.handle(SageEvent.SpeechFinished(first))
        val next = c.handle(SageEvent.EchoGuardElapsed(first))
        assertEquals(SageRuntimeState.THINKING_FAST, c.snapshot().state)
        assertTrue(next.any { it is SageEffect.ExecuteFast && it.command == "open youtube" })
    }
}
