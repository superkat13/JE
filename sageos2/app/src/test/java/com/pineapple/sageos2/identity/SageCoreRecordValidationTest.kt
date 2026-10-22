package com.pineapple.sageos2.identity

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
class SageCoreRecordValidationTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun prefs() = context.getSharedPreferences("sage_core_v2", Context.MODE_PRIVATE)

    @Before fun clearFixture() { assertTrue(prefs().edit().clear().commit()) }

    @Test fun malformedFieldsCannotBeHealthyOrOfferedAsVerifiedRecoverySources() {
        val malformed = listOf(
            """{"revision":1,"twinIdentity":42}""",
            """{"revision":1.5,"twinIdentity":"Sage"}""",
            """{"revision":"1","twinIdentity":"Sage"}""",
            """{"revision":1,"twinIdentity":"Sage","notes":{"private":"wrong type"}}""",
            """{"revision":1,"twinIdentity":"Sage","ownerModel":"damaged"}""",
            """{"revision":1,"twinIdentity":"Sage","ownerModel":{"preferredNames":"wrong type"}}""",
            """{"revision":1,"twinIdentity":"Sage","ownerModel":{"learnedFacts":{"key":42}}}""",
            """{"revision":1,"twinIdentity":"Sage","sageSelfModel":{"identity":42}}""",
            """{"revision":1,"twinIdentity":"Sage","sharedContinuity":{"activeProjects":[]}}""",
            """{"revision":1,"twinIdentity":"Sage","principles":[{"private":"wrong type"}]}"""
        )
        malformed.forEachIndexed { index, raw ->
            assertTrue(prefs().edit().clear().putString("current", raw).putString("revision_1", raw).commit())
            val store = SharedPreferencesSageCoreStore(context)
            assertEquals("malformed fixture $index must not be healthy", SageCoreCurrentStatus.INVALID, store.currentStatus())
            assertEquals(0L, store.current().revision)
            assertNull(store.revision(1))
            assertNull(store.candidateForRestore(1))
            assertTrue(store.validRevisions().isEmpty())
            assertEquals("malformed fixture $index source bytes must remain untouched", raw, store.historyRaw(1))
            assertEquals(raw, prefs().getString("current", null))
        }
    }

    @Test fun historicalRecordsWithAbsentOptionalFieldsStillRestore() {
        val raw = """{"revision":1,"twinIdentity":"Historical owner identity"}"""
        assertTrue(prefs().edit().putString("revision_1", raw).commit())
        val store = SharedPreferencesSageCoreStore(context)
        val reviewed = store.candidateForRestore(1)!!

        val restored = store.restore(1, reviewed.sourceDigest, reviewed.targetRevision)!!

        assertEquals("Historical owner identity", restored.twinIdentity)
        assertEquals(2L, restored.revision)
        assertEquals(raw, store.historyRaw(1))
        assertEquals(restored, SharedPreferencesSageCoreStore(context).currentValidOrNull())
    }

    @Test fun blankIdentityCannotBeSavedAsSuccessfulButUnreadableRevision() {
        val store = SharedPreferencesSageCoreStore(context)
        val original = store.createNewCore("The owner-authored identity must remain intact.")
        val before = prefs().all.toMap()

        assertThrows(IllegalArgumentException::class.java) { store.replace(original.copy(twinIdentity = " \n\t")) }

        assertEquals(before, prefs().all)
        assertEquals(original, SharedPreferencesSageCoreStore(context).currentValidOrNull())
    }
}
