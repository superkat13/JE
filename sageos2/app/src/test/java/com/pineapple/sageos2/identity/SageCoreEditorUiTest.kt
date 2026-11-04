package com.pineapple.sageos2.identity

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import com.pineapple.sageos2.MainActivity
import com.pineapple.sageos2.runtime.SageRuntimeHost
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Render the production Core screen without starting microphone, runtime or native Brain. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
class SageCoreEditorUiTest {
    @Test fun missingCurrentWithSavedHistoryOffersReviewedRestoreAndDisablesFallbackSave() {
        val context: Context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("sage_core_v2", Context.MODE_PRIVATE)
        assertTrue(prefs.edit().clear().commit())
        val store = SharedPreferencesSageCoreStore(context)
        store.createNewCore("Actual owner-authored identity in the intact history.")
        assertTrue(prefs.edit().remove("current").commit())
        val sourceBytes = store.historyRaw(1)

        // Only screen construction is needed: avoid onCreate's unrelated runtime/service startup.
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        val host = SageRuntimeHost::class.java.getDeclaredConstructor(Context::class.java).let { constructor ->
            constructor.isAccessible = true
            constructor.newInstance(context)
        }
        val body = LinearLayout(activity)
        ReflectionHelpers.setField(activity, "host", host)
        ReflectionHelpers.setField(activity, "body", body)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "showCore")

        val buttons = descendants(body).filterIsInstance<Button>().toList()
        assertFalse("the displayed fallback must never become a saved identity through ordinary Save",
            buttons.single { it.text.toString() == "Save Sage's changes" }.isEnabled)
        assertTrue("the owner must still be able to review the actual source",
            buttons.single { it.text.toString() == "Review saved snapshot version 1" }.isEnabled)
        assertFalse(prefs.contains("current"))
        assertEquals(sourceBytes, store.historyRaw(1))
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
