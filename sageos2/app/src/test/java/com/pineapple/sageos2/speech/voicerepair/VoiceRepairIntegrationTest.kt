package com.pineapple.sageos2.speech.voicerepair

import com.pineapple.sageos2.personal.EmptySagePersonalResponder
import com.pineapple.sageos2.personal.SagePersonalResolution
import com.pineapple.sageos2.runtime.RuntimeScheduler
import com.pineapple.sageos2.runtime.ScheduledHandle
import org.junit.Assert.*
import org.junit.Test

/** Controller integration with an injected diagnostic adapter; not an Android microphone test. */
class VoiceRepairIntegrationTest {
    private class Clock : RuntimeScheduler {
        var now = 1000L
        private var seq = 0L
        private val tasks = mutableListOf<Triple<Long, Long, () -> Unit>>() // seq, due, task
        override fun schedule(delayMs: Long, task: () -> Unit): ScheduledHandle {
            val entry = Triple(seq++, now + delayMs, task)
            tasks += entry
            return object : ScheduledHandle { override fun cancel() { tasks.remove(entry) } }
        }
        /** Fires every scheduled task due within [ms], earliest due first, including tasks they schedule. */
        fun advance(ms: Long) {
            now += ms
            while (true) {
                val next = tasks.filter { it.second <= now }.minWithOrNull(compareBy({ it.second }, { it.first })) ?: break
                tasks.remove(next)
                next.third()
            }
        }
        fun expire() = advance(60_000)
    }
    private class Adapter : VoiceDiagnosticPort {
        var busy = false
        var broken = false
        var captures = 0
        var resets = 0
        var releases = 0
        var callback: ((VoiceRepairTestResult) -> Unit)? = null
        var resetCallback: ((Boolean) -> Unit)? = null
        var readyCallback: (() -> Unit)? = null
        var speechCallback: (() -> Unit)? = null
        val phrases = mutableListOf<String>()
        override fun acquire(owner: String) = !busy
        override fun capture(
            owner: String,
            expected: String,
            ready: () -> Unit,
            speechBegan: () -> Unit,
            result: (VoiceRepairTestResult) -> Unit
        ) {
            captures++; phrases += expected
            readyCallback = ready; speechCallback = speechBegan
            callback = result
            if (broken) result(VoiceRepairTestResult(expected, null, errorCode = 5))
        }
        override fun reset(owner: String, completed: (Boolean) -> Unit) { resets++; resetCallback = completed }
        fun completeReset(success: Boolean = true) { if (success) broken = false; resetCallback!!.invoke(success) }
        override fun release(owner: String) { releases++ }
        fun ready() { readyCallback!!.invoke() }
        fun speechBegan() { speechCallback!!.invoke() }
        fun heard(text: String) { callback!!.invoke(VoiceRepairTestResult("adapter must not control expected phrase", text)) }
        fun error(code: Int) { callback!!.invoke(VoiceRepairTestResult("ignored", null, errorCode = code)) }
    }
    private class Fixture {
        val clock = Clock()
        val port = Adapter()
        val manager = VoiceRepairSessionManager { clock.now }
        val readyEvents = mutableListOf<VoiceRepairSession>()
        val controller = VoiceRepairOrchestrator(
            port, manager, clock, clockMs = { clock.now }, onReady = { readyEvents += it }
        )
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
    @Test fun noReadinessReleasesMicrophoneWithReadyTimeout() {
        val f = Fixture(); f.controller.startRepair("hello")
        f.clock.advance(15_000) // past the readiness window, but before the overall deadline
        assertEquals(VoiceRepairCause.READY_TIMEOUT, f.manager.current()!!.cause)
        assertEquals(1, f.port.releases); f.port.heard("hello")
        assertEquals(VoiceRepairState.FAILED, f.state())
    }
    @Test fun overallDeadlineAfterReadinessStillReleases() {
        val f = Fixture(); f.controller.startRepair("hello"); f.port.ready(); f.clock.expire()
        assertEquals(VoiceRepairCause.DEADLINE_EXCEEDED, f.manager.current()!!.cause)
        assertEquals(1, f.port.releases)
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

    @Test fun completedRepairPresentsOwnerOutcomeAfterCapture() {
        val f = Fixture(); f.port.broken = true
        f.controller.startRepair("hello")
        f.port.completeReset(); f.port.heard("hello")
        assertEquals(VoiceRepairState.SUCCESS, f.state())
        val text = VoiceRepairCompletionPresenter.ownerText(f.manager.current()!!)
        assertNotNull(text)
        assertTrue(text!!.contains("Voice test complete"))
        assertTrue(text.contains("passed"))
        assertTrue(text.contains("verified"))
    }

    @Test fun healthyCapturePresentsOwnerOutcomeAfterCapture() {
        val f = Fixture(); f.controller.startRepair("hello"); f.port.heard("hello")
        assertEquals(VoiceRepairState.HEALTHY, f.state())
        val text = VoiceRepairCompletionPresenter.ownerText(f.manager.current()!!)
        assertNotNull(text)
        assertTrue(text!!.contains("without any change"))
    }

    @Test fun failedCapturePresentsAnHonestOwnerOutcome() {
        val f = Fixture(); f.controller.startRepair("hello"); f.port.heard("goodbye")
        assertEquals(VoiceRepairState.FAILED, f.state())
        val text = VoiceRepairCompletionPresenter.ownerText(f.manager.current()!!)!!
        assertTrue(text.contains("Voice test complete"))
        assertTrue(text.contains("did not pass"))
        assertTrue(text.contains("not verified"))
    }

    @Test fun interruptionAndCancellationAreNeverPushedUnprompted() {
        val f = Fixture(); f.controller.startRepair("hello")
        assertNull(VoiceRepairCompletionPresenter.ownerText(f.manager.current()!!)) // active DIAGNOSING
        f.controller.interrupt()
        assertNull(VoiceRepairCompletionPresenter.ownerText(f.manager.current()!!)) // INTERRUPTED
        val g = Fixture(); g.controller.startRepair("hello"); g.controller.cancel()
        assertNull(VoiceRepairCompletionPresenter.ownerText(g.manager.current()!!)) // CANCELLED
    }

    @Test fun statusCommandPresentsTheFinalOutcomeAfterCapture() {
        val f = Fixture(); f.controller.startRepair("hello"); f.port.heard("hello")
        assertEquals(VoiceRepairState.HEALTHY, f.state())
        // The responder surfaces the persisted terminal report when the owner checks in.
        val responder = VoiceRepairResponder(
            EmptySagePersonalResponder, f.manager,
            latestReport = { VoiceRepairReporter.terminalReport(f.manager.current()!!) }
        )
        val reply = (responder.resolve("voice repair status")
            as SagePersonalResolution.Reply).text
        assertTrue(reply.contains("without any change"))
        assertTrue(reply.contains("no repair was needed"))
    }

    @Test fun readinessStampsOneStepAndFiresCallbackExactlyOnce() {
        val f = Fixture(); f.controller.startRepair("hello")
        f.port.ready(); f.port.ready() // duplicate callback must not double-prompt
        assertEquals(1, f.manager.current()!!.steps.count { it.name == "ready" })
        assertEquals(1, f.readyEvents.size)
    }

    @Test fun speechOnsetIsRecordedOnlyAfterReadiness() {
        val f = Fixture(); f.controller.startRepair("hello")
        f.port.ready(); f.port.speechBegan()
        val names = f.manager.current()!!.steps.map { it.name }
        assertTrue(names.indexOf("ready") < names.indexOf("speech_began"))
        assertTrue(f.manager.current()!!.steps.any { it.name == "speech_began" })
    }

    @Test fun readinessArrivingAfterCaptureResultIsIgnored() {
        val f = Fixture(); f.controller.startRepair("hello"); f.port.heard("hello")
        f.port.ready()
        assertEquals(0, f.readyEvents.size)
    }

    @Test fun retestReadinessPromptsAgainForTheNewCapture() {
        val f = Fixture()
        f.controller.startRepair("hello"); f.port.ready()
        f.port.error(5) // lifecycle failure after readiness -> reset -> retest capture
        f.port.completeReset(); f.port.ready()
        assertEquals(2, f.readyEvents.size)
        assertEquals(VoiceRepairState.RETESTING, f.readyEvents.last().state)
    }

    @Test fun cancelledCaptureNeverFiresReadinessCallback() {
        val f = Fixture(); f.controller.startRepair("hello"); f.controller.cancel()
        f.port.ready()
        assertEquals(0, f.readyEvents.size)
        assertEquals(VoiceRepairState.CANCELLED, f.state())
    }
}
