package com.pineapple.sageos2.speech.voicerepair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceRepairSessionManagerTest {
    private var clock = 1000L
    private val manager = VoiceRepairSessionManager { clock++ }

    @Test fun startSessionCreatesDiagnosingState() {
        val session = manager.startSession("hello")
        assertEquals(VoiceRepairState.DIAGNOSING, session.state)
        assertEquals("hello", session.testPhrase)
        assertTrue(session.steps.isNotEmpty())
    }

    @Test fun cancelMarksCancelled() {
        manager.startSession("hi")
        val cancelled = manager.cancel()
        assertNotNull(cancelled)
        assertEquals(VoiceRepairState.CANCELLED, cancelled?.state)
        assertTrue(cancelled?.cancelled == true)
    }

    @Test fun deadlineExceededMarksFailed() {
        val session = manager.startSession("hi", timeoutMs = 5)
        clock += 10
        val updated = manager.update(session)
        assertEquals(VoiceRepairState.FAILED, updated.state)
        assertEquals(VoiceRepairCause.DEADLINE_EXCEEDED, updated.cause)
    }

    @Test fun markInterruptedMarksInterrupted() {
        manager.startSession("hi")
        val interrupted = manager.markInterrupted()
        assertNotNull(interrupted)
        assertEquals(VoiceRepairState.INTERRUPTED, interrupted?.state)
        assertTrue(interrupted?.interrupted == true)
    }
}