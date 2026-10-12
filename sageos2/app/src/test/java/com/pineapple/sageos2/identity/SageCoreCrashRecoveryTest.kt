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
 * Crash-recovery and unreadable-current preservation guarantees for Sage Core. No real owner data is
 * touched; only Robolectric test preferences are used.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
class SageCoreCrashRecoveryTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun prefs() = context.getSharedPreferences("sage_core_v2", Context.MODE_PRIVATE)
    private fun store() = SharedPreferencesSageCoreStore(context)
    private fun reset() = prefs().edit().clear().commit()

    @Test fun wronglyTypedCurrentIsPreservedBeforeAnyPromotion() {
        reset()
        val core = store()
        core.replace(core.current().copy(notes = "base"))
        prefs().edit().putInt("current", 7).commit()

        val attempt = runCatching { core.replace(core.current().copy(notes = "later")) }

        assertTrue("replacement must not throw with a wrong-type current", attempt.isSuccess)
        assertTrue("wrongly-typed damaged current must survive durably",
            prefs().all.values.any { it == 7 })
    }

    @Test fun damagedStringCurrentSurvivesEvenThoughItIsUnreadable() {
        reset()
        val core = store()
        core.replace(core.current().copy(notes = "base"))
        val damaged = "{unreadable-owner-authorship"
        prefs().edit().putString("current", damaged).commit()

        core.replace(core.current().copy(notes = "later"))

        assertTrue(prefs().all.values.any { it is String && it == damaged })
    }

    @Test fun inconsistentJournalFailsClosedAndPreservesBothEntries() {
        reset()
        val core = store()
        core.replace(core.current().copy(notes = "verified v1"))
        val good = """{"revision":2,"twinIdentity":"Sage","notes":"legit v2"}"""
        val foreign = """{"revision":2,"twinIdentity":"Sage","notes":"foreign bytes"}"""
        prefs().edit().putString("revision_2", good).putString("write_journal", foreign).commit()

        val reopened = store()

        assertEquals("journal must not override immutable history", 1L, reopened.current().revision)
        assertEquals("history preserved verbatim", good, prefs().getString("revision_2", null))
        assertEquals("mismatching journal preserved for diagnosis", foreign, prefs().getString("write_journal", null))
    }

    @Test fun tornWriteRollsForwardAndNeverCollidesOnTheNextSave() {
        reset()
        val core = store()
        core.replace(core.current().copy(notes = "v1"))
        val staged = """{"revision":2,"twinIdentity":"Sage","notes":"staged v2"}"""
        prefs().edit().putString("revision_2", staged).putString("write_journal", staged).commit()

        val reopened = store()
        assertEquals(2L, reopened.current().revision)

        val next = reopened.replace(reopened.current().copy(notes = "v3"))
        assertTrue("next save must not reuse revision 2", next.revision >= 3L)
        assertEquals(staged, prefs().getString("revision_2", null))
    }

    @Test fun recordsThatOnlyDecodeIntoFallbackAreRejected() {
        reset()
        val core = store()
        listOf(
            "{}",
            """{"twinIdentity":"Sage"}""",
            """{"revision":0,"twinIdentity":"Sage"}""",
            """{"revision":2,"twinIdentity":""}""",
            """{"revision":"not-a-number","twinIdentity":"Sage"}"""
        ).forEach { raw ->
            prefs().edit().putString("current", raw).commit()
            assertEquals("permissive skeleton must not count as authentic: $raw",
                SageCoreCurrentStatus.INVALID, core.currentStatus())
            assertEquals(0L, core.current().revision)
        }
    }

    @Test fun forgedRevisionKeyWithMismatchedEmbeddedRevisionIsNotRecoverable() {
        reset()
        prefs().edit().putString("revision_2", """{"revision":9,"twinIdentity":"Sage","notes":"forged"}""").commit()
        val core = store()

        assertTrue(core.validRevisions().isEmpty())
        assertNull(core.newestRecoverableRevision())
        assertNull(core.revision(2))
    }

    @Test fun boundRestoreRejectsCandidateThatChangedAfterReview() {
        reset()
        val core = store()
        core.replace(core.current().copy(notes = "original"))
        core.replace(core.current().copy(notes = "latest"))
        val reviewed = core.candidateForRestore(1)!!

        // Another write lands after review, so the reviewed target revision is now stale.
        core.replace(core.current().copy(notes = "intervening write"))

        assertNull("stale confirmation must write nothing", core.restore(1, reviewed.sourceDigest, reviewed.targetRevision))

        val refreshed = core.candidateForRestore(1)!!
        val applied = core.restore(1, refreshed.sourceDigest, refreshed.targetRevision)
        assertNotNull("a freshly reviewed candidate still restores", applied)
        assertEquals("original", applied!!.notes)
    }

    @Test fun candidatePreviewCarriesRealBeforeAndAfterSnapshots() {
        reset()
        val core = store()
        core.replace(core.current().copy(twinIdentity = "current identity", notes = "current note"))
        core.replace(core.current().copy(twinIdentity = "newer identity", notes = "newer note"))

        val candidate = core.candidateForRestore(1)!!

        assertEquals("newer identity", candidate.before.twinIdentity)
        assertEquals("current identity", candidate.after.twinIdentity)
        assertTrue(candidate.changedFields.contains("twinIdentity"))
        assertFalse(candidate.toString().contains("current identity"))
    }
}
