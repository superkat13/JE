package com.pineapple.sageos2.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakeReconnectPolicyTest {
    @Test fun recoveryUsesBoundedBackoffAndThenStops() {
        assertEquals(750L, WakeReconnectPolicy.delayForAttempt(1))
        assertEquals(2_000L, WakeReconnectPolicy.delayForAttempt(2))
        assertEquals(5_000L, WakeReconnectPolicy.delayForAttempt(3))
        assertNull(WakeReconnectPolicy.delayForAttempt(4))
        assertNull(WakeReconnectPolicy.delayForAttempt(0))
        assertEquals(3, WakeReconnectPolicy.MAX_ATTEMPTS)
    }
}
