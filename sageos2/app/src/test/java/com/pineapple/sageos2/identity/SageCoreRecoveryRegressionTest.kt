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
 * Failing-first Core recovery regression.
 *
 * A damaged current Core must NEVER cause valid revision_N history to be overwritten.
 * This test intentionally accepts either fail-closed writes or safely monotonic recovery.
 * It should fail on the inherited code because a broken current record silently decodes
 * to the revision-0 fallback; replace() then overwrites revision_1.
 *
 * Only Robolectric test preferences are altered. No user device data is accessed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
class SageCoreRecoveryRegressionTest {
    @Test fun corruptedCurrentCannotOverwriteValidHistoryWhenSaving() {
        val context: Context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("sage_core_v2", Context.MODE_PRIVATE)
            .edit().clear().commit()
        val core = SharedPreferencesSageCoreStore(context)
        val original = core.replace(core.current().copy(notes = "owner-approved original"))
        assertEquals(1L, original.revision)
        assertEquals(original, core.revision(1))

        val prefs = context.getSharedPreferences("sage_core_v2", Context.MODE_PRIVATE)
        val damaged = "{not-a-valid-json-record"
        prefs.edit().putString("current", damaged).commit()

        val attempt = runCatching {
            core.replace(core.current().copy(notes = "a later owner-approved update"))
        }

        // The original historical snapshot is immutable even if current is damaged.
        assertEquals("valid history is never recycled as revision 1", original, core.revision(1))
        if (attempt.isSuccess) {
            assertTrue("new saves must use a higher revision number",
                attempt.getOrThrow().revision > original.revision)
        } else {
            assertEquals("failed recovery must leave the damaged current record untouched",
                damaged, prefs.getString("current", null))
        }
    }
}
