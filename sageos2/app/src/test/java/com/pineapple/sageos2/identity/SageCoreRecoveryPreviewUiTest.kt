package com.pineapple.sageos2.identity

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Validates the exact private text the owner reviews in the restoration dialog (the same
 * [SageCorePreviewText.render] output the Activity displays): real owner-authored before/after values,
 * never a hash, and confirmations bound to the previewed candidate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
class SageCoreRecoveryPreviewUiTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun corePrefs() = context.getSharedPreferences("sage_core_v2", Context.MODE_PRIVATE)
    private fun legacyPrefs() = context.getSharedPreferences("sage_core", Context.MODE_PRIVATE)
    private fun reset() {
        corePrefs().edit().clear().commit()
        legacyPrefs().edit().clear().commit()
    }

    @Test fun legacyReviewShowsOwnerAuthoredTextNotAHash() {
        reset()
        val ownerText = "owner wrote: I am Kat's twin and I keep our promises"
        legacyPrefs().edit().putString("owner_instructions", ownerText).commit()
        val core = SharedPreferencesSageCoreStore(context)
        val recovery = LegacyCoreRecovery(core, LegacyCoreSource(context))

        val candidate = recovery.previewCandidate()
        assertNotNull(candidate)
        val text = SageCorePreviewText.render(candidate!!)

        assertTrue("the actual owner text must be visible for approval", text.contains(ownerText))
        assertTrue(text.contains("Before:"))
        assertTrue(text.contains("After:"))
        assertTrue(text.contains(candidate.after.notes))
        assertFalse("dialog text must not fall back to a bare digest", text == "digest " + candidate.sourceDigest.take(12))
    }

    @Test fun snapshotReviewShowsRestoredIdentityBeforeAndAfter() {
        reset()
        val core = SharedPreferencesSageCoreStore(context)
        core.replace(core.current().copy(twinIdentity = "older saved identity", notes = "older saved note"))
        core.replace(core.current().copy(twinIdentity = "current identity", notes = "current note"))

        val candidate = core.candidateForRestore(1)!!
        val text = SageCorePreviewText.render(candidate)

        assertTrue(text.contains("older saved identity"))
        assertTrue(text.contains("current identity"))
        assertTrue(text.contains("current note"))
    }

    @Test fun staleLegacyConfirmationWritesNothingAndKeepsSource() {
        reset()
        val ownerText = "legacy owner core under review"
        legacyPrefs().edit().putString("owner_instructions", ownerText).commit()
        val core = SharedPreferencesSageCoreStore(context)
        val recovery = LegacyCoreRecovery(core, LegacyCoreSource(context))
        val reviewed = recovery.previewCandidate()!!

        // A different write lands after the preview, moving the target revision.
        core.replace(core.current().copy(notes = "intervening owner save"))

        val applied = recovery.restore(reviewed.sourceDigest, reviewed.targetRevision)

        assertNull("stale legacy approval must not write", applied)
        assertEquals("legacy source untouched", ownerText, legacyPrefs().getString("owner_instructions", null))
    }
}
