package com.pineapple.sageos2.identity

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Synthetic, app-private test storage only. Never touches owner data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
class SageCoreOwnerCreationTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun prefs() = context.getSharedPreferences("sage_core_v2", Context.MODE_PRIVATE)
    private fun store() = SharedPreferencesSageCoreStore(context)

    @Before fun clearFixture() {
        assertTrue(prefs().edit().clear().commit())
    }

    @Test fun blankAndDefaultIdentityCannotBecomeRealCore() {
        val store = store()
        assertThrows(IllegalArgumentException::class.java) { store.createNewCore("   ") }
        assertThrows(IllegalArgumentException::class.java) {
            store.createNewCore(EmptySageCoreProvider.current().twinIdentity)
        }
        assertEquals(SageCoreCurrentStatus.MISSING, store.currentStatus())
        assertTrue(store.validRevisions().isEmpty())
    }

    @Test fun ownerAuthoredCreationHasDurableProvenanceAndCannotRunTwice() {
        val core = store()
        val created = core.createNewCore("I am a new, owner-authored Sage Core.")
        assertEquals(1L, created.revision)
        assertEquals("owner_created", created.provenance)
        assertEquals("I am a new, owner-authored Sage Core.", created.twinIdentity)
        val reopened = store()
        assertEquals(created, reopened.currentValidOrNull())
        assertThrows(IllegalStateException::class.java) {
            reopened.createNewCore("Attempted replacement for saved Core.")
        }
        assertEquals(created, core.current())
    }

    @Test fun damagedCurrentPreventsNewCreationAndPreservesRawRecord() {
        val corrupt = "owner source with invalid JSON"
        assertTrue(prefs().edit().putString("current", corrupt).commit())
        assertThrows(IllegalStateException::class.java) {
            store().createNewCore("Owner would like to create a different Core.")
        }
        assertEquals(corrupt, prefs().getString("current", null))
        assertTrue(store().validRevisions().isEmpty())
    }

    @Test fun savedHistoryCannotBeDiscardedByCreationWhenCurrentIsAbsent() {
        val store = store()
        store.createNewCore("Original, owner-authored Sage identity.")
        assertTrue(prefs().edit().remove("current").commit())
        assertThrows(IllegalStateException::class.java) {
            store.createNewCore("Proposed new identity over historical snapshot.")
        }
        assertNotNull(store.revision(1))
        assertFalse(prefs().contains("current"))
    }
}
