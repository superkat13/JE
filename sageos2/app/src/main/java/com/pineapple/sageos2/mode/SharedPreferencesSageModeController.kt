package com.pineapple.sageos2.mode

import android.content.Context

class SharedPreferencesSageModeController(context: Context) : SageModeController {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun activate(profileId: String, modeId: String?) {
        prefs.edit()
            .putString(KEY_PROFILE, profileId.ifBlank { "sage" })
            .putString(KEY_MODE, modeId)
            .apply()
    }

    override fun current() = SageModeSnapshot(
        profileId = prefs.getString(KEY_PROFILE, "sage").orEmpty().ifBlank { "sage" },
        modeId = prefs.getString(KEY_MODE, null)?.takeIf { it.isNotBlank() }
    )

    companion object {
        private const val PREFS = "sageos2_mode"
        private const val KEY_PROFILE = "profile"
        private const val KEY_MODE = "mode"
    }
}
