package com.pineapple.sageos2.speech

import org.junit.Assert.*
import org.junit.Test

/**
 * A cancelled turn's microphone release unblocks the worker's pending read with an error. That
 * error is an expected consequence of the cancel rather than a backend fault, so it must not open a
 * cooldown that rejects the caller's next request. The live turn still has to report it, which is
 * what separates this from swallowing real failures.
 */
class CommandSpeechFailurePolicyTest {

    @Test fun cancelledTurnFailureDoesNotPoisonTheNextRequest() {
        assertFalse(CommandSpeechFailurePolicy.shouldMarkUnhealthy(ownsTurn = true, stopRequested = true))
    }

    @Test fun supersededTurnFailureDoesNotPoisonTheNextRequest() {
        assertFalse(CommandSpeechFailurePolicy.shouldMarkUnhealthy(ownsTurn = false, stopRequested = false))
    }

    @Test fun liveTurnFailureStillMarksUnhealthy() {
        assertTrue(CommandSpeechFailurePolicy.shouldMarkUnhealthy(ownsTurn = true, stopRequested = false))
    }

    @Test fun onlyTheLiveTurnEmitsATerminalError() {
        assertTrue(CommandSpeechFailurePolicy.shouldEmitError(ownsTurn = true))
        assertFalse(CommandSpeechFailurePolicy.shouldEmitError(ownsTurn = false))
    }

    @Test fun stopAndCancelWaitForTheRetiringWorker() {
        assertTrue(
            "the retiring worker must be given time to clear its AudioRecord, " +
                "otherwise the next request is rejected as busy",
            CommandSpeechFailurePolicy.STOP_JOIN_MS > 0L
        )
        assertTrue(
            "the wait happens on the service's main thread, so it must stay well clear of an ANR",
            CommandSpeechFailurePolicy.STOP_JOIN_MS <= 500L
        )
    }
}
