package com.pineapple.sageos2.identity

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercise the production editor boundary using synthetic app-private records. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
class SageCoreEditorSafetyTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun prefs() = context.getSharedPreferences("sage_core_v2", Context.MODE_PRIVATE)

    @Before fun clearFixture() {
        assertTrue(prefs().edit().clear().commit())
    }

    @Test fun missingCurrentWithRecoverableHistoryCannotSaveTheDisplayedFallback() {
        val store = SharedPreferencesSageCoreStore(context)
        val original = store.createNewCore("Original owner-authored identity to recover.")
        val sourceBytes = store.historyRaw(original.revision)
        assertTrue(prefs().edit().remove("current").commit())
        val displayedFallback = store.current()
        val before = prefs().all.toMap()

        assertThrows(IllegalStateException::class.java) {
            store.editCurrent { displayedFallback.copy(notes = "an ordinary editor change") }
        }

        assertEquals("an editor save must not promote the revision-zero fallback", before, prefs().all)
        val reviewed = store.candidateForRestore(original.revision)!!
        val restored = store.restore(original.revision, reviewed.sourceDigest, reviewed.targetRevision)!!
        assertEquals(original.twinIdentity, restored.twinIdentity)
        assertEquals(original.revision + 1, restored.revision)
        assertEquals("the owner's source survives the explicit restore", sourceBytes, store.historyRaw(original.revision))
    }

    @Test fun editorCannotReplaceDamagedCurrentEvenIfTheFormOpenedWhileItWasValid() {
        val store = SharedPreferencesSageCoreStore(context)
        val displayed = store.createNewCore("Owner identity shown before the record was damaged.")
        assertTrue(prefs().edit().putString("current", "{damaged-owner-record").commit())
        val before = prefs().all.toMap()

        assertThrows(IllegalStateException::class.java) { store.editCurrent { displayed.copy(notes = "stale form") } }

        assertEquals("a stale editor must preserve the complete damaged state", before, prefs().all)
    }

    @Test fun blankIdentityEditIsRejectedWithoutChangingCurrentHistoryOrJournal() {
        val store = SharedPreferencesSageCoreStore(context)
        val original = store.createNewCore("Original identity must remain usable after an invalid edit.")
        val before = prefs().all.toMap()

        assertThrows(IllegalArgumentException::class.java) { store.editCurrent { it.copy(twinIdentity = " \n\t") } }

        assertEquals(before, prefs().all)
        assertEquals(original, SharedPreferencesSageCoreStore(context).currentValidOrNull())
    }

    @Test fun validOwnerEditPersistsAsANewRevisionAndKeepsThePreviousIdentity() {
        val store = SharedPreferencesSageCoreStore(context)
        val original = store.createNewCore("Original owner-authored identity before an approved edit.")

        val saved = store.editCurrent { it.copy(twinIdentity = "Updated owner-authored identity.", notes = "new note") }

        assertEquals(original.revision + 1, saved.revision)
        assertEquals(original, store.revision(original.revision))
        assertEquals(saved, SharedPreferencesSageCoreStore(context).currentValidOrNull())
    }
}
