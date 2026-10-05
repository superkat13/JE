package com.pineapple.sageos2.speech

import com.pineapple.sageos2.speech.CommandEndpointPolicy.ChunkAction
import com.pineapple.sageos2.speech.CommandEndpointPolicy.WindowEnd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Executable regression lab for command-speech failure and recovery, against build 217.
 *
 * Covers every terminal outcome of a command turn: genuine no-match, the no-audio versus no-match
 * boundary, the utterance budget, cancellation, and stale platform callbacks. For each outcome the
 * lab asserts the two properties the runtime depends on and that nothing else guarantees:
 * capture is released, and the next turn is permitted. It also asserts that a late callback
 * belonging to a retired turn can neither finish nor kill a live turn.
 *
 * What this lab cannot cover is stated rather than implied: microphone hardware, sherpa decode
 * quality, endpoint rule timing and the Android binder itself are device behaviour and are not
 * exercised here. The boundary this lab does own is the decision each turn's terminal outcome
 * makes, which is the part that silently strands a turn when it is wrong.
 */
class CommandSpeechRegressionLabTest {

    /**
     * Mirrors the service's callback sequence for one turn: onStartListening opens ownership and
     * the worker opens the microphone, the turn reaches a terminal outcome and releases both, and
     * the platform's stop and cancel arrive afterwards. Capture is represented by [capturing],
     * which only the owner of the live turn may release, matching stopMicrophone()'s reach in
     * SageSherpaRecognitionService.
     */
    private class TurnHarness {
        val turns = CommandSpeechTurnOwnership()
        val emitted = mutableListOf<String>()

        var capturing: String? = null
            private set
        var stopRequested: Boolean = false
            private set

        /** onStartListening, plus the worker opening the microphone. */
        fun startListening(turn: String): Boolean {
            if (!turns.begin(turn)) return false
            stopRequested = false
            capturing = turn
            return true
        }

        /**
         * The turn's own terminal outcome. SUPPRESSED retires without emitting, which is what the
         * service does: an abandoned turn must not deliver a late transcript or a late error, but
         * it still has to release capture and ownership or the next turn cannot start.
         */
        fun terminalOutcome(turn: String, outcome: WindowEnd) {
            if (!turns.owns(turn)) return
            stopRequested = true
            capturing = null
            turns.retire(turn)
            if (outcome != WindowEnd.SUPPRESSED) emitted += outcome.name
        }

        /** onStopListening. False means the callback belongs to a retired turn and was ignored. */
        fun onStopListening(turn: String): Boolean {
            if (!turns.requestStop(turn)) return false
            stopRequested = true
            capturing = null
            return true
        }

        /** onCancel. False means the callback belongs to a retired turn and was ignored. */
        fun onCancel(turn: String): Boolean {
            if (!turns.retire(turn)) return false
            stopRequested = true
            capturing = null
            return true
        }
    }

    // --- classification of a finished window --------------------------------------

    /**
     * A genuine no-match: the microphone worked, the window closed on a real endpoint, the
     * amplitude is above the silence floor, and the decoder produced nothing. This must not be
     * reported as a microphone fault, because the fault path marks the recognizer unhealthy and
     * makes the owner deaf for the cooldown.
     */
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

