package com.pineapple.sageos2.speech

import org.json.JSONArray
import org.json.JSONObject

internal object RemoteWakeProtocol {
    const val MSG_CONFIGURE = 1
    const val MSG_START = 2
    const val MSG_STOP = 3
    const val MSG_CLOSE = 4
    const val MSG_WAKE_HIT = 100
    const val MSG_STATUS = 101

    const val KEY_PROFILES = "profiles"
    const val KEY_GENERATION = "generation"
    const val KEY_PROFILE_ID = "profile_id"
    const val KEY_MODE_ID = "mode_id"
    const val KEY_ACK = "ack"
    const val KEY_READY = "ready"
    const val KEY_DETAIL = "detail"

    fun encodeProfiles(profiles: List<WakeProfile>): String = JSONArray().apply {
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

    fun decodeProfiles(raw: String): List<WakeProfile> {
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
                        obj.optString(key).trim().takeIf { it.isNotEmpty() }?.let {
                            put(WakeProfile.normalizePhrase(key), it)
                        }
                    }
                }
                add(
                    WakeProfile(
                        id = item.optString("id").trim().ifEmpty { "remote_$i" },
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
}
