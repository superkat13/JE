package com.pineapple.sageos2.speech.voicerepair

import com.pineapple.sageos2.memory.ConversationEntry
import com.pineapple.sageos2.memory.ConversationHistorySnapshot
import com.pineapple.sageos2.memory.ConversationHistoryStore
import com.pineapple.sageos2.memory.ConversationInput
import com.pineapple.sageos2.memory.ConversationSpeaker
import com.pineapple.sageos2.runtime.RuntimeScheduler
import com.pineapple.sageos2.runtime.ScheduledHandle
import org.junit.Assert.*
import org.junit.Test

/** End-to-end owner-visible completion reporting: real controller -> notifier -> persistent
 *  conversation history -> the exact row source MainActivity.renderConversation() reads. */
class VoiceRepairOutcomeNotifierTest {
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
        var resets = 0
        var releases = 0
        var callback: ((VoiceRepairTestResult) -> Unit)? = null
        var resetCallback: ((Boolean) -> Unit)? = null
        override fun acquire(owner: String) = !busy
        override fun capture(owner: String, expected: String, result: (VoiceRepairTestResult) -> Unit) {
            callback = result
            if (broken) result(VoiceRepairTestResult(expected, null, errorCode = 5))
        }
        override fun reset(owner: String, completed: (Boolean) -> Unit) { resets++; resetCallback = completed }
        fun completeReset(success: Boolean = true) { if (success) broken = false; resetCallback!!.invoke(success) }
        override fun release(owner: String) { releases++ }
        fun heard(text: String) { callback!!.invoke(VoiceRepairTestResult("adapter must not control expected phrase", text)) }
    }
    private class MemStore : ConversationHistoryStore {
        val entries = mutableListOf<ConversationEntry>()
        private var revision = 0L
        override fun recent(limit: Int) =
            ConversationHistorySnapshot(revision, entries.toList().takeLast(limit.coerceAtLeast(0)))
        override fun record(entry: ConversationEntry) { entries += entry; revision++ }
        override fun clear() { entries.clear(); revision = 0 }
    }
    private class Fixture {
        val clock = Clock()
        val port = Adapter()
        val manager = VoiceRepairSessionManager { clock.now }
        val store = MemStore()
        val deliveries = mutableListOf<String>()
        val notifier = VoiceRepairOutcomeNotifier(store, notify = deliveries::add)
        val controller = VoiceRepairOrchestrator(
            port, manager, clock, clockMs = { clock.now }, onChanged = { notifier.onTerminal(it) }
        )
        fun state() = manager.current()!!.state
    }

    private fun completedSuccessfully(f: Fixture) {
        f.port.broken = true
        f.controller.startRepair("hello")
        f.port.completeReset()
        f.port.heard("hello")
        assertEquals(VoiceRepairState.SUCCESS, f.state())
    }

    @Test fun successfulCapturePersistsExactlyOneOwnerEntryAndDeliversOnce() {
        val f = Fixture(); completedSuccessfully(f)
        val entries = f.store.entries
        assertEquals(1, entries.size)
        val entry = entries.single()
        assertEquals(ConversationSpeaker.SAGE, entry.speaker)
        assertEquals(ConversationInput.SYSTEM, entry.input)
        assertEquals(0L, entry.turnId)
        assertTrue(entry.text.contains("Voice test complete"))
        assertTrue(entry.text.contains("passed"))
        assertTrue(entry.text.contains("verified"))
        assertEquals(listOf(entry.text), f.deliveries)
    }

    @Test fun healthyCapturePersistsOwnerEntryAndDeliversOnce() {
        val f = Fixture(); f.controller.startRepair("hello"); f.port.heard("hello")
        assertEquals(VoiceRepairState.HEALTHY, f.state())
        val entry = f.store.entries.single()
        assertTrue(entry.text.contains("Voice test complete"))
        assertTrue(entry.text.contains("without any change"))
        assertEquals(listOf(entry.text), f.deliveries)
    }

    @Test fun failedCapturePersistsAnHonestOwnerEntry() {
        val f = Fixture(); f.controller.startRepair("hello"); f.port.heard("goodbye")
        assertEquals(VoiceRepairState.FAILED, f.state())
        val text = f.store.entries.single().text
        assertTrue(text.contains("Voice test complete"))
        assertTrue(text.contains("did not pass"))
        assertTrue(text.contains("not verified"))
    }

    @Test fun persistencePrecedesUiNotification() {
        val f = Fixture()
        val notifier = VoiceRepairOutcomeNotifier(
            f.store,
            notify = { text -> assertTrue(f.store.recent(1).entries.any { it.text == text }) }
        )
        val controller = VoiceRepairOrchestrator(
            f.port, f.manager, f.clock, clockMs = { f.clock.now }, onChanged = { notifier.onTerminal(it) }
        )
        f.port.broken = true
        controller.startRepair("hello")
        f.port.completeReset()
        f.port.heard("hello")
    }

    @Test fun duplicateTerminalPublishPersistsAndDeliversExactlyOnce() {
        val f = Fixture(); completedSuccessfully(f)
        val session = f.manager.current()!!
        f.notifier.onTerminal(session)
        f.notifier.onTerminal(session)
        assertEquals(1, f.store.entries.size)
        assertEquals(1, f.deliveries.size)
    }

    @Test fun rerenderedNotificationDoesNotDuplicatePersistedEntry() {
        val f = Fixture(); completedSuccessfully(f)
        // A re-foregrounded activity sends onTextResponse again: renderConversation() rereads history.
        assertTrue(f.store.recent(60).entries.single().text == f.deliveries.single())
        assertEquals(1, f.store.recent(60).entries.size)
    }

    @Test fun interruptionAndCancellationAreNeverPersistedOrPushed() {
        val f = Fixture(); f.controller.startRepair("hello")
        assertEquals(0, f.store.entries.size) // active DIAGNOSING not presentable
        f.controller.interrupt(); f.port.heard("hello")
        assertEquals(VoiceRepairState.INTERRUPTED, f.state())
        assertEquals(0, f.store.entries.size); assertTrue(f.deliveries.isEmpty())
        val g = Fixture(); g.controller.startRepair("hello"); g.controller.cancel()
        assertEquals(VoiceRepairState.CANCELLED, g.state())
        assertEquals(0, g.store.entries.size); assertTrue(g.deliveries.isEmpty())
    }

    @Test fun normalConversationRoutingIsUnaffectedByRepairEntry() {
        val f = Fixture()
        f.store.record(ConversationEntry("o1", 41L, ConversationSpeaker.OWNER, ConversationInput.TEXT, "hi", 1))
        f.store.record(ConversationEntry("s1", 41L, ConversationSpeaker.SAGE, ConversationInput.TEXT, "hello there", 2))
        completedSuccessfully(f)
        val all = f.store.recent(10).entries
        assertEquals(3, all.size)
        assertEquals(41L, all[0].turnId); assertEquals(ConversationSpeaker.OWNER, all[0].speaker)
        assertEquals(41L, all[1].turnId); assertEquals(ConversationSpeaker.SAGE, all[1].speaker)
        assertEquals(0L, all[2].turnId); assertEquals(ConversationInput.SYSTEM, all[2].input)
    }

    @Test fun secondSessionOutcomeLandsAsSeparateEntry() {
        val f = Fixture(); completedSuccessfully(f)
        val first = f.store.entries.single()
        f.controller.startRepair("again"); f.port.heard("again")
        assertEquals(VoiceRepairState.HEALTHY, f.state())
        assertEquals(2, f.store.entries.size)
        assertEquals(2, f.deliveries.size)
        assertEquals(first, f.store.entries.first())
    }
}