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

    @Test fun lateCallbackCannotReviveCancelledSession() {
        val old = manager.startSession("hi")
        manager.cancel()
        assertEquals(VoiceRepairState.CANCELLED, manager.update(old).state)
    }

    @Test fun oldSessionCannotOverwriteSuccessor() {
        val old = manager.startSession("old")
        manager.cancel()
        val next = manager.startSession("new")
        assertEquals(next.id, manager.update(old).id)
    }

    @Test(expected = IllegalStateException::class)
    fun duplicateStartDoesNotReplaceLiveOwner() {
        manager.startSession("first")
        manager.startSession("second")
    }

    @Test fun deadlineIsEnforcedOnReadWithoutCallback() {
        manager.startSession("hello", timeoutMs = 5)
        clock += 10
        assertEquals(VoiceRepairCause.DEADLINE_EXCEEDED, manager.current()?.cause)
    }

    @Test(expected = IllegalArgumentException::class)
    fun successWithoutRetestIsRejected() {
        val old = manager.startSession("hello")
        manager.update(old.copy(state = VoiceRepairState.SUCCESS))
    }

    @Test fun silentAudioDoesNotAuthorizeRecognizerReset() {
        org.junit.Assert.assertFalse(VoiceRepairPolicy.canApplyRepair(VoiceRepairCause.NO_SPEECH))
        org.junit.Assert.assertFalse(VoiceRepairPolicy.canApplyRepair(VoiceRepairCause.WRONG_TRANSCRIPT))
        assertTrue(VoiceRepairPolicy.canApplyRepair(VoiceRepairCause.RECOGNIZER_LIFECYCLE_FAILURE))
    }

    @Test(expected = IllegalArgumentException::class)
    fun wrongTranscriptCannotBeReportedAsRepaired() {
        val old = manager.startSession("hello")
        manager.update(old.copy(state = VoiceRepairState.SUCCESS, attemptCount = 1,
            repairAppliedAtMs = clock, secondTest = VoiceRepairTestResult("hello", "goodbye")))
    }

    @Test fun matchingRetestAfterRepairCanComplete() {
        val old = manager.startSession("hello")
        val result = manager.update(old.copy(state = VoiceRepairState.SUCCESS, attemptCount = 1,
            repairAppliedAtMs = clock, secondTest = VoiceRepairTestResult("hello", "hello")))
        assertEquals(VoiceRepairState.SUCCESS, result.state)
        org.junit.Assert.assertNull(manager.cancel())
        org.junit.Assert.assertNull(manager.markInterrupted())
    }
}
