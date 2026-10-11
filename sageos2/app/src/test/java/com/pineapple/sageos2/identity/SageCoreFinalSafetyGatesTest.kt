package com.pineapple.sageos2.identity

import android.content.Context
import com.pineapple.sageos2.continuity.OwnerContinuityImporter
import com.pineapple.sageos2.memory.SharedPreferencesTwinMemoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Independent pre-install safety cases missing from 680-test green build (6948fe4).
 * These scenarios are expected RED until Core recovery/import paths preserve their invariants.
 * Operates on Robolectric test preferences only; no real owner data or process dump.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
class SageCoreFinalSafetyGatesTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun corePrefs() = context.getSharedPreferences("sage_core_v2", Context.MODE_PRIVATE)
    private fun importPrefs() = context.getSharedPreferences("sageos2_owner_continuity_imports", Context.MODE_PRIVATE)
    private fun reset() {
        corePrefs().edit().clear().commit()
        importPrefs().edit().clear().commit()
        context.getSharedPreferences("sage_twin_memory_v2", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun matchingJournalRollForwardCannotDiscardDamagedCurrentRawData() {
        reset()
        val store = SharedPreferencesSageCoreStore(context)
        store.replace(store.current().copy(notes = "genuine old revision"))
        val invalidCurrent = "{damaged-private-owner-current-before-power-loss"
        corePrefs().edit().putString("current", invalidCurrent).commit()
        val validStaged = """{"revision":2,"twinIdentity":"Sage","notes":"staged from interrupted write"}"""
        corePrefs().edit().putString("revision_2", validStaged)
            .putString("write_journal", validStaged).commit()

        // Constructor reconciliation is allowed to roll forward this authenticated, matching
        // journal, but only if the damaged prior current is durably preserved before promotion.
        val reopened = SharedPreferencesSageCoreStore(context)
        val all = corePrefs().all
        assertTrue("damaged pre-recovery current must survive even if journal advances",
            all.values.any { it is String && it == invalidCurrent })
        assertEquals(validStaged, reopened.historyRaw(2))
        assertEquals(2L, reopened.current().revision)
    }

    @Test fun retryAfterCoreWriteButBeforeImportMarkerCannotAppendNotesTwice() {
        reset()
        val core = SharedPreferencesSageCoreStore(context)
        core.replace(core.current().copy(notes = "existing owner's earlier note"))
        val raw = """{"schema":"sage-owner-continuity-v1","source":"owner-approved fixture",
            "core":{"notes":"recovered owner note"},
            "memories":[{"subject":"PROJECT","key":"core-project","value":"in progress"}]}"""
        val importer = OwnerContinuityImporter(context, core, SharedPreferencesTwinMemoryStore(context))
        val digest = importer.digestOf(raw)
        importer.import(raw, 10L)
        val afterFirstImport = core.current()
        assertTrue(afterFirstImport.notes.contains("existing owner's earlier note"))
        assertTrue(afterFirstImport.notes.contains("recovered owner note"))

        // Reproduce a crash just before the finalized digest marker became durable, after
        // the Core merge and memory write already succeeded. No user data is overwritten.
        importPrefs().edit().remove("digest_$digest").putBoolean("pending_$digest", true).commit()
        OwnerContinuityImporter(context,
            SharedPreferencesSageCoreStore(context),
            SharedPreferencesTwinMemoryStore(context)
        ).import(raw, 20L)

        val afterRetry = SharedPreferencesSageCoreStore(context).current()
        assertEquals("retry must not append a second copy of recovered note", 1,
            Regex("recovered owner note").findAll(afterRetry.notes).count())
        assertEquals("repeated partial import should not create an extra Core revision",
            afterFirstImport.revision, afterRetry.revision)
        assertEquals(1, SharedPreferencesTwinMemoryStore(context).snapshot().records.size)
    }
}
