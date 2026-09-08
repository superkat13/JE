package com.pineapple.sageos2.mode

import android.content.Context

class SharedPreferencesSageModeController(context: Context) : SageModeController {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val legacyPrefs = context.applicationContext.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)

    override fun activate(profileId: String, modeId: String?) {
        prefs.edit()
            .putString(KEY_PROFILE, profileId.ifBlank { "sage" })
            .putString(KEY_MODE, modeId)
            .apply()
    }

    override fun setTone(tone: SageTone) {
        // This is deliberately the exact Sage 1.33.3 preference. It remains readable in either
        // direction across an in-place update and preserves the owner's existing choice.
        legacyPrefs.edit().putString(LEGACY_TONE_KEY, tone.name).commit()
    }

    override fun current() = SageModeSnapshot(
        profileId = prefs.getString(KEY_PROFILE, "sage").orEmpty().ifBlank { "sage" },
        modeId = prefs.getString(KEY_MODE, null)?.takeIf { it.isNotBlank() },
        tone = runCatching {
            SageTone.valueOf(legacyPrefs.getString(LEGACY_TONE_KEY, SageTone.UNFILTERED.name).orEmpty())
        }.getOrDefault(SageTone.UNFILTERED)
    )

    companion object {
        private const val PREFS = "sageos2_mode"
        private const val KEY_PROFILE = "profile"
        private const val KEY_MODE = "mode"
        private const val LEGACY_PREFS = "sage_state"
        private const val LEGACY_TONE_KEY = "owner_tone"
    }
}
