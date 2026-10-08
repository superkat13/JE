package com.pineapple.sageos2.speech.voicerepair

import com.pineapple.sageos2.runtime.RuntimeScheduler
import com.pineapple.sageos2.runtime.ScheduledHandle
import org.junit.Assert.*
import org.junit.Test

class VoiceRepairWorkflowTest {
    private class Scheduler : RuntimeScheduler {
        data class Task(val delay: Long, val run: () -> Unit, var cancelled: Boolean = false)
        val tasks = mutableListOf<Task>()
        override fun schedule(delayMs: Long, task: () -> Unit): ScheduledHandle {
            val item = Task(delayMs, task); tasks.add(item)
            return object : ScheduledHandle { override fun cancel() { item.cancelled = true } }
        }
        fun next() { val i = tasks.indexOfFirst { !it.cancelled && it.delay < 60_000 }; check(i >= 0); tasks.removeAt(i).run() }
    }
    private class Port : VoiceDiagnosticPort {
        var owner: String? = null
        var captures = mutableListOf<String>()
        var callback: ((VoiceRepairTestResult) -> Unit)? = null
        var releases = 0
        var resets = 0
        override fun acquire(owner: String): Boolean { check(this.owner == null); this.owner = owner; return true }
        override fun capture(owner: String, expected: String, result: (VoiceRepairTestResult) -> Unit) {
            check(this.owner == owner); captures.add(expected); callback = result
        }
        override fun reset(owner: String, completed: (Boolean) -> Unit) { resets++; completed(true) }
        override fun release(owner: String) { check(this.owner == owner); this.owner = null; releases++ }
    }
    private class Fixture {
        val scheduler = Scheduler()
        val port = Port()
        val manager = VoiceRepairSessionManager()
        var idle = false
        var held = false
        var releases = 0
        lateinit var workflow: VoiceRepairWorkflow
        val controller = VoiceRepairOrchestrator(port, manager, scheduler, onChanged = { workflow.sessionChanged(it) })
        init { workflow = VoiceRepairWorkflow(controller, scheduler, reserve = {
            if (!idle || held) false else { held = true; true }
        }, release = { held = false; releases++ }, changed = {}) }
    }

    @Test fun phraseWaitsForReplyAndBusyRuntimeThenUsesOnlyDedicatedCapture() {
        val f = Fixture()
        f.workflow.request()
        assertTrue(f.workflow.awaitingPhrase)
        assertTrue(f.workflow.submitPhrase("delete everything and send a message"))
        assertTrue(f.port.captures.isEmpty())
        f.scheduler.next()
        assertTrue(f.port.captures.isEmpty())
        f.idle = true; f.scheduler.next()
        assertEquals(listOf("delete everything and send a message"), f.port.captures)
        assertTrue(f.held)
        assertTrue(f.workflow.message.contains("Speak now"))
    }

    @Test fun cancelReachesControllerAndReleasesBothReservationsEvenWithLateCallback() {
        val f = Fixture(); f.idle = true
        f.workflow.request(); f.workflow.submitPhrase("hello"); f.scheduler.next()
        val stale = f.port.callback!!
        f.workflow.cancel()
        assertNull(f.port.owner); assertFalse(f.held)
        assertEquals(1, f.port.releases); assertEquals(1, f.releases)
        assertEquals(VoiceRepairState.CANCELLED, f.manager.current()!!.state)
        stale(VoiceRepairTestResult("hello", "hello"))
        assertEquals(VoiceRepairState.CANCELLED, f.manager.current()!!.state)
        assertEquals(0, f.port.resets)
    }

    @Test fun cancelledPendingAdmissionCannotStartLater() {
        val f = Fixture(); f.workflow.request(); f.workflow.submitPhrase("hello")
        val late = f.scheduler.tasks.first().run
        f.workflow.cancel(); f.idle = true; late()
        assertTrue(f.port.captures.isEmpty()); assertFalse(f.held)
    }

    @Test fun mismatchShowsActualWordsAndNeverClaimsRepair() {
        val f = Fixture(); f.idle = true
        f.workflow.request(); f.workflow.submitPhrase("hello"); f.scheduler.next()
        f.port.callback!!(VoiceRepairTestResult("hello", "yellow", elapsedMs = 123))
        assertTrue(f.workflow.message.contains("yellow")); assertTrue(f.workflow.message.contains("123 ms"))
        assertTrue(f.workflow.message.contains("No repair was verified")); assertEquals(0, f.port.resets)
        assertTrue(f.held) // adapter release must precede runtime restoration
        f.scheduler.next(); assertFalse(f.held); assertEquals(1, f.port.releases)
    }

    @Test fun completedResetIsReportedSeparatelyFromVerifiedRetest() {
        val f = Fixture(); f.idle = true
        f.workflow.request(); f.workflow.submitPhrase("hello"); f.scheduler.next()
        f.port.callback!!(VoiceRepairTestResult("hello", null, errorCode = 5))
        assertEquals(1, f.port.resets); assertTrue(f.workflow.message.contains("same phrase"))
        f.workflow.interrupt()
        assertTrue(f.workflow.message.contains("No repair was verified"))
        assertTrue(f.workflow.message.contains("recreated")); assertFalse(f.held)
        assertEquals(VoiceRepairState.INTERRUPTED, f.manager.current()!!.state)
    }

    @Test fun invalidPhrasesRemainInDedicatedInput() {
        val f = Fixture(); f.workflow.request()
        assertFalse(f.workflow.submitPhrase(" ")); assertFalse(f.workflow.submitPhrase("x".repeat(201)))
        assertTrue(f.workflow.awaitingPhrase); assertTrue(f.port.captures.isEmpty())
    }

    @Test fun busyAdmissionIsBoundedWithoutInterruptingOtherWork() {
        val f = Fixture(); f.workflow.request(); f.workflow.submitPhrase("hello")
        repeat(301) { f.scheduler.next() }
        assertFalse(f.workflow.waitingForIdle); assertFalse(f.held)
        assertTrue(f.port.captures.isEmpty()); assertTrue(f.workflow.message.contains("idle microphone"))
    }

    @Test fun successReportsBeforeAfterAndActualChange() {
        val f = Fixture(); f.idle = true
        f.workflow.request(); f.workflow.submitPhrase("hello"); f.scheduler.next()
        f.port.callback!!(VoiceRepairTestResult("hello", null, errorCode = 5))
        f.port.callback!!(VoiceRepairTestResult("hello", "hello", elapsedMs = 42))
        f.scheduler.next()
        assertFalse(f.held); assertEquals(VoiceRepairState.SUCCESS, f.manager.current()!!.state)
        assertTrue(f.workflow.message.contains("Repair verified")); assertTrue(f.workflow.message.contains("Retest heard"))
    }
}
