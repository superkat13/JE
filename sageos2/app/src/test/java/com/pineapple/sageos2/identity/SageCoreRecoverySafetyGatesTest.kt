package com.pineapple.sageos2.identity

import android.content.Context
import com.pineapple.sageos2.apps.SharedPreferencesOwnerAppRegistry
import com.pineapple.sageos2.continuity.SharedPreferencesTaskContinuityStore
import com.pineapple.sageos2.memory.SharedPreferencesTwinMemoryStore
import com.pineapple.sageos2.migration.LegacySageMigration
import com.pineapple.sageos2.speech.SharedPreferencesWakeProfileStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Independent review safety gates for PR #75.
 * Intentionally red against 02ca24f. No real owner data is accessed.
 *
 * These tests describe fail-closed preservation requirements. They permit a later implementation
 * to either reject an unapproved write or preserve the original raw current in a durable
 * forensic backup before promoting a specifically approved new revision.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
class SageCoreRecoverySafetyGatesTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun corePrefs() = context.getSharedPreferences("sage_core_v2", Context.MODE_PRIVATE)
    private fun legacyPrefs() = context.getSharedPreferences("sage_core", Context.MODE_PRIVATE)

    private fun reset() {
        corePrefs().edit().clear().commit()
        legacyPrefs().edit().clear().commit()
        context.getSharedPreferences("sageos2_legacy_migration", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun normalSaveCannotDestroyUnreadableCurrentWithoutPreservingRawBytes() {
        reset()
        val core = SharedPreferencesSageCoreStore(context)
        core.replace(core.current().copy(notes = "original historical core"))
        val damaged = "{damaged-but-owner-authored-current"
        corePrefs().edit().putString("current", damaged).commit()

        // This emulates the old Core editor saving values from a revision-0 fallback.
        val attempted = runCatching {
            core.replace(core.current().copy(notes = "ordinary editor input"))
        }

        if (attempted.isFailure) {
            assertEquals("failed write must not mutate damaged current",
                damaged, corePrefs().getString("current", null))
        } else {
            // If a real owner-approved recovery is eventually allowed, the entire old raw current
            // must remain recoverable, even if malformed, not just revision_1.
            assertTrue("damaged raw current bytes must survive any successful replacement",
                corePrefs().all.values.any { value -> value is String && value == damaged })
        }
    }

    @Test fun aJournalMustMatchTheImmutableHistorySnapshotBeforeRollForward() {
        reset()
        val core = SharedPreferencesSageCoreStore(context)
        core.replace(core.current().copy(notes = "verified current v1"))
        val goodSnapshot = """{"revision":2,"twinIdentity":"Sage","notes":"legitimate staged v2"}"""
        val mismatchingJournal = """{"revision":2,"twinIdentity":"Sage","notes":"different unsaved bytes"}"""
        corePrefs().edit().putString("revision_2", goodSnapshot)
            .putString("write_journal", mismatchingJournal).commit()

        val reopened = SharedPreferencesSageCoreStore(context)
        assertEquals("a foreign or damaged journal cannot become the live Core",
            1L, reopened.current().revision)
        assertEquals("immutable history must be preserved verbatim",
            goodSnapshot, corePrefs().getString("revision_2", null))
    }

    @Test fun missingRequiredCoreIdentityMustNotBeMistakenForAValidatedCurrent() {
        reset()
        corePrefs().edit().putString("current", "{}").commit()
        val core = SharedPreferencesSageCoreStore(context)
        assertEquals("an empty object is not an authentic saved Core snapshot",
            SageCoreCurrentStatus.INVALID, core.currentStatus())
        assertEquals(0L, core.current().revision)
    }

    @Test fun legacyOwnerCoreIsReviewOnlyWhenNoSavedCurrentExists() {
        reset()
        val ownerText = "legacy owner instructions requiring owner review"
        legacyPrefs().edit().putString("owner_instructions", ownerText).commit()
        val core = SharedPreferencesSageCoreStore(context)
        val migration = LegacySageMigration(context,
            core,
            SharedPreferencesTwinMemoryStore(context),
            SharedPreferencesOwnerAppRegistry(context),
            SharedPreferencesWakeProfileStore(context),
            SharedPreferencesTaskContinuityStore(context)
        )

        migration.runIfNeeded(1L)

        assertEquals("legacy content must be offered for review, not silently promoted",
            SageCoreCurrentStatus.MISSING, core.currentStatus())
        assertEquals(0L, core.current().revision)
        assertEquals("owner's original legacy source must remain",
            ownerText, legacyPrefs().getString("owner_instructions", null))
    }
}
