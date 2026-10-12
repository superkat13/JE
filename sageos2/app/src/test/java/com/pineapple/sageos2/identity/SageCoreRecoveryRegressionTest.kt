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
 * Failing-first Core recovery regression and anti-data-loss contract.
 *
 * A damaged current Core must NEVER cause valid revision_N history to be overwritten, and an
 * interrupted write must be recoverable without losing any owner-authored bytes.
 *
 * Only Robolectric test preferences are altered. No user device data is accessed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
class SageCoreRecoveryRegressionTest {
    private fun prefs(context: Context) = context.getSharedPreferences("sage_core_v2", Context.MODE_PRIVATE)
    private fun store(context: Context) = SharedPreferencesSageCoreStore(context)
    private fun reset(context: Context) { prefs(context).edit().clear().commit() }

    @Test fun corruptedCurrentCannotOverwriteValidHistoryWhenSaving() {
        val context: Context = RuntimeEnvironment.getApplication()
        reset(context)
        val core = store(context)
        val original = core.replace(core.current().copy(notes = "owner-approved original"))
        assertEquals(1L, original.revision)
        assertEquals(original, core.revision(1))

        val damaged = "{not-a-valid-json-record"
        prefs(context).edit().putString("current", damaged).commit()

        val attempt = runCatching {
            core.replace(core.current().copy(notes = "a later owner-approved update"))
        }

        assertEquals("valid history is never recycled as revision 1", original, core.revision(1))
        if (attempt.isSuccess) {
            assertTrue("new saves must use a higher revision number",
                attempt.getOrThrow().revision > original.revision)
        } else {
            assertEquals("failed recovery must leave the damaged current record untouched",
                damaged, prefs(context).getString("current", null))
        }
    }

    @Test fun missingCurrentWithValidHistoryIsRecoverableButNeverAutoPromoted() {
        val context: Context = RuntimeEnvironment.getApplication()
        reset(context)
        val core = store(context)
        val original = core.replace(core.current().copy(notes = "kept history"))
        val rawHistory = core.historyRaw(1)

        prefs(context).edit().remove("current").commit()

        assertEquals(SageCoreCurrentStatus.MISSING, core.currentStatus())
        assertEquals(1L, core.newestRecoverableRevision())
        assertEquals("readers still get a safe fallback, not a silent restore", 0L, core.current().revision)
        assertEquals("history bytes unchanged", rawHistory, core.historyRaw(1))
        assertEquals(original, core.revision(1))
    }

    @Test fun damagedCurrentIsDistinguishedFromTrulyMissing() {
        val context: Context = RuntimeEnvironment.getApplication()
        reset(context)
        val core = store(context)
        core.replace(core.current().copy(notes = "exists"))

        prefs(context).edit().putString("current", "}{ broken").commit()
        assertEquals(SageCoreCurrentStatus.INVALID, core.currentStatus())

        prefs(context).edit().putInt("current", 7).commit()
        assertEquals("wrongly-typed current is damaged, not missing", SageCoreCurrentStatus.INVALID, core.currentStatus())

        prefs(context).edit().remove("current").commit()
        assertEquals(SageCoreCurrentStatus.MISSING, core.currentStatus())
    }

    @Test fun corruptSnapshotKeyIsSkippedWhenChoosingNewRevision() {
        val context: Context = RuntimeEnvironment.getApplication()
        reset(context)
        val core = store(context)
        core.replace(core.current().copy(notes = "one"))
        core.replace(core.current().copy(notes = "two"))
        core.replace(core.current().copy(notes = "three"))   // revision_3
        val corrupt = "{orphan-corrupt"
        prefs(context).edit().putString("revision_4", corrupt).commit()

        val next = core.replace(core.current().copy(notes = "four"))

        assertTrue("never reuse an occupied revision key", next.revision > 3L)
        assertTrue("must not overwrite the orphan revision key", next.revision != 4L)
        assertEquals("orphan bytes preserved", corrupt, prefs(context).getString("revision_4", null))
    }

