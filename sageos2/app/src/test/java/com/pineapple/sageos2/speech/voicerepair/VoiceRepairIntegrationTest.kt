package com.pineapple.sageos2.speech.voicerepair

import com.pineapple.sageos2.runtime.RuntimeScheduler
import com.pineapple.sageos2.runtime.ScheduledHandle
import org.junit.Assert.*
import org.junit.Test

/** Controller integration with an injected diagnostic adapter; not an Android microphone test. */
class VoiceRepairIntegrationTest {
    private class Clock : RuntimeScheduler {
        var now = 1000L
        var task: (() -> Unit)? = null
        override fun schedule(delayMs: Long, task: () -> Unit): ScheduledHandle {
            this.task = task
            return object : ScheduledHandle { override fun cancel() { this@Clock.task = null } }
        }
        fun expire() { now += 60_000; task?.invoke() }
    }
    private class Adapter : VoiceDiagnosticPort {
        var busy = false
        var broken = false
        var captures = 0
        var resets = 0
        var releases = 0
        var callback: ((VoiceRepairTestResult) -> Unit)? = null
        var resetCallback: ((Boolean) -> Unit)? = null
        val phrases = mutableListOf<String>()
        override fun acquire(owner: String) = !busy
        override fun capture(owner: String, expected: String, result: (VoiceRepairTestResult) -> Unit) {
            captures++; phrases += expected; callback = result
            if (broken) result(VoiceRepairTestResult(expected, null, errorCode = 5))
        }
        override fun reset(owner: String, completed: (Boolean) -> Unit) { resets++; resetCallback = completed }
        fun completeReset(success: Boolean = true) { if (success) broken = false; resetCallback!!.invoke(success) }
        override fun release(owner: String) { releases++ }
        fun heard(text: String) { callback!!.invoke(VoiceRepairTestResult("adapter must not control expected phrase", text)) }
        fun error(code: Int) { callback!!.invoke(VoiceRepairTestResult("ignored", null, errorCode = code)) }
    }
    private class Fixture {
        val clock = Clock()
        val port = Adapter()
        val manager = VoiceRepairSessionManager { clock.now }
        val controller = VoiceRepairOrchestrator(port, manager, clock, clockMs = { clock.now })
        fun state() = manager.current()!!.state
    }

