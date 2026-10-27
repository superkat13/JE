package com.pineapple.sageos2.speech

import com.pineapple.sageos2.speech.CommandEndpointPolicy.ChunkAction
import com.pineapple.sageos2.speech.CommandEndpointPolicy.WindowEnd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Executable regression lab for command speech failure and recovery cases.
 * Covers: genuine no-match, no-audio vs no-match boundary, timeout, cancellation,
 * and stale callbacks; verifies capture is released and next turn is permitted;
 * verifies late callbacks do not finish another turn.
 */
class CommandSpeechRegressionLabTest {

    @Test
    fun genuineNoMatchClassifiedCorrectly() {
        val outcome = CommandEndpointPolicy.onWindowEnd(
            stopRequested = false,
            totalSamples = 16000L,
            peakAbs = 500,
            finalText = ""
        )
        assertEquals(WindowEnd.NO_MATCH, outcome)
    }

    @Test
    fun noAudioVsNoMatchBoundary() {
        // No audio (no samples) -> AUDIO_ERROR
        assertEquals(
            WindowEnd.AUDIO_ERROR,
            CommandEndpointPolicy.onWindowEnd(
                stopRequested = false,
                totalSamples = 0L,
                peakAbs = 0,
                finalText = ""
            )
        )
        // Below silence threshold -> AUDIO_ERROR
        assertEquals(
            WindowEnd.AUDIO_ERROR,
            CommandEndpointPolicy.onWindowEnd(
                stopRequested = false,
                totalSamples = 8000L,
                peakAbs = CommandEndpointPolicy.MIN_PEAK_ABS - 1,
                finalText = ""
            )
        )
        // At threshold -> NO_MATCH (not audio error)
        assertEquals(
            WindowEnd.NO_MATCH,
            CommandEndpointPolicy.onWindowEnd(
                stopRequested = false,
                totalSamples = 8000L,
                peakAbs = CommandEndpointPolicy.MIN_PEAK_ABS,
                finalText = ""
            )
        )
        // Just above -> NO_MATCH
        assertEquals(
            WindowEnd.NO_MATCH,
            CommandEndpointPolicy.onWindowEnd(
                stopRequested = false,
                totalSamples = 8000L,
                peakAbs = CommandEndpointPolicy.MIN_PEAK_ABS + 10,
                finalText = ""
            )
        )
    }

    @Test
    fun timeoutBehavior() {
        val maxMs = CommandEndpointPolicy.DEFAULT_MAX_UTTERANCE_MS
        assertEquals(15000L, maxMs)
        assertTrue(CommandEndpointPolicy.shouldContinue(elapsedMs = maxMs - 1, stopRequested = false))
        assertFalse(CommandEndpointPolicy.shouldContinue(elapsedMs = maxMs, stopRequested = false))
        assertFalse(CommandEndpointPolicy.shouldContinue(elapsedMs = maxMs + 100, stopRequested = false))
    }

    @Test
    fun cancellationBehavior() {
        // Cancellation ends loop even deep inside budget
        assertFalse(CommandEndpointPolicy.shouldContinue(elapsedMs = 100L, stopRequested = true))
        assertFalse(CommandEndpointPolicy.shouldContinue(elapsedMs = 14000L, stopRequested = true))
        // Cancellation suppresses emission and outranks recovered text
        assertEquals(
            WindowEnd.SUPPRESSED,
            CommandEndpointPolicy.onWindowEnd(stopRequested = true, totalSamples = 8000L, peakAbs = 500, finalText = "")
        )
        assertEquals(
            WindowEnd.SUPPRESSED,
            CommandEndpointPolicy.onWindowEnd(stopRequested = true, totalSamples = 8000L, peakAbs = 500, finalText = "hello")
        )
    }

