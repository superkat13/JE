package com.pineapple.sageos2.speech.voicerepair

import com.pineapple.sageos2.memory.ConversationEntry
import com.pineapple.sageos2.memory.ConversationHistorySnapshot
import com.pineapple.sageos2.memory.ConversationHistoryStore
import com.pineapple.sageos2.memory.ConversationInput
import com.pineapple.sageos2.memory.ConversationSpeaker
import org.junit.Assert.*
import org.junit.Test

/** The owner is only ever told to speak after the recognizer itself reports readiness, and the
 *  prompt is persisted before it is pushed so a backgrounded activity still shows it. */
class VoiceRepairReadinessTest {
    private fun session(
        state: VoiceRepairState = VoiceRepairState.TESTING_EXPECTED,
        id: String = "s1",
        steps: List<VoiceRepairStep> = emptyList(),
        phrase: String = "hello"
    ) = VoiceRepairSession(id = id, state = state, cause = VoiceRepairCause.NONE, testPhrase = phrase, steps = steps)

    private fun readyStep(at: Long) = VoiceRepairStep("ready", at)

    private class MemStore : ConversationHistoryStore {
        val entries = mutableListOf<ConversationEntry>()
        private var revision = 0L
        override fun recent(limit: Int) =
            ConversationHistorySnapshot(revision, entries.toList().takeLast(limit.coerceAtLeast(0)))
        override fun record(entry: ConversationEntry) { entries += entry; revision++ }
        override fun clear() { entries.clear(); revision = 0 }
    }

    @Test fun presenterWithholdsThePromptUntilTheRecognizerReportsReady() {
        assertNull(VoiceRepairReadinessPresenter.ownerText(session()))
        val prompt = VoiceRepairReadinessPresenter.ownerText(session(steps = listOf(readyStep(10))))
        assertNotNull(prompt)
        assertTrue(prompt!!.contains("hello"))
        assertTrue(prompt.contains("listening now"))
    }

    @Test fun presenterOnlyPromptsDuringALiveCapture() {
        for (state in listOf(VoiceRepairState.SUCCESS, VoiceRepairState.HEALTHY, VoiceRepairState.FAILED,
                VoiceRepairState.INTERRUPTED, VoiceRepairState.CANCELLED, VoiceRepairState.REPAIRING)) {
            assertNull("state $state must not prompt", VoiceRepairReadinessPresenter.ownerText(
                session(state = state, steps = listOf(readyStep(10)))))
        }
        assertNotNull(VoiceRepairReadinessPresenter.ownerText(session(state = VoiceRepairState.RETESTING, steps = listOf(readyStep(10)))))
    }

    @Test fun notifierPersistsThenNotifiesExactlyOnce() {
        val store = MemStore()
        val deliveries = mutableListOf<String>()
        val notifier = VoiceRepairReadinessNotifier(store) { text ->
            // Persistence must precede notification.
            assertTrue(store.recent(5).entries.any { it.text == text })
            deliveries += text
        }
        val s = session(steps = listOf(readyStep(10)))
        notifier.onReady(s)
        notifier.onReady(s) // duplicate readiness must not double-prompt
        assertEquals(1, store.entries.size)
        val entry = store.entries.single()
        assertEquals(ConversationSpeaker.SAGE, entry.speaker)
        assertEquals(ConversationInput.SYSTEM, entry.input)
        assertEquals(0L, entry.turnId)
        assertEquals(listOf(entry.text), deliveries)
    }

    @Test fun notifierNeverPromptsBeforeReadiness() {
        val store = MemStore()
        val notifier = VoiceRepairReadinessNotifier(store) { fail("must not notify before readiness") }
        notifier.onReady(session())
        assertTrue(store.entries.isEmpty())
    }

    @Test fun notifierPromptsAgainForARetestCapture() {
        val store = MemStore()
        val deliveries = mutableListOf<String>()
        val notifier = VoiceRepairReadinessNotifier(store, deliveries::add)
        notifier.onReady(session(state = VoiceRepairState.TESTING_EXPECTED, steps = listOf(readyStep(10))))
        notifier.onReady(session(state = VoiceRepairState.RETESTING, steps = listOf(readyStep(10), readyStep(90))))
        assertEquals(2, store.entries.size)
        assertEquals(2, deliveries.size)
    }

    @Test fun promptNeverLeaksAnIdentifier() {
        val prompt = VoiceRepairReadinessPresenter.ownerText(
            session(id = "session-secret-id", steps = listOf(readyStep(10))))!!
        assertFalse(prompt.contains("session-secret-id"))
        assertFalse(prompt.contains("voice_repair"))
    }
}
