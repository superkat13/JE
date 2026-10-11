package com.pineapple.sageos2.identity

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
class SageCoreHealthReaderTest {
    private fun context(): Context = RuntimeEnvironment.getApplication()
    private fun corePrefs(context: Context) = context.getSharedPreferences("sage_core_v2", Context.MODE_PRIVATE)
    private fun legacyPrefs(context: Context) = context.getSharedPreferences("sage_core", Context.MODE_PRIVATE)
    private fun importPrefs(context: Context) = context.getSharedPreferences("sageos2_owner_continuity_imports", Context.MODE_PRIVATE)

    private fun clear(context: Context) {
        corePrefs(context).edit().clear().commit()
        legacyPrefs(context).edit().clear().commit()
        importPrefs(context).edit().clear().commit()
    }

    @Test fun noSourceReportsNoCurrent() {
        val context = context(); clear(context)
        val health = SageCoreHealthReader(context).assess()

        assertEquals(SageCoreStatus.NO_CURRENT, health.primaryStatus())
        assertFalse(health.valid)
        assertEquals(0, health.validRevisions.size)
        assertEquals(null, health.newestRecoverableRevision)
    }

    @Test fun validCurrentWins() {
        val context = context(); clear(context)
        val core = SharedPreferencesSageCoreStore(context)
        core.replace(core.current().copy(notes = "healthy"))
        val health = SageCoreHealthReader(context).assess()

        assertEquals(SageCoreStatus.VALID_CURRENT, health.primaryStatus())
        assertTrue(health.valid)
        assertEquals(1L, health.currentRevision)
    }

    @Test fun missingCurrentWithHistoryIsRecoverableRevisionNotMissing() {
        val context = context(); clear(context)
        val core = SharedPreferencesSageCoreStore(context)
        core.replace(core.current().copy(notes = "v1"))
        corePrefs(context).edit().remove("current").commit()

        val health = SageCoreHealthReader(context).assess()
        assertEquals(SageCoreStatus.RECOVERABLE_REVISION, health.primaryStatus())
        assertEquals(1L, health.newestRecoverableRevision)
        assertTrue(health.issueCodes.contains("history_recoverable"))
    }

    @Test fun corruptCurrentWithHistoryIsRecoverableAndFlaggedDamaged() {
        val context = context(); clear(context)
        val core = SharedPreferencesSageCoreStore(context)
        core.replace(core.current().copy(notes = "v1"))
        corePrefs(context).edit().putString("current", "{broken").commit()

        val health = SageCoreHealthReader(context).assess()
        assertEquals(SageCoreCurrentStatus.INVALID, health.currentStatus)
        assertEquals(SageCoreStatus.RECOVERABLE_REVISION, health.primaryStatus())
        assertTrue(health.issueCodes.contains("current_record_damaged"))
    }

    @Test fun previousOnlyLegacyIsSurfacedWithoutMutation() {
        val context = context(); clear(context)
        legacyPrefs(context).edit().putString("previous_owner_instructions", "legacy previous core").commit()

        val health = SageCoreHealthReader(context).assess()
        assertEquals(SageCoreStatus.LEGACY_PREVIOUS, health.primaryStatus())
        assertFalse(health.legacyCurrentAvailable)
        assertTrue(health.legacyPreviousAvailable)
        assertEquals("legacy previous core", legacyPrefs(context).getString("previous_owner_instructions", null))
    }

    @Test fun ownerImportMarkerMakesImportAvailable() {
        val context = context(); clear(context)
        importPrefs(context).edit().putBoolean("digest_abc123", true).commit()

        val health = SageCoreHealthReader(context).assess()
        assertEquals(SageCoreStatus.OWNER_IMPORT_AVAILABLE, health.primaryStatus())
        assertTrue(health.ownerImportAvailable)
    }

    @Test fun wronglyTypedLegacyKeyIsReportedWithoutCrashAndPreservesBytes() {
        val context = context(); clear(context)
        legacyPrefs(context).edit().putInt("owner_instructions", 42).commit()

        val health = SageCoreHealthReader(context).assess()
        assertTrue(health.issueCodes.any { it.startsWith("legacy_core_type_invalid") })
        assertEquals("legacy bytes untouched", 42, legacyPrefs(context).getInt("owner_instructions", 0))
        assertEquals(SageCoreStatus.NO_CURRENT, health.primaryStatus())
    }
}
