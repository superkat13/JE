package com.pineapple.sageos2.speech.voicerepair

import com.pineapple.sageos2.memory.ConversationEntry
import com.pineapple.sageos2.memory.ConversationInput
import com.pineapple.sageos2.memory.ConversationSpeaker
import com.pineapple.sageos2.memory.SharedPreferencesConversationHistoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Persistence of the owner-visible voice-repair report through the real prefs-backed
 *  conversation store, so a next-foregrounded MainActivity renders it without a live listener. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VoiceRepairCompletionPersistenceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun store() = SharedPreferencesConversationHistoryStore(context)

    @Before fun setUp() { store().clear() }

    private fun terminal(state: VoiceRepairState, phrase: String = "good morning") = VoiceRepairSession(
        id = "s-1", state = state, cause = VoiceRepairCause.NONE,
        startedAtMs = 1_000, endedAtMs = 2_000, deadlineMs = 60_000, testPhrase = phrase
    )

    @Test fun terminalResultIsPersistedAndSurvivesForegroundReload() {
        val deliveries = mutableListOf<String>()
        val notifier = VoiceRepairOutcomeNotifier(store(), notify = deliveries::add)
        notifier.onTerminal(terminal(VoiceRepairState.SUCCESS))
        assertEquals(1, deliveries.size)
        val reloaded = store().recent(200).entries
        assertEquals(1, reloaded.size)
        val entry = reloaded.single()
        assertEquals(ConversationSpeaker.SAGE, entry.speaker)
        assertEquals(ConversationInput.SYSTEM, entry.input)
        assertEquals(0L, entry.turnId)
        assertTrue(entry.text.contains("Voice test complete"))
        assertEquals(entry.text, deliveries.single())
    }

    @Test fun backgroundedNotificationIsStillRenderedFromPersistence() {
        // No UI listener attached (activity backgrounded): onTextResponse is dropped by design.
        val notifier = VoiceRepairOutcomeNotifier(store(), notify = {})
        notifier.onTerminal(terminal(VoiceRepairState.HEALTHY))
        val reloaded = store().recent(60).entries
        assertEquals(1, reloaded.size)
        assertTrue(reloaded.single().text.contains("without any change"))
    }

    @Test fun interruptionAndCancellationPersistNothing() {
        val notifier = VoiceRepairOutcomeNotifier(store(), notify = {})
        notifier.onTerminal(terminal(VoiceRepairState.INTERRUPTED))
        notifier.onTerminal(terminal(VoiceRepairState.CANCELLED))
        assertTrue(store().recent(60).entries.isEmpty())
    }

    @Test fun duplicateRepublishPersistsExactlyOneEntryAcrossActivityLifecycle() {
        val notifier = VoiceRepairOutcomeNotifier(store(), notify = {})
        val session = terminal(VoiceRepairState.FAILED)
        notifier.onTerminal(session)
        notifier.onTerminal(session) // re-foreground re-delivery attempt
        assertEquals(1, store().recent(60).entries.size)
    }

    @Test fun repairEntryRendersAlongsideRoutedConversationTurns() {
        val s = store()
        s.record(ConversationEntry("o1", 41L, ConversationSpeaker.OWNER, ConversationInput.TEXT, "hi", 1))
        s.record(ConversationEntry("s1", 41L, ConversationSpeaker.SAGE, ConversationInput.TEXT, "hello", 2))
        val notifier = VoiceRepairOutcomeNotifier(s, notify = {})
        notifier.onTerminal(terminal(VoiceRepairState.SUCCESS))
        val all = s.recent(60).entries
        assertEquals(3, all.size)
        assertEquals(41L, all[0].turnId); assertEquals(ConversationSpeaker.OWNER, all[0].speaker)
        assertEquals(41L, all[1].turnId); assertEquals(ConversationSpeaker.SAGE, all[1].speaker)
        assertEquals(0L, all[2].turnId); assertEquals(ConversationInput.SYSTEM, all[2].input)
    }
}