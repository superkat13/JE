package com.pineapple.sageos2.identity

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SageCoreJournalTypeTest {
    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("sage_core_v2", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun prefs() = ctx.getSharedPreferences("sage_core_v2", Context.MODE_PRIVATE)
    private fun store() = SharedPreferencesSageCoreStore(ctx)

    @Test
    fun wrongTypeJournalFailsClosedAndPreserves() {
        val core = store()
        core.replace(core.current().copy(notes = "v1"))
        prefs().edit().putInt("write_journal", 123).commit()
        try {
            core.replace(core.current().copy(notes = "v2"))
            fail("should fail")
        } catch (e: IllegalStateException) {
        }
        assertTrue(prefs().contains("write_journal"))
        assertEquals(123, prefs().getInt("write_journal", 0))
    }
}