    @Test fun injectedLifecycleFaultRequiresActualResetCompletionAndMatchingRetest() {
        val f = Fixture(); f.port.broken = true
        assertTrue(f.controller.startRepair("hello"))
        assertEquals(1, f.port.captures); assertEquals(1, f.port.resets)
        assertEquals(VoiceRepairState.REPAIRING, f.state())
        assertTrue(f.controller.resetRequested()); assertFalse(f.controller.resetCompleted())
        assertFalse(f.controller.createExport()!!.verified)
        f.port.completeReset()
        assertEquals(2, f.port.captures); assertEquals(listOf("hello", "hello"), f.port.phrases)
        assertTrue(f.controller.resetCompleted()); assertFalse(f.controller.createExport()!!.verified)
        f.port.heard("hello")
        assertEquals(VoiceRepairState.SUCCESS, f.state()); assertTrue(f.controller.createExport()!!.verified)
        assertEquals(1, f.port.releases)
    }
    @Test fun matchingInitialPhraseDoesNotClaimRepair() {
        val f = Fixture(); f.controller.startRepair("hello"); f.port.heard("hello")
        assertEquals(VoiceRepairState.HEALTHY, f.state()); assertEquals(0, f.port.resets)
        assertFalse(f.controller.createExport()!!.repairApplied); assertFalse(f.controller.createExport()!!.verified)
    }
    @Test fun wrongWordsNeverResetRecognizer() {
        val f = Fixture(); f.controller.startRepair("hello"); f.port.heard("goodbye")
        assertEquals(VoiceRepairCause.WRONG_TRANSCRIPT, f.manager.current()!!.cause)
        assertEquals(0, f.port.resets); assertEquals(1, f.port.releases)
    }
    @Test fun silencePermissionsAndMissingModelDoNotReset() {
        for (code in listOf(6,7,9,12,13,2)) {
            val f = Fixture(); f.controller.startRepair("hello"); f.port.error(code)
            assertEquals(0, f.port.resets); assertEquals(VoiceRepairState.FAILED, f.state())
        }
    }
    @Test fun wrongRetestStaysUnverified() {
        val f = Fixture(); f.port.broken = true; f.controller.startRepair("hello")
        f.port.completeReset(); f.port.heard("goodbye")
        assertEquals(VoiceRepairState.FAILED, f.state()); assertFalse(f.controller.createExport()!!.verified)
        assertEquals("goodbye", f.controller.createExport()!!.recognizedAfter)
    }
    @Test fun repeatedLifecycleFailureStopsAfterOneReset() {
        val f = Fixture(); f.port.broken = true; f.controller.startRepair("hello")
        f.port.completeReset(); f.port.error(5)
        assertEquals(VoiceRepairState.FAILED, f.state()); assertEquals(1, f.port.resets)
    }
    @Test fun resetFailureDoesNotStartRetest() {
        val f = Fixture(); f.port.broken = true; f.controller.startRepair("hello")
        f.port.completeReset(false)
        assertEquals(1, f.port.captures); assertEquals(VoiceRepairState.FAILED, f.state())
        assertFalse(f.controller.resetCompleted())
    }
    @Test fun cancelDuringResetRejectsLateCompletion() {
        val f = Fixture(); f.port.broken = true; f.controller.startRepair("hello")
        f.controller.cancel(); f.port.completeReset()
        assertEquals(VoiceRepairState.CANCELLED, f.state()); assertEquals(1, f.port.captures)
        assertEquals(1, f.port.releases)
    }
    @Test fun timeoutReleasesMicrophoneWithoutAnyCallback() {
        val f = Fixture(); f.controller.startRepair("hello"); f.clock.expire()
        assertEquals(VoiceRepairCause.DEADLINE_EXCEEDED, f.manager.current()!!.cause)
        assertEquals(1, f.port.releases); f.port.heard("hello")
        assertEquals(VoiceRepairState.FAILED, f.state())
    }
    @Test fun busyDoesNotCaptureOrReleaseAnotherOwner() {
        val f = Fixture(); f.port.busy = true
        assertFalse(f.controller.startRepair("hello")); assertEquals(0, f.port.captures)
        assertEquals(0, f.port.releases); assertEquals(VoiceRepairCause.BUSY_RUNTIME, f.manager.current()!!.cause)
    }
    @Test fun duplicateStartDoesNotReplaceCurrentSession() {
        val f = Fixture(); f.controller.startRepair("first"); val id = f.manager.current()!!.id
        assertFalse(f.controller.startRepair("second")); assertEquals(id, f.manager.current()!!.id)
    }
    @Test fun oldCaptureCannotAffectSuccessor() {
        val f = Fixture(); f.controller.startRepair("old"); val old = f.port.callback!!
        f.controller.cancel(); f.controller.startRepair("new")
        old(VoiceRepairTestResult("old", null, errorCode = 5))
        assertEquals(0, f.port.resets); assertEquals("new", f.manager.current()!!.testPhrase)
        f.port.heard("new"); assertEquals(VoiceRepairState.HEALTHY, f.state())
    }
    @Test fun duplicateResetCompletionCannotStartAnotherCapture() {
        val f = Fixture(); f.port.broken = true; f.controller.startRepair("hello")
        f.port.completeReset(); f.port.completeReset(); assertEquals(2, f.port.captures)
    }
    @Test fun interruptionIsUnverifiedAndReleasesCapture() {
        val f = Fixture(); f.controller.startRepair("hello"); f.controller.interrupt()
        f.port.heard("hello"); assertEquals(VoiceRepairState.INTERRUPTED, f.state())
        assertFalse(f.controller.createExport()!!.verified); assertEquals(1, f.port.releases)
    }
    @Test fun commandShapedPhraseRemainsDiagnosticEvidence() {
        val f = Fixture(); f.controller.startRepair("delete everything"); f.port.heard("delete everything")
        assertEquals(VoiceRepairState.HEALTHY, f.state())
        assertEquals("delete everything", f.controller.createExport()!!.recognizedBefore)
        assertTrue(f.manager.current()!!.steps.none { it.detail.contains("delete everything") })
        // Android integration must separately prove ordinary listeners receive no test callbacks.
    }
    @Test(expected = IllegalArgumentException::class) fun blankPhraseCannotStartCapture() {
        Fixture().controller.startRepair(" ")
    }
}
