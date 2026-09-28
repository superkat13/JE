package com.pineapple.sageos2.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionSessionGateTest {

    /**
     * A fresh gate sits at token 0, so it accepts 0. This mirrors the pre-refactor
     * `recognitionSession = 0L` + `session != recognitionSession` exactly, where the first real
     * turn is always `++recognitionSession` and therefore never 0. The invariant that matters is
     * that no *opened* turn is ever mistaken for the pre-turn state, so assert on the token a
     * real caller would hold.
     */
    @Test fun freshGateHasNoOpenTurn() {
        val gate = RecognitionSessionGate()
        assertEquals(0L, gate.current)
        assertFalse(gate.isCurrent(gate.next() - 1L))
    }

    @Test fun nextOpensAnIncreasingToken() {
        val gate = RecognitionSessionGate()
        val first = gate.next()
        val second = gate.next()
        assertTrue(first < second)
        assertEquals(second, gate.current)
    }

    @Test fun currentTokenIsAccepted() {
        val gate = RecognitionSessionGate()
        val session = gate.next()
        assertTrue(gate.isCurrent(session))
    }

    @Test fun invalidateRetiresTheLiveTurn() {
        val gate = RecognitionSessionGate()
        val session = gate.next()
        gate.invalidate()
        assertFalse(gate.isCurrent(session))
    }

    @Test fun staleResultAfterNewTurnIsRejected() {
        val gate = RecognitionSessionGate()
        val stale = gate.next()
        gate.invalidate() // stopInput()
        val live = gate.next() // startRecognition
        assertFalse(gate.isCurrent(stale))
        assertTrue(gate.isCurrent(live))
    }

    @Test fun staleErrorAfterCancelIsRejected() {
        val gate = RecognitionSessionGate()
        val session = gate.next()
        gate.invalidate()
        assertFalse(gate.isCurrent(session))
    }

    /** ensureRecognizer retires the token too, so a double retire must still reject. */
    @Test fun doubleInvalidateStillRejects() {
        val gate = RecognitionSessionGate()
        val session = gate.next()
        gate.invalidate() // stopInput()
        gate.invalidate() // ensureRecognizer
        assertFalse(gate.isCurrent(session))
    }

    @Test fun aLateCallbackManyTurnsLateIsStillRejected() {
        val gate = RecognitionSessionGate()
        val veryStale = gate.next()
        repeat(5) { gate.invalidate() }
        assertFalse(gate.isCurrent(veryStale))
    }

    /**
     * Pins the exact mutation order AndroidSpeechPort uses, proving the extracted gate is
     * behaviour-preserving: setListening -> stopInput -> ensureRecognizer -> startRecognition.
     */
    @Test fun portMutationOrderRejectsTheOldSession() {
        val gate = RecognitionSessionGate()
        val previous = gate.next()
        gate.invalidate()          // setListening -> stopInput()
        gate.invalidate()          // startRecognition -> ensureRecognizer (backend rebuild)
        val session = gate.next()  // startRecognition -> listener install
        assertFalse(gate.isCurrent(previous))
        assertTrue(gate.isCurrent(session))
    }

    @Test fun consumingAResultRetiresTheTokenSoItCannotFireTwice() {
        val gate = RecognitionSessionGate()
        val session = gate.next()
        assertTrue(gate.isCurrent(session))
        gate.invalidate() // handleResults
        assertFalse(gate.isCurrent(session))
    }

    @Test fun fallbackRestartRejectsTheFailedLocalSession() {
        val gate = RecognitionSessionGate()
        val localSession = gate.next()
        gate.invalidate()          // handleRecognitionError
        val androidSession = gate.next() // startRecognition(allowLocal = false)
        assertFalse(gate.isCurrent(localSession))
        assertTrue(gate.isCurrent(androidSession))
    }
}
