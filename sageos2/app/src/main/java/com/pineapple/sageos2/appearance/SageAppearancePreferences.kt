package com.pineapple.sageos2.appearance

import android.content.Context

/** Reads and writes the exact Sage 1.33.3 appearance preferences in place. */
class SageAppearancePreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun current() = SageAppearanceSnapshot(
        mode = SageAppearanceMode.fromStored(prefs.getString(KEY_MODE, SageAppearanceMode.DARK.storedValue)),
        backgroundUri = prefs.getString(KEY_BACKGROUND_URI, "").orEmpty(),
        backgroundIntensity = prefs.getInt(KEY_BACKGROUND_INTENSITY, DEFAULT_INTENSITY).coerceIn(0, 100)
    )

    fun cycleMode(): SageAppearanceMode {
        val next = current().mode.next()
        prefs.edit().putString(KEY_MODE, next.storedValue).apply()
        return next
    }

    fun setBackgroundUri(value: String) {
        prefs.edit().putString(KEY_BACKGROUND_URI, value).apply()
    }

    fun setBackgroundIntensity(value: Int) {
        prefs.edit().putInt(KEY_BACKGROUND_INTENSITY, value.coerceIn(0, 100)).apply()
    }

    companion object {
        private const val PREFS = "sage_state"
        private const val KEY_MODE = "appearance_mode"
        private const val KEY_BACKGROUND_URI = "appearance_background_uri"
        private const val KEY_BACKGROUND_INTENSITY = "appearance_background_intensity"
        private const val DEFAULT_INTENSITY = 35
    }
}