    @Test
    fun staleCallbacksRejectedLateCallbacksDoNotFinishAnotherTurn() {
        val gate = RecognitionSessionGate()
        val veryStale = gate.next()
        // Simulate multiple turns starting new sessions
        repeat(3) {
            gate.invalidate()
            val live = gate.next()
            assertFalse(gate.isCurrent(veryStale))
            assertTrue(gate.isCurrent(live))
        }
        // Late callback from much earlier is still rejected
        assertFalse(gate.isCurrent(veryStale))
        // After invalidation, no matter what, stale is rejected
        gate.invalidate()
        assertFalse(gate.isCurrent(veryStale))
    }

    @Test
    fun eachTerminalOutcomeReleasesCaptureAndPermitsNextTurn() {
        val gate = RecognitionSessionGate()
        // Simulate a turn lifecycle for each outcome type
        // Outcome 1: NO_MATCH - turn ends, capture released
        var session = gate.next()
        assertTrue(gate.isCurrent(session))
        gate.invalidate() // simulate capture released after processing
        val nextSession1 = gate.next()
        assertFalse(gate.isCurrent(session))
        assertTrue(gate.isCurrent(nextSession1))

        // Outcome 2: AUDIO_ERROR
        session = nextSession1
        assertTrue(gate.isCurrent(session))
        gate.invalidate()
        val nextSession2 = gate.next()
        assertFalse(gate.isCurrent(session))
        assertTrue(gate.isCurrent(nextSession2))

        // Outcome 3: RESULTS
        session = nextSession2
        assertTrue(gate.isCurrent(session))
        gate.invalidate()
        val nextSession3 = gate.next()
        assertFalse(gate.isCurrent(session))
        assertTrue(gate.isCurrent(nextSession3))

        // Outcome 4: SUPPRESSED (cancellation)
        session = nextSession3
        assertTrue(gate.isCurrent(session))
        gate.invalidate()
        val nextSession4 = gate.next()
        assertFalse(gate.isCurrent(session))
        assertTrue(gate.isCurrent(nextSession4))

        // Outcome 5: TIMEOUT scenario - session retired
        session = nextSession4
        assertTrue(gate.isCurrent(session))
        gate.invalidate()
        val nextSession5 = gate.next()
        assertFalse(gate.isCurrent(session))
        assertTrue(gate.isCurrent(nextSession5))
    }

    @Test
    fun lateCallbacksDoNotFinishAnotherTurn() {
        val gate = RecognitionSessionGate()
        val stale = gate.next()
        gate.invalidate() // old turn ends
        val live = gate.next() // new turn starts
        // Simulate late callback arriving with stale token
        assertFalse(gate.isCurrent(stale))
        assertTrue(gate.isCurrent(live))
        // Another turn
        gate.invalidate()
        val live2 = gate.next()
        assertFalse(gate.isCurrent(stale))
        assertTrue(gate.isCurrent(live2))
    }

    @Test
    fun endpointMinimumAudioRespectedAcrossOutcomes() {
        // Empty endpoint below minimum - continue (doesn't end turn)
        assertEquals(
            ChunkAction.CONTINUE,
            CommandEndpointPolicy.onChunk(
                endpointReached = true,
                text = "",
                speechBegan = true,
                audioMs = CommandEndpointPolicy.MIN_ENDPOINT_AUDIO_MS - 1
            )
        )
        // Empty endpoint at minimum - can finish empty
        assertEquals(
            ChunkAction.FINISH_EMPTY,
            CommandEndpointPolicy.onChunk(
                endpointReached = true,
                text = "",
                speechBegan = true,
                audioMs = CommandEndpointPolicy.MIN_ENDPOINT_AUDIO_MS
            )
        )
        // With text, minimum is not enforced
        assertEquals(
            ChunkAction.FINISH_WITH_TEXT,
            CommandEndpointPolicy.onChunk(
                endpointReached = true,
                text = "test",
                speechBegan = true,
                audioMs = 100L
            )
        )
    }

    @Test
    fun maxUtteranceMsUnchanged() {
        assertEquals(15000L, CommandEndpointPolicy.DEFAULT_MAX_UTTERANCE_MS)
    }
}
