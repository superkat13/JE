package com.pineapple.sageos2.identity

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SageCoreJournalProtectionTest {
    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("sage_core_v2", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun store() = SharedPreferencesSageCoreStore(ctx)
    private fun prefs() = ctx.getSharedPreferences("sage_core_v2", Context.MODE_PRIVATE)

    @Test
    fun replaceFailsClosedOnUnresolvedJournalWithMismatchedHistory() {
        val core = store()
        core.replace(core.current().copy(notes = "v1"))
        val good = """{"revision":2,"twinIdentity":"Sage","notes":"v2"}"""
        val bad = """{"revision":2,"twinIdentity":"Sage","notes":"corrupt"}"""
        prefs().edit().putString("revision_2", good).putString("write_journal", bad).commit()
        try {
            core.replace(core.current().copy(notes = "v3"))
            fail("should fail closed")
        } catch (e: IllegalStateException) {
            // expected
        }
        assertEquals(good, prefs().getString("revision_2", null))
        assertEquals(bad, prefs().getString("write_journal", null))
        assertEquals(1L, store().current().revision)
    }

    @Test
    fun replaceSucceedsAfterRecoverableJournalRollsForward() {
        val core = store()
        core.replace(core.current().copy(notes = "v1"))
        val staged = """{"revision":2,"twinIdentity":"Sage","notes":"staged"}"""
        prefs().edit().putString("revision_2", staged).putString("write_journal", staged).commit()
        val reopened = store()
        assertEquals(2L, reopened.current().revision)
        val next = reopened.replace(reopened.current().copy(notes = "v3"))
        assertTrue(next.revision >= 3L)
    }
}