    @Test fun newestValidSnapshotIsChosenAsRecoveryCandidate() {
        val context: Context = RuntimeEnvironment.getApplication()
        reset(context)
        val core = store(context)
        core.replace(core.current().copy(notes = "v1"))
        core.replace(core.current().copy(notes = "v2"))
        prefs(context).edit().putString("revision_9", "{broken").commit()
        prefs(context).edit().remove("current").commit()

        assertEquals(listOf(1L, 2L), core.validRevisions())
        assertEquals(2L, core.newestRecoverableRevision())
    }

    @Test fun previewWritesNothing() {
        val context: Context = RuntimeEnvironment.getApplication()
        reset(context)
        val core = store(context)
        core.replace(core.current().copy(notes = "original"))
        val before = LinkedHashMap(prefs(context).all)

        val preview = core.previewRestore(1)

        assertNotNull(preview)
        assertEquals(1L, preview!!.sourceRevision)
        assertEquals("preview must target a new revision", 2L, preview.targetRevision)
        assertEquals(before, LinkedHashMap(prefs(context).all))
    }

    @Test fun ownerApprovedRestoreCreatesHigherRevisionAndPreservesSourceBytes() {
        val context: Context = RuntimeEnvironment.getApplication()
        reset(context)
        val core = store(context)
        core.replace(core.current().copy(notes = "original"))
        val sourceBytes = core.historyRaw(1)!!
        core.replace(core.current().copy(notes = "second"))

        val restored = core.restore(1)

        assertNotNull(restored)
        assertEquals("original", restored!!.notes)
        assertEquals(3L, restored.revision)
        assertEquals("3 is the live current", 3L, core.current().revision)
        assertEquals("source snapshot byte-for-byte intact", sourceBytes, core.historyRaw(1))
    }

    @Test fun restoreSurvivesStoreRecreation() {
        val context: Context = RuntimeEnvironment.getApplication()
        reset(context)
        store(context).replace(store(context).current().copy(notes = "durable original"))
        store(context).replace(store(context).current().copy(notes = "later"))
        store(context).restore(1)

        val reopened = SharedPreferencesSageCoreStore(context)
        assertEquals(3L, reopened.current().revision)
        assertEquals("durable original", reopened.current().notes)
    }

    @Test fun tornWriteIsRolledForwardOnReopenAndIsIdempotent() {
        val context: Context = RuntimeEnvironment.getApplication()
        reset(context)
        val core = store(context)
        core.replace(core.current().copy(notes = "committed"))
        // Simulate power loss after phase one: history + journal written, current not yet promoted.
        val staged = """{"revision":2,"twinIdentity":"staged","notes":"staged after crash"}"""
        prefs(context).edit().putString("revision_2", staged).putString("write_journal", staged).commit()

        val reopened = SharedPreferencesSageCoreStore(context)
        assertEquals("journal rolled forward", 2L, reopened.current().revision)
        assertEquals("staged after crash", reopened.current().notes)
        assertNull("journal cleared", prefs(context).getString("write_journal", null))
        assertFalse("second recovery is a no-op", reopened.recoverPendingWrite())
        assertEquals(2L, reopened.current().revision)
    }

    @Test fun everyHistoricalRevisionBytesArePreservedThroughRecovery() {
        val context: Context = RuntimeEnvironment.getApplication()
        reset(context)
        val core = store(context)
        core.replace(core.current().copy(notes = "a"))
        core.replace(core.current().copy(notes = "b"))
        core.replace(core.current().copy(notes = "c"))
        val snapshot = mapOf(1L to core.historyRaw(1), 2L to core.historyRaw(2), 3L to core.historyRaw(3))

        prefs(context).edit().putString("current", "{damaged").commit()
        core.replace(core.current().copy(notes = "d"))
        core.restore(1)

        snapshot.forEach { (revision, raw) ->
            assertEquals("revision_$revision preserved", raw, core.historyRaw(revision))
        }
    }

    @Test fun safeReportNeverContainsPrivateCoreText() {
        val context: Context = RuntimeEnvironment.getApplication()
        reset(context)
        val marker = "private-owner-secret-phrase"
        store(context).replace(store(context).current().copy(notes = marker))
        val report = SageCoreHealthReader(context, store(context)).assess().safeReport()

        assertTrue(report.contains("Sage Core health"))
        assertFalse("safe diagnostics must never leak Core text", report.contains(marker))
    }
}
