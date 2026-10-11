package com.pineapple.sageos2.identity

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SageCoreJournalRestartTest {
    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("sage_core", Context.MODE_PRIVATE).edit().clear().commit()
        ctx.getSharedPreferences("sage_core_v2", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun prefsV2() = ctx.getSharedPreferences("sage_core_v2", Context.MODE_PRIVATE)

    @Test
    fun restartPreservesUnresolvedInconsistentJournal() {
        val core = SharedPreferencesSageCoreStore(ctx)
        core.replace(core.current().copy(notes = "v1"))
        val good = """{"revision":2,"twinIdentity":"Sage","notes":"v2"}"""
        val bad = """{"revision":2,"twinIdentity":"Sage","notes":"corrupt"}"""
        prefsV2().edit().putString("revision_2", good).putString("write_journal", bad).commit()
        // restart
        val core2 = SharedPreferencesSageCoreStore(ctx)
        assertEquals(1L, core2.current().revision)
        assertEquals(good, prefsV2().getString("revision_2", null))
        assertEquals(bad, prefsV2().getString("write_journal", null))
    }

    @Test
    fun multipleConsecutiveRecoveriesRemainStable() {
        val core = SharedPreferencesSageCoreStore(ctx)
        core.replace(core.current().copy(notes = "v1"))
        // staged
        val staged = """{"revision":2,"twinIdentity":"Sage","notes":"staged"}"""
        prefsV2().edit().putString("revision_2", staged).putString("write_journal", staged).commit()
        val c1 = SharedPreferencesSageCoreStore(ctx)
        assertEquals(2L, c1.current().revision)
        val c2 = SharedPreferencesSageCoreStore(ctx)
        assertEquals(2L, c2.current().revision)
        val c3 = SharedPreferencesSageCoreStore(ctx)
        assertEquals(2L, c3.current().revision)
    }

    @Test
    fun corruptedJournalPreventsReplace() {
        val core = SharedPreferencesSageCoreStore(ctx)
        core.replace(core.current().copy(notes = "v1"))
        prefsV2().edit().putString("write_journal", "{not json}").commit()
        try {
            core.replace(core.current().copy(notes = "v2"))
            fail("should fail closed")
        } catch (e: IllegalStateException) {
        }
    }

    @Test
    fun revisionCollisionHistoryIntact() {
        val core = SharedPreferencesSageCoreStore(ctx)
        core.replace(core.current().copy(notes = "v1"))
        // create inconsistent journal at rev2
        val good = """{"revision":2,"twinIdentity":"Sage","notes":"v2"}"""
        val bad = """{"revision":2,"twinIdentity":"Sage","notes":"bad"}"""
        prefsV2().edit().putString("revision_2", good).putString("write_journal", bad).commit()
        try {
            core.replace(core.current().copy(notes = "v3"))
            fail("should fail closed")
        } catch (e: IllegalStateException) {
        }
        // history not overwritten
        assertEquals(good, prefsV2().getString("revision_2", null))
    }
}
