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
}
