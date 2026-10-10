package com.pineapple.sageos2.speech.voicerepair

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SharedPreferencesVoiceRepairHistoryStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("sageos2_voice_repair_history", 0)

    @Before fun setUp() {
        SharedPreferencesVoiceRepairHistoryStore(context).clear()
    }

    private fun terminal(id: String, state: VoiceRepairState = VoiceRepairState.FAILED) = VoiceRepairSession(
        id = id, state = state, cause = VoiceRepairCause.NONE,
        startedAtMs = 1_000, endedAtMs = 2_000, deadlineMs = 60_000, testPhrase = "good morning"
    )

    private fun active(id: String) = VoiceRepairSession(
        id = id, state = VoiceRepairState.TESTING_EXPECTED, cause = VoiceRepairCause.NONE,
        startedAtMs = 1_000, endedAtMs = null, deadlineMs = 60_000, testPhrase = "good morning"
    )

    @Test fun initialStoreIsEmpty() {
        val store = SharedPreferencesVoiceRepairHistoryStore(context)
        assertNull(store.latestTerminal())
        assertNull(store.interruptedSession())
        assertTrue(store.history().isEmpty())
    }

    @Test fun terminalSessionEntersHistoryAndStaysLatest() {
        val store = SharedPreferencesVoiceRepairHistoryStore(context)
        store.observe(terminal("t1", VoiceRepairState.SUCCESS))
        store.observe(terminal("t2", VoiceRepairState.HEALTHY))
        assertEquals("t2", store.latestTerminal()?.id)
        assertEquals(listOf("t1", "t2"), store.history().map { it.id })
        assertNull(store.interruptedSession())
    }

    @Test fun activeSessionPinsInFlightAndDoesNotEnterHistory() {
        val store = SharedPreferencesVoiceRepairHistoryStore(context)
        store.observe(active("a1"))
        store.observe(active("a2"))
        assertEquals("a2", store.interruptedSession()?.id)
        assertEquals(VoiceRepairState.TESTING_EXPECTED, store.interruptedSession()?.state)
        assertNull(store.latestTerminal())
        assertTrue(store.history().isEmpty())
    }

    @Test fun terminalSessionClearsTheInFlightMarker() {
        val store = SharedPreferencesVoiceRepairHistoryStore(context)
        store.observe(active("a1"))
        store.observe(terminal("t1", VoiceRepairState.FAILED))
        assertNull(store.interruptedSession())
        assertEquals("t1", store.latestTerminal()?.id)
    }

    @Test fun historyIsBoundedToCapacity() {
        val store = SharedPreferencesVoiceRepairHistoryStore(context, capacity = 2)
        repeat(5) { index -> store.observe(terminal("t$index", VoiceRepairState.FAILED)) }
        assertEquals(listOf("t3", "t4"), store.history().map { it.id })
    }

    @Test fun interruptedSessionSurvivesARestart() {
        SharedPreferencesVoiceRepairHistoryStore(context).observe(active("a1"))
        val reloaded = SharedPreferencesVoiceRepairHistoryStore(context)
        assertEquals("a1", reloaded.interruptedSession()?.id)
        assertNull(reloaded.latestTerminal())
    }

    @Test fun terminalHistorySurvivesARestart() {
        SharedPreferencesVoiceRepairHistoryStore(context).observe(terminal("t1", VoiceRepairState.SUCCESS))
        val reloaded = SharedPreferencesVoiceRepairHistoryStore(context)
        assertEquals("t1", reloaded.latestTerminal()?.id)
        assertEquals(1, reloaded.history().size)
    }

    @Test fun reloadedActiveSessionIsNeverMarkedRepaired() {
        SharedPreferencesVoiceRepairHistoryStore(context).observe(active("a1"))
        val reloaded = SharedPreferencesVoiceRepairHistoryStore(context)
        val restored = reloaded.interruptedSession()
        assertNotNull(restored)
        assertFalse(restored!!.state == VoiceRepairState.SUCCESS)
        assertFalse(restored.state == VoiceRepairState.HEALTHY)
    }

    @Test fun clearRemovesEverything() {
        val store = SharedPreferencesVoiceRepairHistoryStore(context)
        store.observe(active("a1"))
        store.observe(terminal("t1", VoiceRepairState.FAILED))
        store.clear()
        assertNull(store.latestTerminal())
        assertNull(store.interruptedSession())
        assertTrue(prefs.all.isEmpty())
    }

    @Test fun corruptedPayloadIsIgnoredGracefully() {
        prefs.edit().putString("history", "{not-json").putString("in_flight", "42").apply()
        val store = SharedPreferencesVoiceRepairHistoryStore(context)
        assertTrue(store.history().isEmpty())
        assertNull(store.latestTerminal())
        assertNull(store.interruptedSession())
    }
}