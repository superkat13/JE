package com.pineapple.sageos2.speech

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class WakeProfile(
    val id: String,
    val displayName: String,
    val phrases: List<String>,
    val modeId: String? = null,
    val acknowledgement: String = "Yes",
    val enabled: Boolean = true
) {
    init {
        require(id.isNotBlank())
        require(displayName.isNotBlank())
        require(phrases.any { it.isNotBlank() })
    }
}

data class WakeHit(
    val generation: Long,
    val profileId: String,
    val modeId: String?,
    val acknowledgement: String
)

interface WakeProfileProvider {
    fun profiles(): List<WakeProfile>
}

class SharedPreferencesWakeProfileStore(context: Context) : WakeProfileProvider {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun profiles(): List<WakeProfile> {
        val raw = prefs.getString(KEY, null) ?: return defaults()
        return runCatching { decode(raw) }.getOrElse { defaults() }
            .filter { it.enabled }
            .ifEmpty { defaults() }
    }

    fun replace(profiles: List<WakeProfile>) {
        require(profiles.isNotEmpty())
        prefs.edit().putString(KEY, encode(profiles)).apply()
    }

    fun upsert(profile: WakeProfile) {
        val all = profiles().associateBy { it.id }.toMutableMap()
        all[profile.id] = profile
        replace(all.values.toList())
    }

    fun remove(id: String) {
        val remaining = profiles().filterNot { it.id == id }
        replace(if (remaining.isEmpty()) defaults() else remaining)
    }

    private fun encode(profiles: List<WakeProfile>): String = JSONArray().apply {
        profiles.forEach { profile ->
            put(JSONObject().apply {
                put("id", profile.id)
                put("displayName", profile.displayName)
                put("phrases", JSONArray(profile.phrases))
                put("modeId", profile.modeId)
                put("acknowledgement", profile.acknowledgement)
                put("enabled", profile.enabled)
            })
        }
    }.toString()

    private fun decode(raw: String): List<WakeProfile> {
        val array = JSONArray(raw)
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val phraseArray = item.optJSONArray("phrases") ?: JSONArray()
                val phrases = buildList {
                    for (j in 0 until phraseArray.length()) {
                        phraseArray.optString(j).trim().takeIf { it.isNotEmpty() }?.let(::add)
                    }
                }
                if (phrases.isEmpty()) continue
                add(
                    WakeProfile(
                        id = item.optString("id").trim().ifEmpty { "profile_$i" },
                        displayName = item.optString("displayName").trim().ifEmpty { "Wake profile" },
                        phrases = phrases,
                        modeId = item.optString("modeId").trim().ifEmpty { null },
                        acknowledgement = item.optString("acknowledgement", "Yes").ifBlank { "Yes" },
                        enabled = item.optBoolean("enabled", true)
                    )
                )
            }
        }
    }

    companion object {
        private const val PREFS = "sage_wake_profiles_v2"
        private const val KEY = "profiles"

        fun defaults() = listOf(
            WakeProfile(
                id = "sage",
                displayName = "Sage",
                phrases = listOf("sage"),
                acknowledgement = "Yes"
            ),
            WakeProfile(
                id = "sage_glitch",
                displayName = "Sage Glitch",
                phrases = listOf("sage glitch"),
                modeId = "red_queen",
                acknowledgement = "Yes"
            )
        )
    }
}
