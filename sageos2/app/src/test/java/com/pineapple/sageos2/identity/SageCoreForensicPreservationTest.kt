package com.pineapple.sageos2.identity

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Verifies forensic preservation of damaged Core records: distinct damaged values must never
 * overwrite each other, count must advance, preservation survives restart, and retry is idempotent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
class SageCoreForensicPreservationTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun corePrefs() = context.getSharedPreferences("sage_core_v2", Context.MODE_PRIVATE)
    private fun reset() {
        corePrefs().edit().clear().commit()
    }

    @Test fun twoDifferentDamagedRecordsPreserveSeparately() {
        reset()
        val core = SharedPreferencesSageCoreStore(context)
        core.replace(core.current().copy(notes = "base"))

        val damaged1 = "{damaged-first-owner-authored"
        corePrefs().edit().putString("current", damaged1).commit()
        // This save should preserve damaged1 as forensic_1, count becomes 1
        core.replace(core.current().copy(notes = "after-first"))

        assertEquals(1, corePrefs().getInt("forensic_damaged_current_count", -1))
        assertTrue(corePrefs().all.containsKey("forensic_damaged_current_1"))
        assertEquals(damaged1, corePrefs().getString("forensic_damaged_current_1", null))

        val damaged2 = "{damaged-second-distinct-record"
        corePrefs().edit().putString("current", damaged2).commit()
        // This save should preserve damaged2 as forensic_2 without overwriting forensic_1
        core.replace(core.current().copy(notes = "after-second"))

        assertEquals(2, corePrefs().getInt("forensic_damaged_current_count", -1))
        assertEquals(damaged1, corePrefs().getString("forensic_damaged_current_1", null))
        assertEquals(damaged2, corePrefs().getString("forensic_damaged_current_2", null))
    }

    @Test fun preservationSurvivesRestart() {
        reset()
        val core1 = SharedPreferencesSageCoreStore(context)
        core1.replace(core1.current().copy(notes = "base"))
        val damaged = "{damaged-across-restart"
        corePrefs().edit().putString("current", damaged).commit()
        core1.replace(core1.current().copy(notes = "save1"))

        val core2 = SharedPreferencesSageCoreStore(context)
        assertEquals(1, corePrefs().getInt("forensic_damaged_current_count", -1))
        assertEquals(damaged, corePrefs().getString("forensic_damaged_current_1", null))
    }

    @Test fun retryWithSameDamagedBytesDoesNotCreateDuplicate() {
        reset()
        val core = SharedPreferencesSageCoreStore(context)
        core.replace(core.current().copy(notes = "base"))
        val damaged = "{same-damaged-bytes"
        corePrefs().edit().putString("current", damaged).commit()
        core.replace(core.current().copy(notes = "save1"))
        val countAfter1 = corePrefs().getInt("forensic_damaged_current_count", -1)

        // Simulate retry/path where same damaged bytes remain; idempotent
        core.replace(core.current().copy(notes = "save2"))
        val countAfter2 = corePrefs().getInt("forensic_damaged_current_count", -1)
        assertEquals(countAfter1, countAfter2)
        assertEquals(damaged, corePrefs().getString("forensic_damaged_current_1", null))
    }

    @Test fun occupiedForensicKeySkipsOverwrite() {
        reset()
        val core = SharedPreferencesSageCoreStore(context)
        core.replace(core.current().copy(notes = "base"))
        val damaged1 = "{first}"
        corePrefs().edit().putString("current", damaged1).commit()
        core.replace(core.current().copy(notes = "s1"))

        // Artificially occupy forensic_damaged_current_2 to simulate counter skew
        corePrefs().edit().putString("forensic_damaged_current_2", "occupied").commit()
        val damaged2 = "{second}"
        corePrefs().edit().putString("current", damaged2).commit()
        core.replace(core.current().copy(notes = "s2"))

        // Should not overwrite forensic_damaged_current_2; should advance to 3
        assertEquals(3, corePrefs().getInt("forensic_damaged_current_count", -1))
        assertEquals(damaged1, corePrefs().getString("forensic_damaged_current_1", null))
        assertEquals(damaged2, corePrefs().getString("forensic_damaged_current_2", null))
        assertEquals("occupied", corePrefs().getString("forensic_damaged_current_2", null))
        assertEquals(damaged2, corePrefs().getString("forensic_damaged_current_3", null))
    }
}
