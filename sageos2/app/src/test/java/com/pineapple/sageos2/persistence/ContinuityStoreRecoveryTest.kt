package com.pineapple.sageos2.persistence

import android.content.Context
import android.content.SharedPreferences
import com.pineapple.sageos2.apps.OwnerAppRecord
import com.pineapple.sageos2.apps.SharedPreferencesOwnerAppRegistry
import com.pineapple.sageos2.continuity.SharedPreferencesTaskContinuityStore
import com.pineapple.sageos2.continuity.TaskCheckpoint
import com.pineapple.sageos2.continuity.TaskState
import com.pineapple.sageos2.memory.ConversationEntry
import com.pineapple.sageos2.memory.ConversationInput
import com.pineapple.sageos2.memory.ConversationSpeaker
import com.pineapple.sageos2.memory.SharedPreferencesConversationHistoryStore
import com.pineapple.sageos2.memory.SharedPreferencesTwinMemoryStore
import com.pineapple.sageos2.memory.TwinMemorySource
import com.pineapple.sageos2.memory.TwinMemorySubject
import com.pineapple.sageos2.speech.SharedPreferencesWakeProfileStore
import com.pineapple.sageos2.speech.WakeProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * Regressions for unreadable continuity records. These operate only on Robolectric preferences,
 * never on the owner's installed data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ContinuityStoreRecoveryTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun prefs(name: String): SharedPreferences =
        context.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun reset() {
        listOf(
            "sage_twin_memory_v2",
            "sage_conversation_history_v2",
            "sageos2_tasks",
            "sage_owner_apps_v2",
            "sage_wake_profiles_v2"
        ).forEach { prefs(it).edit().clear().commit() }
    }

    private fun assertRetained(prefsName: String, raw: Any, message: String) {
        assertTrue(message, prefs(prefsName).all.entries.any { (_, value) -> value == raw })
    }

    @Test fun corruptTwinMemorySurvivesAnOwnerRemember() {
        reset()
        val damaged = "{owner-memory-before-power-loss"
        prefs("sage_twin_memory_v2").edit().putString("state", damaged).commit()

        SharedPreferencesTwinMemoryStore(context).remember(
            subject = TwinMemorySubject.OWNER,
            key = "new preference",
            value = "keep this",
            source = TwinMemorySource.EXPLICIT_OWNER,
            confidence = 1.0,
            nowEpochMs = 10L
        )

        assertRetained("sage_twin_memory_v2", damaged,
            "an unreadable memory state must remain recoverable after a new memory is written")
    }

    @Test fun corruptConversationHistorySurvivesANewTurn() {
        reset()
        val damaged = "[owner-conversation-before-restart"
        prefs("sage_conversation_history_v2").edit().putString("state", damaged).commit()

        SharedPreferencesConversationHistoryStore(context).record(
            ConversationEntry("new", 1L, ConversationSpeaker.OWNER, ConversationInput.TEXT, "hello", 10L)
        )

        assertRetained("sage_conversation_history_v2", damaged,
            "an unreadable conversation history must remain recoverable after a new turn")
    }

    @Test fun corruptTaskStoreSurvivesACheckpoint() {
        reset()
        val damaged = "{\"taskId\":\"unfinished\""
        prefs("sageos2_tasks").edit().putString("tasks", damaged).commit()

        SharedPreferencesTaskContinuityStore(context).upsert(
            TaskCheckpoint("new", "New work", TaskState.ACTIVE, "started", "continue", 10L)
        )

        assertRetained("sageos2_tasks", damaged,
            "an unreadable task list must remain recoverable after a checkpoint is saved")
    }

    @Test fun corruptOwnerAppsSurviveARegistryUpdate() {
        reset()
        val damaged = "{\"revision\":3,\"apps\":[{\"packageName\":\"owner.app\""
        prefs("sage_owner_apps_v2").edit().putString("state", damaged).commit()

        SharedPreferencesOwnerAppRegistry(context).upsert(
            OwnerAppRecord("com.example.new", "New App")
        )

        assertRetained("sage_owner_apps_v2", damaged,
            "an unreadable Owner Apps registry must remain recoverable after an update")
    }

    @Test fun corruptWakeProfilesSurviveAReplacement() {
        reset()
        val damaged = "[{\"id\":\"sage\",\"phrases\":[\"sage\""
        prefs("sage_wake_profiles_v2").edit().putString("profiles", damaged).commit()

        SharedPreferencesWakeProfileStore(context).replace(
            listOf(WakeProfile("sage", "Sage", listOf("sage"), mapOf("sage" to "▁S AGE")))
        )

        assertRetained("sage_wake_profiles_v2", damaged,
            "unreadable wake profiles must remain recoverable after a profile replacement")
    }

    @Test fun partiallyUnreadableMemoryRecordIsNotDroppedByAWrite() {
        reset()
        val damaged = """{"revision":7,"records":[
            {"id":"ok","subject":"OWNER","key":"existing","value":"kept","source":"EXPLICIT_OWNER",
             "confidence":1.0,"createdAtEpochMs":1,"updatedAtEpochMs":1,"active":true},
            {"id":"broken","subject":"NOT_A_SUBJECT","key":"damaged","value":"must survive",
             "source":"EXPLICIT_OWNER","confidence":1.0,"createdAtEpochMs":2,"updatedAtEpochMs":2,"active":true}
        ]}"""
        prefs("sage_twin_memory_v2").edit().putString("state", damaged).commit()
        val store = SharedPreferencesTwinMemoryStore(context)

        val readable = store.snapshot()
        assertEquals("the readable memory must still be available", 1, readable.records.size)
        store.remember(
            subject = TwinMemorySubject.SHARED,
            key = "new",
            value = "new value",
            source = TwinMemorySource.EXPLICIT_OWNER,
            confidence = 1.0,
            nowEpochMs = 10L
        )

        assertTrue("the complete raw state, including the unreadable record, must survive",
            prefs("sage_twin_memory_v2").all.values.any { it is String && it == damaged })
    }

    @Test fun wronglyTypedMemoryStateIsReadableAsDamagedAndNeverOverwrittenSilently() {
        reset()
        prefs("sage_twin_memory_v2").edit().putInt("state", 7).commit()
        val store = SharedPreferencesTwinMemoryStore(context)

        assertEquals("a wrongly typed store must read as empty, not crash", 0, store.snapshot().records.size)
        store.remember(
            subject = TwinMemorySubject.OWNER,
            key = "new",
            value = "value",
            source = TwinMemorySource.EXPLICIT_OWNER,
            confidence = 1.0,
            nowEpochMs = 10L
        )

        assertTrue("the wrongly typed original value must survive",
            prefs("sage_twin_memory_v2").all.values.any { it == 7 })
    }

    @Test fun missingEmptyAndDamagedAreDistinctForEveryStore() {
        reset()
        val memory = SharedPreferencesTwinMemoryStore(context)
        val history = SharedPreferencesConversationHistoryStore(context)
        val tasks = SharedPreferencesTaskContinuityStore(context)
        val ownerApps = SharedPreferencesOwnerAppRegistry(context)
        val wake = SharedPreferencesWakeProfileStore(context)

        assertEquals(ContinuityStoreState.MISSING, memory.health().state)
        assertEquals(ContinuityStoreState.MISSING, history.health().state)
        assertEquals(ContinuityStoreState.MISSING, tasks.health().state)
        assertEquals(ContinuityStoreState.MISSING, ownerApps.health().state)
        assertEquals(ContinuityStoreState.MISSING, wake.health().state)

        prefs("sage_twin_memory_v2").edit().putString("state", "{\"revision\":0,\"records\":[]}").commit()
        prefs("sage_conversation_history_v2").edit().putString("state", "{\"revision\":0,\"entries\":[]}").commit()
        prefs("sageos2_tasks").edit().putString("tasks", "[]").commit()
        prefs("sage_owner_apps_v2").edit().putString("state", "{\"revision\":0,\"apps\":[]}").commit()
        prefs("sage_wake_profiles_v2").edit().putString("profiles", "[]").commit()

        assertEquals(ContinuityStoreState.EMPTY, memory.health().state)
        assertEquals(ContinuityStoreState.EMPTY, history.health().state)
        assertEquals(ContinuityStoreState.EMPTY, tasks.health().state)
        assertEquals(ContinuityStoreState.EMPTY, ownerApps.health().state)
        assertEquals(ContinuityStoreState.EMPTY, wake.health().state)

        prefs("sage_twin_memory_v2").edit().putString("state", "{damaged").commit()
        prefs("sage_conversation_history_v2").edit().putString("state", "{damaged").commit()
        prefs("sageos2_tasks").edit().putString("tasks", "{damaged").commit()
        prefs("sage_owner_apps_v2").edit().putString("state", "{damaged").commit()
        prefs("sage_wake_profiles_v2").edit().putString("profiles", "{damaged").commit()

        assertEquals(ContinuityStoreState.DAMAGED, memory.health().state)
        assertEquals(ContinuityStoreState.DAMAGED, history.health().state)
        assertEquals(ContinuityStoreState.DAMAGED, tasks.health().state)
        assertEquals(ContinuityStoreState.DAMAGED, ownerApps.health().state)
        assertEquals(ContinuityStoreState.DAMAGED, wake.health().state)
    }

    @Test fun everyStoreReportsPartiallyUnreadableRecordsWithoutDiscardingReadableOnes() {
        reset()
        prefs("sage_twin_memory_v2").edit().putString("state",
            "{\"revision\":2,\"records\":[" +
                "{\"id\":\"ok\",\"subject\":\"OWNER\",\"key\":\"k\",\"value\":\"v\",\"source\":\"EXPLICIT_OWNER\",\"confidence\":1,\"createdAtEpochMs\":1,\"updatedAtEpochMs\":1}," +
                "{\"id\":\"bad\",\"subject\":\"NOT_A_SUBJECT\",\"key\":\"k2\",\"value\":\"v2\",\"source\":\"EXPLICIT_OWNER\",\"confidence\":1,\"createdAtEpochMs\":1,\"updatedAtEpochMs\":1}]}"
        ).commit()
        prefs("sage_conversation_history_v2").edit().putString("state",
            "{\"revision\":2,\"entries\":[" +
                "{\"id\":\"ok\",\"speaker\":\"OWNER\",\"input\":\"TEXT\",\"text\":\"hello\",\"timestampEpochMs\":1}," +
                "{\"id\":\"bad\",\"speaker\":\"NOBODY\",\"input\":\"TEXT\",\"text\":\"bad\",\"timestampEpochMs\":1}]}"
        ).commit()
        prefs("sageos2_tasks").edit().putString("tasks",
            "[{\"taskId\":\"ok\",\"title\":\"T\",\"state\":\"ACTIVE\",\"summary\":\"s\",\"updatedAtMs\":1}," +
                "{\"taskId\":\"bad\",\"title\":\"T\",\"state\":\"NO_SUCH_STATE\",\"summary\":\"s\",\"updatedAtMs\":1}]"
        ).commit()
        prefs("sage_owner_apps_v2").edit().putString("state",
            "{\"revision\":2,\"apps\":[" +
                "{\"packageName\":\"ok.app\",\"displayName\":\"Ok\"}," +
                "{\"packageName\":\"bad.app\"}]}"
        ).commit()
        prefs("sage_wake_profiles_v2").edit().putString("profiles",
            "[{\"id\":\"ok\",\"displayName\":\"Ok\",\"phrases\":[\"sage\"]}," +
                "{\"id\":\"bad\",\"displayName\":\"Bad\",\"phrases\":[]}]"
        ).commit()

        val memory = SharedPreferencesTwinMemoryStore(context).health()
        val history = SharedPreferencesConversationHistoryStore(context).health()
        val tasks = SharedPreferencesTaskContinuityStore(context).health()
        val ownerApps = SharedPreferencesOwnerAppRegistry(context).health()
        val wake = SharedPreferencesWakeProfileStore(context).health()

        listOf(memory, history, tasks, ownerApps, wake).forEach { health ->
            assertEquals(ContinuityStoreState.DAMAGED, health.state)
            assertEquals(1, health.unreadableRecords)
        }
        assertEquals(1, SharedPreferencesTwinMemoryStore(context).snapshot().records.size)
        assertEquals(1, SharedPreferencesConversationHistoryStore(context).recent(10).entries.size)
        assertEquals(1, SharedPreferencesTaskContinuityStore(context).active().size)
        assertEquals(1, SharedPreferencesOwnerAppRegistry(context).snapshot().apps.size)
        assertEquals(1, SharedPreferencesWakeProfileStore(context).profiles().size)
    }

    @Test fun repeatedDamageRetryDoesNotDuplicateForensicCopies() {
        reset()
        val damaged = "{same-damaged-memory"
        prefs("sage_twin_memory_v2").edit().putString("state", damaged).commit()
        val store = SharedPreferencesTwinMemoryStore(context)
        store.remember(TwinMemorySubject.OWNER, "first", "one", TwinMemorySource.EXPLICIT_OWNER, 1.0, 10L)
        assertEquals(1, store.health().retainedDamagedCopies)

        prefs("sage_twin_memory_v2").edit().putString("state", damaged).commit()
        store.remember(TwinMemorySubject.OWNER, "second", "two", TwinMemorySource.EXPLICIT_OWNER, 1.0, 20L)

        assertEquals(1, store.health().retainedDamagedCopies)
        assertTrue(prefs("sage_twin_memory_v2").all.values.any { it == damaged })
    }

    @Test fun clearingConversationHistoryPreservesUnreadableBytes() {
        reset()
        val damaged = "{unreadable-history-before-clear"
        prefs("sage_conversation_history_v2").edit().putString("state", damaged).commit()

        SharedPreferencesConversationHistoryStore(context).clear()

        assertTrue(prefs("sage_conversation_history_v2").all.values.any { it == damaged })
        assertEquals(ContinuityStoreState.MISSING,
            SharedPreferencesConversationHistoryStore(context).health().state)
    }
}
