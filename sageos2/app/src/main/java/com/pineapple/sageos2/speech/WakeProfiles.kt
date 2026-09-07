package com.pineapple.sageos2.speech

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class WakeProfile(
    val id: String,
    val displayName: String,
    val phrases: List<String>,
    val compiledPhrases: Map<String, String> = emptyMap(),
    val modeId: String? = null,
    val acknowledgement: String = "Yes",
    val enabled: Boolean = true,
    val legacyCommand: String? = null
) {
    init {
        require(id.isNotBlank())
        require(displayName.isNotBlank())
        require(phrases.any { it.isNotBlank() })
    }

    fun compiledTokensFor(phrase: String): String? =
        compiledPhrases[normalizePhrase(phrase)]?.trim()?.takeIf { it.isNotEmpty() }
            ?: BuiltInWakeTokens.forPhrase(phrase)

    companion object {
        fun normalizePhrase(value: String) = value.lowercase().trim().replace(Regex("\\s+"), " ")
    }
}

object BuiltInWakeTokens {
    private val tokens = mapOf(
        "sage" to "▁S AGE",
        "sage glitch" to "▁S AGE ▁G LI T CH"
    )

    fun forPhrase(phrase: String): String? = tokens[WakeProfile.normalizePhrase(phrase)]
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

    /** Returns all configured profiles. The physical wake engine separately filters enabled profiles. */
    override fun profiles(): List<WakeProfile> {
        val raw = prefs.getString(KEY, null) ?: return defaults()
        return runCatching { decode(raw) }.getOrElse { defaults() }.ifEmpty { defaults() }
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
                put("compiledPhrases", JSONObject(profile.compiledPhrases))
                put("modeId", profile.modeId)
                put("acknowledgement", profile.acknowledgement)
                put("enabled", profile.enabled)
                put("legacyCommand", profile.legacyCommand)
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
                val compiled = buildMap {
                    val obj = item.optJSONObject("compiledPhrases") ?: JSONObject()
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val value = obj.optString(key).trim()
                        if (value.isNotEmpty()) put(WakeProfile.normalizePhrase(key), value)
                    }
                }
                add(
                    WakeProfile(
                        id = item.optString("id").trim().ifEmpty { "profile_$i" },
                        displayName = item.optString("displayName").trim().ifEmpty { "Wake profile" },
                        phrases = phrases,
                        compiledPhrases = compiled,
                        modeId = item.optString("modeId").trim().ifEmpty { null },
                        acknowledgement = item.optString("acknowledgement", "Yes").ifBlank { "Yes" },
                        enabled = item.optBoolean("enabled", true),
                        legacyCommand = item.optString("legacyCommand").trim().ifEmpty { null }
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
                compiledPhrases = mapOf("sage" to "▁S AGE"),
                acknowledgement = "Yes"
            ),
            WakeProfile(
                id = "sage_glitch",
                displayName = "Sage Glitch",
                phrases = listOf("sage glitch"),
                compiledPhrases = mapOf("sage glitch" to "▁S AGE ▁G LI T CH"),
                modeId = "red_queen",
                acknowledgement = "Yes"
            )
        )
    }
}
