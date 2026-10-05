package com.pineapple.sageos2.speech

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class CommandSpeechTurnOwnershipTest {
    @Test fun concurrentStartsAdmitExactlyOneOwner() {
        val turns = CommandSpeechTurnOwnership()
        val pool = Executors.newFixedThreadPool(8)
        try {
            repeat(100) {
                turns.clear()
                val ready = CountDownLatch(8)
                val start = CountDownLatch(1)
                val callbacks = List(8) { Any() }
                val results = callbacks.map { callback ->
                    pool.submit<Boolean> {
                        ready.countDown()
                        check(start.await(5, TimeUnit.SECONDS))
                        turns.begin(callback)
                    }
                }
                assertTrue(ready.await(5, TimeUnit.SECONDS))
                start.countDown()
                val winners = results.map { it.get(5, TimeUnit.SECONDS) }
                assertEquals(1, winners.count { it })
                assertSame(callbacks[winners.indexOf(true)], turns.live)
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun equalButDistinctCallbackCannotRetireOwner() {
        data class Callback(val id: Int)
        val turns = CommandSpeechTurnOwnership()
        val owner = Callback(1)
        val impostor = Callback(1)
        assertTrue(turns.begin(owner))
        assertFalse(turns.owns(impostor))
        assertFalse(turns.retire(impostor))
        assertSame(owner, turns.live)
        assertTrue(turns.retire(owner))
        assertNull(turns.live)
    }
}
