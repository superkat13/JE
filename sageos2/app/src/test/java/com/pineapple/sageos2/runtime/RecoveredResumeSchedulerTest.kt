package com.pineapple.sageos2.runtime

import org.junit.Assert.*
import org.junit.Test

class RecoveredResumeSchedulerTest {
    @Test fun busyThenIdleResumesExactlyOnce() {
        val f = Fixture()
        f.recovery.schedule { f.resumes++ }
        val busy = f.clock.fireNext()
        busy.task() // A repeated busy callback must not create overlapping polls.
        assertEquals(1, f.clock.activeCount())
        f.clock.fireNext()
        assertEquals(0, f.resumes)
        assertEquals(1, f.clock.activeCount())
        f.idle = true
        val final = f.clock.fireNext()
        final.task() // A scheduler delivering the consumed callback twice must not replay it.
        assertEquals(1, f.resumes)
        assertEquals(0, f.exhausted)
        assertEquals(0, f.clock.activeCount())
    }

    @Test fun alwaysBusyExhaustsAtBoundWithoutAttemptingResume() {
        val f = Fixture()
        f.recovery.schedule { f.resumes++ }
        repeat(3) { f.clock.fireNext() }
        assertEquals(0, f.resumes)
        assertEquals(1, f.exhausted)
        assertEquals(0, f.clock.activeCount())
        assertEquals(listOf(1_200L, 1_200L, 1_200L), f.clock.delays)
    }

    @Test fun stoppedBeforeDeliveryDoesNotResumeOrScheduleAgain() {
        val f = Fixture()
        f.recovery.schedule { f.resumes++ }
        f.started = false
        f.idle = true
        f.clock.fireNext()
        assertEquals(0, f.resumes)
        assertEquals(0, f.exhausted)
        assertEquals(0, f.clock.activeCount())
        f.recovery.schedule { f.resumes++ }
        assertEquals(0, f.clock.activeCount())
    }

    @Test fun replacementCancelsAndIgnoresOldCallback() {
        val f = Fixture()
        var oldResumes = 0
        f.recovery.schedule { oldResumes++ }
        val old = f.clock.entries.single()
        f.recovery.schedule { f.resumes++ }
        assertTrue(old.cancelled)
        assertEquals(1, f.clock.activeCount())
        f.idle = true
        old.task() // Cancellation is best effort, so token validation is also required.
        assertEquals(0, oldResumes)
        assertEquals(0, f.resumes)
        f.clock.fireNext()
        assertEquals(1, f.resumes)
        assertEquals(0, f.clock.activeCount())
    }

    @Test fun cancellationInvalidatesEvenAlreadyDeliveredCallback() {
        val f = Fixture()
        f.recovery.schedule { f.resumes++ }
        val old = f.clock.entries.single()
        f.recovery.cancel()
        f.idle = true
        old.task()
        assertEquals(0, f.resumes)
        assertEquals(0, f.clock.activeCount())
    }

    private class Fixture {
        val clock = FakeScheduler()
        var started = true
        var idle = false
        var resumes = 0
        var exhausted = 0
        val recovery = RecoveredResumeScheduler(clock, { started }, { idle }, { exhausted++ }, maxChecks = 3)
    }

    private class FakeScheduler : RuntimeScheduler {
        class Entry(val task: () -> Unit) : ScheduledHandle {
            var cancelled = false
            var delivered = false
            override fun cancel() { cancelled = true }
        }
        val entries = mutableListOf<Entry>()
        val delays = mutableListOf<Long>()
        override fun schedule(delayMs: Long, task: () -> Unit): ScheduledHandle {
            delays += delayMs
            return Entry(task).also { entries += it }
        }
        fun activeCount() = entries.count { !it.cancelled && !it.delivered }
        fun fireNext(): Entry {
            val entry = entries.first { !it.cancelled && !it.delivered }
            entry.delivered = true
            entry.task()
            return entry
        }
    }
}
