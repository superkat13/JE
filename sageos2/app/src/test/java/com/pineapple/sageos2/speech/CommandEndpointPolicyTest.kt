package com.pineapple.sageos2.speech

import com.pineapple.sageos2.speech.CommandEndpointPolicy.ChunkAction
import com.pineapple.sageos2.speech.CommandEndpointPolicy.WindowEnd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandEndpointPolicyTest {

    // --- empty-result endpoint completion (the fix) -------------------------------

    @Test fun noEndpointKeepsReadingAudio() {
        assertEquals(
            ChunkAction.CONTINUE,
            CommandEndpointPolicy.onChunk(endpointReached = false, text = "", speechBegan = true)
        )
    }

    @Test fun endpointWithTextFinishesWithText() {
        assertEquals(
            ChunkAction.FINISH_WITH_TEXT,
            CommandEndpointPolicy.onChunk(endpointReached = true, text = "turn on the lights", speechBegan = true)
        )
    }

    /**
     * Regression guard for the observed failure: speech began, the native endpoint fired on
     * trailing silence, but the decoder exposed no text. This must finish the turn instead of
     * holding the microphone until the whole budget expires.
     */
    @Test fun endpointWithEmptyTextAfterOnsetFinishesEmpty() {
        assertEquals(
            ChunkAction.FINISH_EMPTY,
            CommandEndpointPolicy.onChunk(endpointReached = true, text = "", speechBegan = true)
        )
    }

    /**
     * The stock sherpa rule1 can fire on leading silence. Completing here would turn an
     * ordinary quiet start into an instant no-match, so this must keep reading.
     */
    @Test fun endpointWithEmptyTextBeforeOnsetKeepsReading() {
        assertEquals(
            ChunkAction.CONTINUE,
            CommandEndpointPolicy.onChunk(endpointReached = true, text = "", speechBegan = false)
        )
    }

    @Test fun whitespaceOnlyTextIsStillEmpty() {
        assertEquals(
            ChunkAction.FINISH_EMPTY,
            CommandEndpointPolicy.onChunk(endpointReached = true, text = "   ", speechBegan = true)
        )
    }

    // --- budget / cancellation ----------------------------------------------------

    @Test fun budgetIsUnchanged() {
        assertEquals(15_000L, CommandEndpointPolicy.DEFAULT_MAX_UTTERANCE_MS)
    }

    @Test fun continuesWhileInsideBudget() {
        assertTrue(CommandEndpointPolicy.shouldContinue(elapsedMs = 14_999L, stopRequested = false))
    }

    @Test fun stopsAtBudget() {
        assertFalse(CommandEndpointPolicy.shouldContinue(elapsedMs = 15_000L, stopRequested = false))
    }

    @Test fun stopRequestedEndsTheLoopEvenInsideBudget() {
        assertFalse(CommandEndpointPolicy.shouldContinue(elapsedMs = 120L, stopRequested = true))
    }

    // --- window classification ----------------------------------------------------

    @Test fun stopRequestedSuppressesLateEmission() {
        assertEquals(
            WindowEnd.SUPPRESSED,
            CommandEndpointPolicy.onWindowEnd(stopRequested = true, totalSamples = 8_000L, peakAbs = 900, finalText = "")
        )
    }

    /** SUPPRESSED must outrank a usable transcript, otherwise a stop races a late result. */
    @Test fun stopRequestedOutranksRecoveredText() {
        assertEquals(
            WindowEnd.SUPPRESSED,
            CommandEndpointPolicy.onWindowEnd(stopRequested = true, totalSamples = 8_000L, peakAbs = 900, finalText = "hello")
        )
    }

    @Test fun nonemptyTextEmitsResults() {
        assertEquals(
            WindowEnd.RESULTS,
            CommandEndpointPolicy.onWindowEnd(stopRequested = false, totalSamples = 8_000L, peakAbs = 900, finalText = "hello")
        )
    }

    @Test fun genuineNoMatchIsNoMatch() {
        assertEquals(
            WindowEnd.NO_MATCH,
            CommandEndpointPolicy.onWindowEnd(stopRequested = false, totalSamples = 8_000L, peakAbs = 900, finalText = "")
        )
    }

    @Test fun noSamplesIsAudioError() {
        assertEquals(
            WindowEnd.AUDIO_ERROR,
            CommandEndpointPolicy.onWindowEnd(stopRequested = false, totalSamples = 0L, peakAbs = 0, finalText = "")
        )
    }

    @Test fun tooQuietIsAudioError() {
        assertEquals(
            WindowEnd.AUDIO_ERROR,
            CommandEndpointPolicy.onWindowEnd(stopRequested = false, totalSamples = 8_000L, peakAbs = 31, finalText = "")
        )
    }

    @Test fun peakExactlyAtThresholdIsNotAudioError() {
        assertEquals(
            WindowEnd.NO_MATCH,
            CommandEndpointPolicy.onWindowEnd(stopRequested = false, totalSamples = 8_000L, peakAbs = 32, finalText = "")
        )
    }

    // --- onset gate ---------------------------------------------------------------

    @Test fun loudAudioTriggersOnset() {
        assertTrue(CommandEndpointPolicy.hasSpeechEnergy(subsampledAbsSum = 200L * 400L, count = 1_600))
    }

    @Test fun quietAudioDoesNotTriggerOnset() {
        assertFalse(CommandEndpointPolicy.hasSpeechEnergy(subsampledAbsSum = 179L * 400L, count = 1_600))
    }

    @Test fun zeroCountDoesNotDivideByZero() {
        assertFalse(CommandEndpointPolicy.hasSpeechEnergy(subsampledAbsSum = 0L, count = 0))
    }

    // --- failed-turn delay vs accuracy -------------------------------------------

    /**
     * Reproduces the two observed turn shapes. Both end in the same classification either way;
     * only the wall-clock cost differs. This is the regression that motivated the fix.
     *
     * This omits `audioMs`, so it pins the behaviour for callers that cannot supply a duration.
     * The minimum-audio guard is covered separately, below.
     */
    @Test fun emptyEndpointTurnCompletesAtEndpointNotAtBudget() {
        var elapsed = 0L
        var began = false
        var finishedAt = -1L
        val chunks = 300 // 100ms chunks => 30s of audio, well past the budget

        for (index in 0 until chunks) {
            elapsed += 100L
            if (!began && index == 10) began = true
            val endpoint = index == 40 // trailing silence at ~4.1s
            when (CommandEndpointPolicy.onChunk(endpoint, "", began)) {
                ChunkAction.CONTINUE ->
                    if (!CommandEndpointPolicy.shouldContinue(elapsed, false)) { finishedAt = elapsed; break }
                ChunkAction.FINISH_WITH_TEXT -> { finishedAt = elapsed; break }
                ChunkAction.FINISH_EMPTY -> { finishedAt = elapsed; break }
            }
        }

        assertEquals(4_100L, finishedAt)
        assertTrue(
            "empty-endpoint turn must finish well before the ${CommandEndpointPolicy.DEFAULT_MAX_UTTERANCE_MS}ms budget",
            finishedAt < CommandEndpointPolicy.DEFAULT_MAX_UTTERANCE_MS
        )
    }

    /**
     * Ending the window at the endpoint does not discard the decode: the service still runs the
     * same inputFinished + drain, so text the loop never surfaced is still recovered. Accuracy
     * is therefore unchanged, and may only improve, by finishing early.
     */
    @Test fun textRecoveredByTheTailFlushIsStillEmitted() {
        val loopText = ""
        val tailText = "play some music"
        val finalText = if (tailText.isNotEmpty()) tailText else loopText
        assertEquals(
            WindowEnd.RESULTS,
            CommandEndpointPolicy.onWindowEnd(stopRequested = false, totalSamples = 8_000L, peakAbs = 900, finalText = finalText)
        )
    }

    // --- minimum audio before an empty endpoint is honoured -------------------------

    /**
     * The three real L10_T05 empty turns, replayed against the new guard. Every one of them must
     * now keep reading instead of closing the window, because all three finished before the
     * decoder had ever produced text on this device.
     */
    @Test fun observedDeviceEmptyTurnsAreNoLongerTruncated() {
        for (audioMs in listOf(2_844L, 2_997L, 2_959L)) {
            assertEquals(
                "an empty turn that closed at ${audioMs}ms must not be allowed to close at all",
                ChunkAction.CONTINUE,
                CommandEndpointPolicy.onChunk(
                    endpointReached = true, text = "", speechBegan = true, audioMs = audioMs
                )
            )
        }
    }

    /** The one turn that did produce text ran 5,573ms, so the guard must not block it. */
    @Test fun observedDeviceSuccessfulTurnIsStillAllowedToClose() {
        assertEquals(
            ChunkAction.FINISH_EMPTY,
            CommandEndpointPolicy.onChunk(
                endpointReached = true, text = "", speechBegan = true, audioMs = 5_573L
            )
        )
    }

    @Test fun emptyEndpointIsRefusedOneSampleBelowTheMinimum() {
        assertEquals(
            ChunkAction.CONTINUE,
            CommandEndpointPolicy.onChunk(
                endpointReached = true, text = "", speechBegan = true,
                audioMs = CommandEndpointPolicy.MIN_ENDPOINT_AUDIO_MS - 1L
            )
        )
    }

    @Test fun emptyEndpointIsAllowedExactlyAtTheMinimum() {
        assertEquals(
            ChunkAction.FINISH_EMPTY,
            CommandEndpointPolicy.onChunk(
                endpointReached = true, text = "", speechBegan = true,
                audioMs = CommandEndpointPolicy.MIN_ENDPOINT_AUDIO_MS
            )
        )
    }

    /**
     * The guard must never delay a result that already exists. If the decoder has produced text,
     * holding the microphone open only accumulates trailing noise, so FINISH_WITH_TEXT stays ahead
     * of the minimum-audio check.
     */
    @Test fun realTextIsNeverHeldBackByTheMinimum() {
        assertEquals(
            ChunkAction.FINISH_WITH_TEXT,
            CommandEndpointPolicy.onChunk(
                endpointReached = true, text = "play some music", speechBegan = true, audioMs = 400L
            )
        )
    }

    /** Callers that cannot supply a duration keep the previous behaviour unchanged. */
    @Test fun omittingAudioMsKeepsPriorBehaviour() {
        assertEquals(
            ChunkAction.FINISH_EMPTY,
            CommandEndpointPolicy.onChunk(endpointReached = true, text = "", speechBegan = true)
        )
    }

    /**
     * End-to-end replay of the device failure shape: a short burst of speech, a pause long enough
     * to trip the native endpoint, then the rest of the command, then a genuine end-of-utterance
     * endpoint. Before the guard this closed empty at 2,959ms; now it must survive the pause and
     * emit the text that follows it.
     */
    @Test fun aPauseInsideACommandNoLongerEndsTheTurnEmpty() {
        var audioMs = 0L
        var speechBegan = false
        var outcome: ChunkAction? = null
        val chunks = 120 // 100ms => 12s, inside the 15s budget

        for (index in 0 until chunks) {
            audioMs += 100L
            if (!speechBegan && index == 7) speechBegan = true
            // 0.7-1.6s of speech, then a pause that trips the native trailing-silence rule, then
            // the rest of the command, then real trailing silence at the end of the utterance.
            val endpoint = index in 16..20 || index in 60..62
            val text = if (index >= 26) "pick a random cosplay video" else ""
            val action = CommandEndpointPolicy.onChunk(endpoint, text, speechBegan, audioMs)
            if (action != ChunkAction.CONTINUE) { outcome = action; break }
        }

        assertEquals(ChunkAction.FINISH_WITH_TEXT, outcome)
    }

    /**
     * The same replay with the guard absent, pinning what the device actually did: the pause ended
     * the turn empty at 2,959ms and the text that followed was never decoded.
     */
    @Test fun withoutTheGuardTheSameShapeClosesEmptyAtThePause() {
        var audioMs = 0L
        var speechBegan = false
        var finishedAt = -1L
        var outcome: ChunkAction? = null

        for (index in 0 until 120) {
            audioMs += 100L
            if (!speechBegan && index == 7) speechBegan = true
            val endpoint = index in 16..20 || index in 60..62
            val text = if (index >= 26) "pick a random cosplay video" else ""
            // No audioMs argument: this is the pre-guard behaviour.
            val action = CommandEndpointPolicy.onChunk(endpoint, text, speechBegan)
            if (action != ChunkAction.CONTINUE) { finishedAt = audioMs; outcome = action; break }
        }

        assertEquals(ChunkAction.FINISH_EMPTY, outcome)
        // First endpoint chunk: audioMs is incremented before evaluation, so index 16 is 1,700ms.
        assertEquals(1_700L, finishedAt)
    }
}
