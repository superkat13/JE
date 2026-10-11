package com.pineapple.sageos2.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SageTurnCoordinatorTest {
    @Test fun captureProvenanceSeparatesManualTalkWakeAndFollowUp() {
        val manual = SageTurnCoordinator()
        manual.handle(SageEvent.Start)
        val manualEffects = manual.handle(SageEvent.PushToTalkRequested)
        val manualCapture = manualEffects.filterIsInstance<SageEffect.SetListeningMode>().single()
        assertEquals(SageListeningMode.COMMAND, manualCapture.mode)
        assertFalse("pressing Talk has no wake audio tail", manualCapture.wakeTailPresent)

        val wake = SageTurnCoordinator()
        wake.handle(SageEvent.Start)
        wake.handle(SageEvent.WakeDetected(wake.snapshot().recognizerGeneration))
        val id = wake.snapshot().activeTurnId
        val cmd = wake.handle(SageEvent.WakeAcknowledgementSpoken(id))
            .filterIsInstance<SageEffect.SetListeningMode>().single()
        assertEquals(SageListeningMode.COMMAND, cmd.mode)
        assertTrue("a wake-triggered command keeps existing tail policy", cmd.wakeTailPresent)

        val generation = wake.snapshot().recognizerGeneration
        wake.handle(SageEvent.TranscriptFinal(id, generation, "hello"))
        wake.handle(SageEvent.ResponseReady(id, "hi", true))
        wake.handle(SageEvent.SpeechFinished(id))
        val follow = wake.handle(SageEvent.EchoGuardElapsed(id))
            .filterIsInstance<SageEffect.SetListeningMode>().single()
        assertEquals(SageListeningMode.FOLLOW_UP, follow.mode)
        assertFalse("a follow-up is not a wake-phrase handoff", follow.wakeTailPresent)
    }

    @Test fun recognizerReadyIsScopedToCurrentTurnAndGeneration() {
        val c = SageTurnCoordinator()
        c.handle(SageEvent.Start)
        c.handle(SageEvent.PushToTalkRequested)
        val s1 = c.snapshot()
        assertFalse(s1.commandRecognizerReady)
        c.handle(SageEvent.CommandRecognizerReady(s1.activeTurnId, s1.recognizerGeneration))
        assertTrue(c.snapshot().commandRecognizerReady)
        c.handle(SageEvent.CommandRecognizerReady(s1.activeTurnId, s1.recognizerGeneration))
        assertTrue(c.snapshot().commandRecognizerReady)
        c.handle(SageEvent.RecognitionFailed(s1.activeTurnId, s1.recognizerGeneration, 7))
        assertFalse("speech is no longer captured after failure", c.snapshot().commandRecognizerReady)
        c.handle(SageEvent.SpeechFinished(s1.activeTurnId))
        c.handle(SageEvent.EchoGuardElapsed(s1.activeTurnId))
        c.handle(SageEvent.PushToTalkRequested)
        val s2 = c.snapshot()
        assertFalse(s2.commandRecognizerReady)
        c.handle(SageEvent.CommandRecognizerReady(s1.activeTurnId, s1.recognizerGeneration))
        assertFalse("stale readiness must not activate a new capture", c.snapshot().commandRecognizerReady)
        c.handle(SageEvent.CommandRecognizerReady(s2.activeTurnId, s2.recognizerGeneration))
        assertTrue(c.snapshot().commandRecognizerReady)
    }

    @Test fun wakeFlowSaysYesThenListensForCommand() {
        val c = SageTurnCoordinator(); c.handle(SageEvent.Start)
        val g = c.snapshot().recognizerGeneration
        val wake = c.handle(SageEvent.WakeDetected(g)); val turn = c.snapshot().activeTurnId
        assertTrue(wake.contains(SageEffect.Speak(turn, "Yes")))
        assertEquals(TurnOrigin.VOICE_WAKE, c.snapshot().activeTurnOrigin)
        c.handle(SageEvent.WakeAcknowledgementSpoken(turn))
        assertEquals(SageRuntimeState.COMMAND_LISTENING, c.snapshot().state)
    }

    @Test fun savedWakeCommandRunsAfterAcknowledgementWithoutOpeningCommandRecognizer() {
        val c = SageTurnCoordinator()
        c.handle(SageEvent.Start)
        val wake = c.handle(
            SageEvent.WakeDetected(
                c.snapshot().recognizerGeneration,
                profileId = "saved",
                acknowledgement = "Got it",
                command = "open firefox"
            )
        )
        val turn = c.snapshot().activeTurnId
        assertTrue(wake.contains(SageEffect.Speak(turn, "Got it")))

        val routed = c.handle(SageEvent.WakeAcknowledgementSpoken(turn))

        assertEquals(SageRuntimeState.THINKING_FAST, c.snapshot().state)
        assertTrue(routed.contains(SageEffect.RecordOwnerInput(turn, "open firefox", TurnOrigin.VOICE_WAKE)))
        assertTrue(routed.contains(SageEffect.ExecuteFast(turn, "open firefox")))
        assertTrue(routed.none { it is SageEffect.SetListeningMode && it.mode == SageListeningMode.COMMAND })
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

    @Test fun recoveredTaskStartsDedicatedSilentDeepTurnAndReturnsText() {
        val c = SageTurnCoordinator()
        c.handle(SageEvent.Start)

        val effects = c.handle(
            SageEvent.RecoverTask(
                recoveredTaskId = "runtime:turn:9",
                ownerPrompt = "finish the printer repair",
                priorPhase = "capability",
                lastAction = "root.restart_service",
                lastActionSignature = "root.restart_service|service=print",
                lastActionSuccess = null,
                completedToolCalls = 1,
                recoveryDepth = 1
            )
        )
        val turn = c.snapshot().activeTurnId

        assertEquals(TurnOrigin.RECOVERY, c.snapshot().activeTurnOrigin)
        assertEquals(SageRuntimeState.THINKING_DEEP, c.snapshot().state)
        assertTrue(effects.any { it is SageEffect.QueryRecoveredBrain && it.turnId == turn })
        assertTrue(effects.none { it is SageEffect.RecordOwnerInput })
        assertTrue(effects.none { it is SageEffect.Speak })

        val response = c.handle(SageEvent.ResponseReady(turn, "Recovered and verified.", false))
        assertTrue(response.contains(SageEffect.EmitTextResponse(turn, "Recovered and verified.")))
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

    @Test fun microphoneHandoffFailureExplainsItWasNeverReady() {
        val c = SageTurnCoordinator()
        c.handle(SageEvent.Start)
        c.handle(SageEvent.PushToTalkRequested)
        val turn = c.snapshot().activeTurnId
        val generation = c.snapshot().recognizerGeneration
        assertFalse(c.snapshot().commandRecognizerReady)
        val effects = c.handle(SageEvent.RecognitionFailed(turn, generation, 3))
        assertTrue(effects.contains(SageEffect.Speak(
            turn, "There was a microphone problem, so I couldn't hear you."
        )))
        assertEquals(SageRuntimeState.SPEAKING, c.snapshot().state)
        assertFalse(c.snapshot().commandRecognizerReady)
        c.handle(SageEvent.SpeechFinished(turn))
        c.handle(SageEvent.EchoGuardElapsed(turn))
        assertEquals(SageListeningMode.WAKE_ONLY, c.snapshot().listeningMode)
    }

    @Test fun recognitionFailureSpeaksOnceThenClosesToWakeWithoutListeningAgain() {
        val c = SageTurnCoordinator(); c.handle(SageEvent.Start)
        c.handle(SageEvent.PushToTalkRequested)
        val turn = c.snapshot().activeTurnId
        c.handle(SageEvent.TranscriptFinal(turn, c.snapshot().recognizerGeneration, "hello"))
        c.handle(SageEvent.ResponseReady(turn, "Hi", true))
        c.handle(SageEvent.SpeechFinished(turn))
        c.handle(SageEvent.EchoGuardElapsed(turn))
        assertEquals(SageRuntimeState.FOLLOW_UP_LISTENING, c.snapshot().state)
        val failedGeneration = c.snapshot().recognizerGeneration

        val failure = c.handle(SageEvent.RecognitionFailed(turn, failedGeneration, 7))
        assertEquals(1, failure.filterIsInstance<SageEffect.Speak>().size)
        assertTrue(failure.contains(SageEffect.Speak(turn, "I didn't catch that.")))

        c.handle(SageEvent.SpeechFinished(turn))
        val afterGuard = c.handle(SageEvent.EchoGuardElapsed(turn))
        assertEquals(SageRuntimeState.IDLE_WAKE, c.snapshot().state)
        assertEquals(SageListeningMode.WAKE_ONLY, c.snapshot().listeningMode)
        assertTrue(afterGuard.any { it is SageEffect.SetListeningMode && it.mode == SageListeningMode.WAKE_ONLY })
        assertTrue(afterGuard.none { it is SageEffect.ScheduleFollowUpExpiry })

        val repeatedCallback = c.handle(SageEvent.RecognitionFailed(turn, failedGeneration, 7))
        assertTrue(repeatedCallback.none { it is SageEffect.Speak })
    }

    @Test fun chickenTonightTriggerIsSilentAndRoutesToWorkflow() {
        val c = SageTurnCoordinator(); c.handle(SageEvent.Start)
        var g = c.snapshot().recognizerGeneration; c.handle(SageEvent.WakeDetected(g)); val turn = c.snapshot().activeTurnId
        c.handle(SageEvent.WakeAcknowledgementSpoken(turn)); g = c.snapshot().recognizerGeneration
        val effects = c.handle(SageEvent.TranscriptFinal(turn, g, "Do you feel like chicken tonight?"))
        assertTrue(effects.any { it == SageEffect.LaunchOwnerWorkflow(turn, "chicken_tonight") })
        assertTrue(effects.none { it is SageEffect.Speak })
    }

    @Test fun acceptedMessageAppearsImmediatelyEvenWhileSageIsThinking() {
        val c = SageTurnCoordinator(); c.handle(SageEvent.Start)
        c.handle(SageEvent.TextSubmitted("first thought"))
        val firstTurn = c.snapshot().activeTurnId

        val queued = c.handle(SageEvent.TextSubmitted("second thought"))
        val recorded = queued.filterIsInstance<SageEffect.RecordOwnerInput>().single()
        assertEquals("second thought", recorded.text)
        assertEquals(1, c.snapshot().queuedTextCount)

        val next = c.handle(SageEvent.ResponseReady(firstTurn, "first answer"))
        assertTrue(next.any { it is SageEffect.QueryDeepBrain && it.prompt == "second thought" })
        assertTrue(next.none { it is SageEffect.RecordOwnerInput })
    }

    @Test fun duplicateRecognizerFinalCannotCreateADuplicateBrainTurn() {
        val c = SageTurnCoordinator(); c.handle(SageEvent.Start)
        c.handle(SageEvent.PushToTalkRequested)
        val turn = c.snapshot().activeTurnId
        val generation = c.snapshot().recognizerGeneration

        val accepted = c.handle(SageEvent.TranscriptFinal(turn, generation, "one request"))
        val duplicate = c.handle(SageEvent.TranscriptFinal(turn, generation, "one request"))

        assertEquals(1, accepted.filterIsInstance<SageEffect.QueryDeepBrain>().size)
        assertTrue(duplicate.none { it is SageEffect.QueryDeepBrain })
        assertTrue(duplicate.single() is SageEffect.IgnoreStaleCallback)
    }

    @Test fun staleFinalFromClosedTurnCannotReopenOrReplaceNewListener() {
        val c = SageTurnCoordinator(); c.handle(SageEvent.Start)
        c.handle(SageEvent.PushToTalkRequested)
        val oldTurn = c.snapshot().activeTurnId
        val oldGeneration = c.snapshot().recognizerGeneration
        c.handle(SageEvent.RecognitionFailed(oldTurn, oldGeneration, 7))
        c.handle(SageEvent.SpeechFinished(oldTurn))
        c.handle(SageEvent.EchoGuardElapsed(oldTurn))
        c.handle(SageEvent.PushToTalkRequested)
        val current = c.snapshot()

        val stale = c.handle(SageEvent.TranscriptFinal(oldTurn, oldGeneration, "late callback"))

        assertEquals(SageRuntimeState.COMMAND_LISTENING, c.snapshot().state)
        assertEquals(current.activeTurnId, c.snapshot().activeTurnId)
        assertTrue(stale.none { it is SageEffect.QueryDeepBrain })
    }

    @Test fun duplicateTtsCompletionAfterRecognitionMissCannotRestartListening() {
        val c = SageTurnCoordinator(); c.handle(SageEvent.Start)
        c.handle(SageEvent.PushToTalkRequested)
        val turn = c.snapshot().activeTurnId
        c.handle(SageEvent.RecognitionFailed(turn, c.snapshot().recognizerGeneration, 7))

        val first = c.handle(SageEvent.SpeechFinished(turn))
        val duplicate = c.handle(SageEvent.SpeechFinished(turn))

        assertTrue(first.single() is SageEffect.StartEchoGuard)
        assertTrue(duplicate.none { it is SageEffect.SetListeningMode })
        val afterGuard = c.handle(SageEvent.EchoGuardElapsed(turn))
        assertTrue(afterGuard.none { it is SageEffect.ScheduleFollowUpExpiry })
        assertEquals(SageListeningMode.WAKE_ONLY, c.snapshot().listeningMode)
    }

    @Test fun wakeDuringBrainWorkOnlyAcknowledgesAndNeverCancelsOrDuplicatesTurn() {
        val c = SageTurnCoordinator(); c.handle(SageEvent.Start)
        c.handle(SageEvent.TextSubmitted("slow thought"))
        val active = c.snapshot().activeTurnId

        val wake = c.handle(SageEvent.WakeDetected(c.snapshot().recognizerGeneration))

        assertEquals(listOf(SageEffect.SpeakTransient("I'm thinking")), wake)
        assertEquals(active, c.snapshot().activeTurnId)
        assertEquals(SageRuntimeState.THINKING_DEEP, c.snapshot().state)
        assertTrue(wake.none { it is SageEffect.CancelTurn || it is SageEffect.QueryDeepBrain })
    }
}
