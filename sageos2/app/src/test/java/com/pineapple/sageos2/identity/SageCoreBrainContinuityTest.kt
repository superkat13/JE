package com.pineapple.sageos2.identity

import android.content.Context
import com.pineapple.sageos2.apps.EmptyOwnerAppProvider
import com.pineapple.sageos2.memory.EmptyConversationHistoryProvider
import com.pineapple.sageos2.memory.EmptyTwinMemoryProvider
import com.pineapple.sageos2.mode.SageModeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Proves an owner-approved restored Core actually reaches the next local Brain prompt through
 * TwinContextRenderer, and that it survives store re-instantiation (process restart).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
class SageCoreBrainContinuityTest {
    private fun context(): Context = RuntimeEnvironment.getApplication()
    private fun prefs(context: Context) = context.getSharedPreferences("sage_core_v2", Context.MODE_PRIVATE)

    @Test fun approvedRestorePersistsAndReachesNextLocalBrainContext() {
        val context = context()
        prefs(context).edit().clear().commit()
        val store = SharedPreferencesSageCoreStore(context)
        store.replace(
            store.current().copy(
                twinIdentity = "I am Sage, Kat's twin.",
                ownerModel = OwnerModel(
                    preferredNames = listOf("Kat"),
                    preferences = listOf("prefers direct answers")
                ),
                sageSelfModel = SageSelfModel(identity = "Sage", experiences = listOf("learned the recovery workflow")),
                sharedContinuity = SharedContinuity(
                    activeProjects = mapOf("SageOS" to "recovery"),
                    durableDecisions = listOf("never delete owner history")
                ),
                principles = listOf("truth over comfort"),
                notes = "owner-applied note"
            )
        )
        store.replace(store.current().copy(notes = "later transient edit"))
        prefs(context).edit().putString("current", "{damaged record").commit()

        val restored = store.restore(1)
        assertEquals(3L, restored!!.revision)

        // Simulate a process restart: brand-new store instance reading the same preferences.
        val reopened = SharedPreferencesSageCoreStore(context)
        val core = reopened.current()
        assertEquals(3L, core.revision)

        val rendered = TwinContextRenderer().render(
            core,
            EmptyTwinMemoryProvider.snapshot(),
            EmptyConversationHistoryProvider.recent(24),
            EmptyOwnerAppProvider.snapshot(),
            SageModeSnapshot(),
            includeOperationalDetails = false,
            currentRequest = ""
        )

        listOf(
            "Kat",
            "prefers direct answers",
            "learned the recovery workflow",
            "never delete owner history",
            "SageOS = recovery",
            "truth over comfort",
            "owner-applied note"
        ).forEach { expected ->
            assertTrue("restored Core content must reach the Brain prompt: $expected", rendered.contains(expected))
        }
    }
}
