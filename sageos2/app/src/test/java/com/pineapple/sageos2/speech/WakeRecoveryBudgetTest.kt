package com.pineapple.sageos2.speech

import org.junit.Assert.*
import org.junit.Test

class WakeRecoveryBudgetTest {
    @Test fun shortLivedRestartsCannotResetTheRetryLimit() {
        val budget = WakeRecoveryBudget()
        for (attempt in 1..3) {
            budget.listening(attempt * 100L)
            assertNotNull(budget.failed("native death $attempt"))
            assertEquals(attempt, budget.attempts)
        }
        budget.listening(1_000L)
        assertNull(budget.failed("native death 4"))
        assertTrue(budget.paused)
        assertEquals("native death 4", budget.lastFailure)
    }

    @Test fun onlySustainedListeningRestoresTheBudget() {
        val budget = WakeRecoveryBudget()
        budget.failed("first failure")
        budget.listening(10L)
        budget.listening(30_009L)
        assertEquals(1, budget.attempts)
        budget.listening(30_010L)
        assertEquals(0, budget.attempts)
        assertEquals("first failure", budget.lastFailure)
    }

    @Test fun IntentionalStopsDoNotSpendOrResetRetries() {
        val budget = WakeRecoveryBudget()
        budget.failed("failure")
        budget.listening(0L)
        budget.interrupted()
        budget.listening(60_000L)
        assertEquals(1, budget.attempts)
        assertFalse(budget.paused)
        budget.listening(90_000L)
        assertEquals(0, budget.attempts)
    }

    @Test fun queuedHealthyStatusCannotUnpauseExhaustedRecovery() {
        val budget = WakeRecoveryBudget()
        repeat(4) { budget.failed("failure") }
        budget.listening(0L)
        budget.listening(100_000L)
        assertTrue(budget.paused)
        assertEquals(3, budget.attempts)
        assertNull(budget.failed("still failed"))
    }

    @Test fun explicitResetPreservesFailureEvidence() {
        val budget = WakeRecoveryBudget()
        repeat(4) { budget.failed("AudioRecord read failed: -6") }
        budget.reset()
        assertFalse(budget.paused)
        assertEquals(0, budget.attempts)
        assertEquals("AudioRecord read failed: -6", budget.lastFailure)
        assertEquals(750L, budget.failed("retry failed"))
    }
}
