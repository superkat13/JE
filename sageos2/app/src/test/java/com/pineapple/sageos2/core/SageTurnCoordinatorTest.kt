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
        assertEquals(TurnOrigin.VOICE_WAKE, c.snapshot().activeTurnOrigin)
        c.handle(SageEvent.WakeAcknowledgementSpoken(turn))
        assertEquals(SageRuntimeState.COMMAND_LISTENING, c.snapshot().state)
    }

    @Test fun pushToTalkSkipsWakeAcknowledgementAndStartsCommandRecognition() {
        val c = SageTurnCoordinator(); c.handle(SageEvent.Start)
        val effects = c.handle(SageEvent.PushToTalkRequested)
        assertEquals(TurnOrigin.PUSH_TO_TALK, c.snapshot().activeTurnOrigin)
        assertEquals(SageRuntimeState.COMMAND_LISTENING, c.snapshot().state)
        assertTrue(effects.any { it is SageEffect.SetListeningMode && it.mode == SageListeningMode.COMMAND })
        assertTrue(effects.none { it is SageEffect.Speak })
    }

    @Test fun typedResponseIsEmittedWithoutTtsAndClosesToWake() {
        val c = SageTurnCoordinator(); c.handle(SageEvent.Start)
        val effects = c.handle(SageEvent.TextSubmitted("why is the sky blue"))
        val turn = c.snapshot().activeTurnId
        assertTrue(effects.any { it is SageEffect.QueryDeepBrain })
        val response = c.handle(SageEvent.ResponseReady(turn, "Because scattering."))
        assertTrue(response.contains(SageEffect.EmitTextResponse(turn, "Because scattering.")))
        assertTrue(response.none { it is SageEffect.Speak })
        assertEquals(SageRuntimeState.IDLE_WAKE, c.snapshot().state)
        assertEquals(TurnOrigin.NONE, c.snapshot().activeTurnOrigin)
    }

    @Test fun customWakeProfileCanActivateModeWithoutCreatingAnotherSage() {
        val c = SageTurnCoordinator(); c.handle(SageEvent.Start)
        val effects = c.handle(SageEvent.WakeDetected(c.snapshot().recognizerGeneration, "sage_glitch", "red_queen", "Yes"))
        assertTrue(effects.contains(SageEffect.ActivateMode("sage_glitch", "red_queen")))
    }

    @Test fun thinkingUsesWakeOnlyAndAcknowledgesWakeWithoutCancellingThought() {
        val c = SageTurnCoordinator(); c.handle(SageEvent.Start)
        var g = c.snapshot().recognizerGeneration; c.handle(SageEvent.WakeDetected(g)); val turn = c.snapshot().activeTurnId
        c.handle(SageEvent.WakeAcknowledgementSpoken(turn)); g = c.snapshot().recognizerGeneration
        c.handle(SageEvent.TranscriptFinal(turn, g, "explain quantum tunneling"))
        val effects = c.handle(SageEvent.WakeDetected(c.snapshot().recognizerGeneration))
        assertTrue(effects.contains(SageEffect.SpeakTransient("I'm thinking")))
    }

    @Test fun voiceFollowUpExpiresBackToWake() {
        val c = SageTurnCoordinator(followUpWindowMs = 1234L); c.handle(SageEvent.Start)
        var g = c.snapshot().recognizerGeneration; c.handle(SageEvent.WakeDetected(g)); val turn = c.snapshot().activeTurnId
        c.handle(SageEvent.WakeAcknowledgementSpoken(turn)); g = c.snapshot().recognizerGeneration
        c.handle(SageEvent.TranscriptFinal(turn, g, "hello")); c.handle(SageEvent.ResponseReady(turn, "hi", true)); c.handle(SageEvent.SpeechFinished(turn))
        val follow = c.handle(SageEvent.EchoGuardElapsed(turn))
        assertTrue(follow.contains(SageEffect.ScheduleFollowUpExpiry(turn, 1234L)))
        assertEquals(SageRuntimeState.FOLLOW_UP_LISTENING, c.snapshot().state)
        c.handle(SageEvent.FollowUpExpired(turn))
        assertEquals(SageRuntimeState.IDLE_WAKE, c.snapshot().state)
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
