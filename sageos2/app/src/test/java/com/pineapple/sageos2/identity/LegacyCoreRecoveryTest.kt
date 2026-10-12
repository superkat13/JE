package com.pineapple.sageos2.identity

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
class LegacyCoreRecoveryTest {
    private fun context(): Context = RuntimeEnvironment.getApplication()
    private fun legacyPrefs(context: Context) = context.getSharedPreferences("sage_core", Context.MODE_PRIVATE)
    private fun corePrefs(context: Context) = context.getSharedPreferences("sage_core_v2", Context.MODE_PRIVATE)
    private fun clear(context: Context) {
        legacyPrefs(context).edit().clear().commit()
        corePrefs(context).edit().clear().commit()
    }

    @Test fun previousOnlyCandidateIsOfferedButNeverAutoApplied() {
        val context = context(); clear(context)
        legacyPrefs(context).edit().putString("previous_owner_instructions", "legacy previous core").commit()
        val store = SharedPreferencesSageCoreStore(context)
        val recovery = LegacyCoreRecovery(store, LegacyCoreSource(context))

        val candidate = recovery.candidate()
        assertNotNull(candidate)
        assertEquals("previous_owner_instructions", candidate!!.sourceKey)
        assertEquals("candidate is never written without owner approval", 0L, store.current().revision)
        assertNull(store.revision(1))

        val preview = recovery.preview()
        assertNotNull(preview)
        assertEquals(0L, store.current().revision)

        val restored = recovery.restore()
        assertNotNull(restored)
        assertTrue(restored!!.revision >= 1L)
        assertTrue(store.current().notes.contains(LegacyCoreSource.IMPORT_MARKER))
        assertTrue(store.current().notes.contains("legacy previous core"))
        assertEquals("legacy source bytes untouched", "legacy previous core",
            legacyPrefs(context).getString("previous_owner_instructions", null))
    }

    @Test fun currentLegacyIsPreferredOverPrevious() {
        val context = context(); clear(context)
        legacyPrefs(context).edit()
            .putString("owner_instructions", "current legacy")
            .putString("previous_owner_instructions", "older legacy")
            .commit()
        val recovery = LegacyCoreRecovery(SharedPreferencesSageCoreStore(context), LegacyCoreSource(context))

        assertEquals("owner_instructions", recovery.candidate()!!.sourceKey)
    }

    @Test fun recoveryPreservesExistingValidCoreAndHistory() {
        val context = context(); clear(context)
        val store = SharedPreferencesSageCoreStore(context)
        val original = store.replace(store.current().copy(notes = "existing owner note"))
        legacyPrefs(context).edit().putString("owner_instructions", "legacy authored core").commit()

        val restored = LegacyCoreRecovery(store, LegacyCoreSource(context)).restore()

        assertNotNull(restored)
        assertTrue(store.current().notes.contains("existing owner note"))
        assertTrue(store.current().notes.contains("legacy authored core"))
        assertEquals("original revision preserved", original, store.revision(1))
    }
}
