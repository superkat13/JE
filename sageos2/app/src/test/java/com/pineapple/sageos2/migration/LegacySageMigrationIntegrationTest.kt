package com.pineapple.sageos2.migration

import android.content.Context
import com.pineapple.sageos2.apps.OwnerAppRecord
import com.pineapple.sageos2.apps.SharedPreferencesOwnerAppRegistry
import com.pineapple.sageos2.continuity.SharedPreferencesTaskContinuityStore
import com.pineapple.sageos2.identity.SharedPreferencesSageCoreStore
import com.pineapple.sageos2.memory.SharedPreferencesTwinMemoryStore
import com.pineapple.sageos2.speech.SharedPreferencesWakeProfileStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
class LegacySageMigrationIntegrationTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun migration() = LegacySageMigration(context,
        SharedPreferencesSageCoreStore(context), SharedPreferencesTwinMemoryStore(context),
        SharedPreferencesOwnerAppRegistry(context), SharedPreferencesWakeProfileStore(context),
        SharedPreferencesTaskContinuityStore(context))
    private fun legacyApp() {
        context.getSharedPreferences("sage_owner_apps", Context.MODE_PRIVATE).edit().putString("entries",
            """[{"package":"test.owner.app","label":"Owner app","purposes":"legacy purpose","launch_steps":"step one\n\nstep two"}]""").commit()
    }
    @Test fun repeatedStartupDoesNotDuplicateOwnerInstructionsOrRevisions() {
        val apps = SharedPreferencesOwnerAppRegistry(context)
        apps.upsert(OwnerAppRecord("test.owner.app", "My app", purpose = "owner purpose", startupProcedure = "owner step"))
        legacyApp()
        assertEquals(1, migration().runIfNeeded(1).ownerAppsImported)
        val first = apps.snapshot()
        repeat(3) { assertEquals(0, migration().runIfNeeded(2L + it).ownerAppsImported) }
        assertEquals(first, SharedPreferencesOwnerAppRegistry(context).snapshot())
        assertEquals("owner purpose\n\nlegacy purpose", first.apps.single().purpose)
        assertEquals("owner step\n\nstep one\n\nstep two", first.apps.single().startupProcedure)
    }
    @Test fun malformedLegacyTypeIsReportedWithoutBlockingOtherImportsOrErasingSource() {
        val legacy = context.getSharedPreferences("sage_core", Context.MODE_PRIVATE)
        legacy.edit().putInt("owner_instructions", 42).commit()
        legacyApp()
        val report = migration().runIfNeeded(1)
        assertFalse(report.completed)
        assertTrue(report.errors.any { it.startsWith("source_core:") })
        assertEquals(1, report.ownerAppsImported)
        assertEquals(42, legacy.getInt("owner_instructions", 0))
        assertEquals(0L, SharedPreferencesSageCoreStore(context).current().revision)
    }
    @Test fun malformedCompletionMarkerDoesNotCrashOrFalselyReportCompletion() {
        val marker = context.getSharedPreferences("sageos2_legacy_migration", Context.MODE_PRIVATE)
        marker.edit().putString("sage_1_33_3_complete", "damaged").commit()
        val report = migration().runIfNeeded(1)
        assertFalse(report.completed)
        assertTrue(report.errors.any { it.startsWith("marker:") })
        assertTrue(report.summary().startsWith("legacy migration incomplete"))
        assertEquals("damaged", marker.getString("sage_1_33_3_complete", null))
    }
}