    /**
     * The no-audio versus no-match boundary. Below the silence floor there is no usable PCM energy
     * at all, which is a microphone fault; at or above it, silence is still a real window that
     * simply matched nothing. Only the first marks the recognizer unhealthy.
     */
    @Test
    fun noAudioVsNoMatchBoundary() {
        // No samples at all -> audio fault.
        assertEquals(
            WindowEnd.AUDIO_ERROR,
            CommandEndpointPolicy.onWindowEnd(
                stopRequested = false,
                totalSamples = 0L,
                peakAbs = 0,
                finalText = ""
            )
        )
        // One sample below the floor -> still an audio fault.
        assertEquals(
            WindowEnd.AUDIO_ERROR,
            CommandEndpointPolicy.onWindowEnd(
                stopRequested = false,
                totalSamples = 8000L,
                peakAbs = CommandEndpointPolicy.MIN_PEAK_ABS - 1,
                finalText = ""
            )
        )
        // Exactly at the floor -> the boundary belongs to no-match, not to the fault path.
        assertEquals(
            WindowEnd.NO_MATCH,
            CommandEndpointPolicy.onWindowEnd(
                stopRequested = false,
                totalSamples = 8000L,
                peakAbs = CommandEndpointPolicy.MIN_PEAK_ABS,
                finalText = ""
            )
        )
        // Above the floor -> no-match.
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

    /** Real decoded text wins over the amplitude checks: text is never discarded. */
    @Test
    fun decodedTextIsNeverDowngradedToAnAudioFault() {
        assertEquals(
            WindowEnd.RESULTS,
            CommandEndpointPolicy.onWindowEnd(
                stopRequested = false,
                totalSamples = 0L,
                peakAbs = 0,
                finalText = "turn on the lights"
            )
        )
    }

    // --- budget and cancellation ---------------------------------------------------

    /**
     * The utterance budget is unchanged at 15,000 ms and is inclusive at the boundary: the turn
     * may read the last millisecond of the budget but not one past it.
     */
    @Test
    fun timeoutBehavior() {
        val maxMs = CommandEndpointPolicy.DEFAULT_MAX_UTTERANCE_MS
        assertEquals(15000L, maxMs)
        assertTrue(CommandEndpointPolicy.shouldContinue(elapsedMs = maxMs - 1, stopRequested = false))
        assertFalse(CommandEndpointPolicy.shouldContinue(elapsedMs = maxMs, stopRequested = false))
        assertFalse(CommandEndpointPolicy.shouldContinue(elapsedMs = maxMs + 100, stopRequested = false))
    }

    /**
     * A turn that spends the whole budget without an endpoint ends as a genuine no-match, not as a
     * fault, because the audio was real. The classification is what keeps the recognizer healthy
     * and therefore available for the next turn.
     */
    @Test
    fun budgetTimeoutWithoutTextIsAGenuineNoMatch() {
        val samplesAtBudget = CommandEndpointPolicy.DEFAULT_MAX_UTTERANCE_MS * 16 // 16 kHz
        assertEquals(
            WindowEnd.NO_MATCH,
            CommandEndpointPolicy.onWindowEnd(
                stopRequested = false,
                totalSamples = samplesAtBudget,
                peakAbs = 800,
                finalText = ""
            )
        )
    }

    /**
     * Cancellation outranks everything, including text the decoder had already produced. An
     * abandoned turn must not deliver a late transcript or a late error.
     */
    @Test
    fun cancellationBehavior() {
        assertFalse(CommandEndpointPolicy.shouldContinue(elapsedMs = 100L, stopRequested = true))
        assertFalse(CommandEndpointPolicy.shouldContinue(elapsedMs = 14000L, stopRequested = true))
        assertEquals(
            WindowEnd.SUPPRESSED,
            CommandEndpointPolicy.onWindowEnd(stopRequested = true, totalSamples = 8000L, peakAbs = 500, finalText = "")
        )
        assertEquals(
            WindowEnd.SUPPRESSED,
            CommandEndpointPolicy.onWindowEnd(stopRequested = true, totalSamples = 8000L, peakAbs = 500, finalText = "hello")
        )
    }

    // --- terminal outcome releases capture and permits the next turn ---------------

    /**
     * Every terminal outcome, including the suppressed one, must leave no microphone held and no
     * turn owned, so the next turn can start. A cancelled turn is the easiest one to get wrong
     * here, because it deliberately emits nothing.
     */
    @Test
    fun eachTerminalOutcomeReleasesCaptureAndPermitsNextTurn() {
        for (outcome in WindowEnd.values()) {
            val harness = TurnHarness()
            val turn = "turn-" + outcome.name
            assertTrue("$turn must start", harness.startListening(turn))
            assertEquals(turn, harness.capturing)

            harness.terminalOutcome(turn, outcome)

            assertNull("$outcome must release capture", harness.capturing)
            assertFalse("$outcome must release ownership", harness.turns.owns(turn))
            assertTrue("$outcome must permit the next turn", harness.startListening("next"))
        }
    }

    /** Only the suppressed outcome is silent; a real outcome is delivered exactly once. */
    @Test
    fun onlyCancelledTurnsAreSilent() {
        for (outcome in WindowEnd.values()) {
            val harness = TurnHarness()
            harness.startListening("turn")
            harness.terminalOutcome("turn", outcome)
            if (outcome == WindowEnd.SUPPRESSED) assertTrue("cancelled turn must be silent", harness.emitted.isEmpty())
            else assertEquals("outcome " + outcome, listOf(outcome.name), harness.emitted)
        }
    }

    /**
     * The classification a finished window produces is the terminal outcome the turn must apply.
     * Each one still has to release capture and permit the next turn, so this walks the real
     * pairing rather than the outcome enum in isolation.
     */
    @Test
    fun classifiedWindowsAllReleaseCaptureAndPermitTheNextTurn() {
        val windows = listOf(
            "genuine no-match" to CommandEndpointPolicy.onWindowEnd(false, 16000L, 500, ""),
            "no audio" to CommandEndpointPolicy.onWindowEnd(false, 0L, 0, ""),
            "quiet window" to CommandEndpointPolicy.onWindowEnd(false, 8000L, CommandEndpointPolicy.MIN_PEAK_ABS - 1, ""),
            "decoded text" to CommandEndpointPolicy.onWindowEnd(false, 16000L, 500, "turn on the lights"),
            "budget timeout" to CommandEndpointPolicy.onWindowEnd(false, 240000L, 800, ""),
            "cancelled" to CommandEndpointPolicy.onWindowEnd(true, 16000L, 800, "turn on the lights")
        )
        for ((label, outcome) in windows) {
            val harness = TurnHarness()
            harness.startListening("turn")
            harness.terminalOutcome("turn", outcome)
            assertNull("$label must release capture", harness.capturing)
            assertTrue("$label must permit the next turn", harness.startListening("next"))
        }
    }

    // --- late callbacks ------------------------------------------------------------

    /**
     * A stop belonging to a turn that already finished must not reach the turn that replaced it.
     * RecognitionService delivers per-session callbacks over a binder, so this ordering is
     * reachable: the turn ends, the next turn starts, and the platform's stop for the old session
     * arrives afterwards.
     */
    @Test
    fun lateStopFromRetiredTurnDoesNotReleaseTheLiveTurn() {
        val harness = TurnHarness()
        harness.startListening("first")
        harness.terminalOutcome("first", WindowEnd.NO_MATCH)
        harness.startListening("second")

        assertFalse("a late stop must be ignored", harness.onStopListening("first"))

        assertTrue("the live turn must keep its ownership", harness.turns.owns("second"))
        assertEquals("the live turn must keep its microphone", "second", harness.capturing)
    }

    /**
     * The worse case of the same ordering. A late cancel used to drop ownership of the live turn,
     * which made the live turn's own terminal outcome unreachable: the turn then ended with no
     * transcript, no error and no end of speech, and the owner's next request had nothing to
     * resolve against.
     */
    @Test
    fun lateCancelFromRetiredTurnDoesNotStrandTheLiveTurn() {
        val harness = TurnHarness()
        harness.startListening("first")
        harness.terminalOutcome("first", WindowEnd.RESULTS)
        harness.startListening("second")

        assertFalse("a late cancel must be ignored", harness.onCancel("first"))

        assertTrue("the live turn must keep its ownership", harness.turns.owns("second"))
        harness.terminalOutcome("second", WindowEnd.NO_MATCH)
        assertEquals(listOf("RESULTS", "NO_MATCH"), harness.emitted)
    }

    /**
     * A late callback must never finish another turn. Results, errors and end-of-speech are all
     * delivered only for the turn that is still live, so a callback that arrives after its own turn
     * retired cannot be mistaken for the current turn's outcome.
     */
    @Test
    fun lateCallbacksDoNotFinishAnotherTurn() {
        val harness = TurnHarness()
        harness.startListening("first")
        harness.terminalOutcome("first", WindowEnd.NO_MATCH)
        harness.startListening("second")

        // The retired turn's own outcome can no longer be applied, by either channel.
        harness.terminalOutcome("first", WindowEnd.RESULTS)
        assertFalse(harness.onStopListening("first"))
        assertFalse(harness.onCancel("first"))

        assertEquals(listOf("NO_MATCH"), harness.emitted)
        assertEquals("second", harness.capturing)
    }

    /** The same rejection has to hold for the client-side gate, not only the service's ownership. */
    @Test
    fun staleCallbacksRejectedLateCallbacksDoNotFinishAnotherTurn() {
        val gate = RecognitionSessionGate()
        val veryStale = gate.next()
        repeat(3) {
            gate.invalidate()
            val live = gate.next()
            assertFalse(gate.isCurrent(veryStale))
            assertTrue(gate.isCurrent(live))
        }
        assertFalse(gate.isCurrent(veryStale))
        gate.invalidate()
        assertFalse(gate.isCurrent(veryStale))
    }

    /** A callback many turns late is still rejected, and a live turn still starts. */
    @Test
    fun callbackManyTurnsLateIsStillRejected() {
        val harness = TurnHarness()
        val first = "turn-0"
        harness.startListening(first)
        harness.terminalOutcome(first, WindowEnd.NO_MATCH)
        repeat(4) { index ->
            val turn = "turn-" + (index + 1)
            assertTrue(harness.startListening(turn))
            assertFalse(harness.onCancel(first))
            assertFalse(harness.onStopListening(first))
            assertTrue(harness.turns.owns(turn))
            harness.terminalOutcome(turn, WindowEnd.NO_MATCH)
        }
        assertEquals(5, harness.emitted.size)
        assertEquals(listOf("NO_MATCH"), harness.emitted.distinct())
    }

    /** A second turn cannot start while one is still live; the service reports that as busy. */
    @Test
    fun aSecondTurnCannotStartWhileOneIsLive() {
        val harness = TurnHarness()
        assertTrue(harness.startListening("first"))
        assertFalse("a live turn must not be overwritten", harness.startListening("second"))
        assertTrue(harness.turns.owns("first"))
    }

    // --- endpoint rules that decide when a turn stops ------------------------------

    /**
     * An empty endpoint is refused until enough audio has been consumed, because closing a turn
     * early guarantees a no-match. Text is never held back.
     */
    @Test
    fun endpointMinimumAudioRespectedAcrossOutcomes() {
        assertEquals(
            ChunkAction.CONTINUE,
            CommandEndpointPolicy.onChunk(
                endpointReached = true,
                text = "",
                speechBegan = true,
                audioMs = CommandEndpointPolicy.MIN_ENDPOINT_AUDIO_MS - 1
            )
        )
        assertEquals(
            ChunkAction.FINISH_EMPTY,
            CommandEndpointPolicy.onChunk(
                endpointReached = true,
                text = "",
                speechBegan = true,
                audioMs = CommandEndpointPolicy.MIN_ENDPOINT_AUDIO_MS
            )
        )
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

    /** Pinned so a change to either constant has to be deliberate: the issue requires 15,000 ms. */
    @Test
    fun maxUtteranceMsUnchanged() {
        assertEquals(15000L, CommandEndpointPolicy.DEFAULT_MAX_UTTERANCE_MS)
    }
}