package com.pineapple.sageos2.continuity

import android.content.Context
import com.pineapple.sageos2.identity.SharedPreferencesSageCoreStore
import com.pineapple.sageos2.memory.SharedPreferencesTwinMemoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Interrupted owner-continuity imports must be safely repeatable: a crash between the core write and
 * the finalize marker can never double-apply or strand a partial import.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
class OwnerContinuityImportCrashConsistencyTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun prefs() = context.getSharedPreferences("sageos2_owner_continuity_imports", Context.MODE_PRIVATE)
    private fun reset() {
        prefs().edit().clear().commit()
        context.getSharedPreferences("sage_core_v2", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("sage_twin_memory_v2", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private val raw = """{"schema":"sage-owner-continuity-v1","source":"crash fixture",
        "core":{"notes":"recovered owner note"},
        "memories":[{"subject":"PROJECT","key":"recovery","value":"in progress"}]}"""

    private fun importer(): OwnerContinuityImporter =
        OwnerContinuityImporter(context, SharedPreferencesSageCoreStore(context), SharedPreferencesTwinMemoryStore(context))

    @Test fun pendingIntentFromInterruptedImportIsResumedExactlyOnce() {
        reset()
        val importer = importer()
        val digest = importer.digestOf(raw)
        // Emulate a crash after the durable intent marker but before any finalize marker.
        prefs().edit().putBoolean("pending_$digest", true).commit()
        assertTrue(importer.hasPending(digest))
        assertFalse(prefs().getBoolean("digest_$digest", false))

        val resumed = importer.import(raw, 100L)

        assertFalse(resumed.alreadyImported)
        assertFalse("pending marker cleared on finalize", importer.hasPending(digest))
        assertTrue(prefs().getBoolean("digest_$digest", false))

        val again = importer().import(raw, 200L)
        assertTrue(again.alreadyImported)
        assertEquals(1, SharedPreferencesTwinMemoryStore(context).snapshot().records.size)
    }

    @Test fun repeatedImportAppliesMemoriesOnce() {
        reset()
        val core = SharedPreferencesSageCoreStore(context)
        val memory = SharedPreferencesTwinMemoryStore(context)
        val importer = OwnerContinuityImporter(context, core, memory)

        importer.import(raw, 10L)
        val firstCount = memory.snapshot().records.size
        importer.import(raw, 20L)

        assertEquals(1, firstCount)
        assertEquals("repeat import must not duplicate memories", firstCount, memory.snapshot().records.size)
    }

    @Test fun pendingCountReflectsUnfinishedImports() {
        reset()
        val importer = importer()
        prefs().edit().putBoolean("pending_abc", true).putBoolean("pending_def", true).commit()
        assertEquals(2, importer.pendingImportCount())
    }

    @Test fun malformedPackageLeavesNoPendingMarker() {
        reset()
        val importer = importer()
        val bad = """{"schema":"sage-owner-continuity-v1","source":"bad","memories":[{"key":"k","value":"v","confidence":"NaN"}]}"""
        runCatching { importer.import(bad) }
        assertEquals(0, importer.pendingImportCount())
        assertEquals(0L, SharedPreferencesSageCoreStore(context).current().revision)
    }
}
