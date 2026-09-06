package com.pineapple.sageos2.memory

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class SharedPreferencesConversationHistoryStore(
    context: Context,
    private val capacity: Int = 500
) : ConversationHistoryStore {
    init { require(capacity > 0) }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun recent(limit: Int): ConversationHistorySnapshot {
        val state = load()
        val bounded = limit.coerceAtLeast(0)
        return ConversationHistorySnapshot(state.revision, state.entries.takeLast(bounded))
    }

    @Synchronized
    override fun record(entry: ConversationEntry) {
        val state = load()
        val next = (state.entries + entry).takeLast(capacity)
        save(ConversationHistorySnapshot(state.revision + 1, next))
    }

    fun recordOwner(turnId: Long, text: String, input: ConversationInput, nowEpochMs: Long = System.currentTimeMillis()) {
        if (text.isBlank()) return
        record(ConversationEntry(UUID.randomUUID().toString(), turnId, ConversationSpeaker.OWNER, input, text.trim(), nowEpochMs))
    }

    fun recordSage(turnId: Long, text: String, nowEpochMs: Long = System.currentTimeMillis()) {
        if (text.isBlank()) return
        record(ConversationEntry(UUID.randomUUID().toString(), turnId, ConversationSpeaker.SAGE, ConversationInput.SYSTEM, text.trim(), nowEpochMs))
    }

    @Synchronized
    override fun clear() {
        prefs.edit().remove(KEY_STATE).apply()
    }

    private fun load(): ConversationHistorySnapshot {
        val raw = prefs.getString(KEY_STATE, null) ?: return ConversationHistorySnapshot(0L, emptyList())
        return runCatching { decode(raw) }.getOrElse { ConversationHistorySnapshot(0L, emptyList()) }
    }

    private fun save(snapshot: ConversationHistorySnapshot) {
        prefs.edit().putString(KEY_STATE, encode(snapshot)).apply()
    }

    private fun encode(snapshot: ConversationHistorySnapshot): String = JSONObject().apply {
        put("revision", snapshot.revision)
        put("entries", JSONArray().apply {
            snapshot.entries.forEach { e ->
                put(JSONObject().apply {
                    put("id", e.id)
                    put("turnId", e.turnId)
                    put("speaker", e.speaker.name)
                    put("input", e.input.name)
                    put("text", e.text)
                    put("timestampEpochMs", e.timestampEpochMs)
                })
            }
        })
    }.toString()

    private fun decode(raw: String): ConversationHistorySnapshot {
        val root = JSONObject(raw)
        val array = root.optJSONArray("entries") ?: JSONArray()
        val entries = buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val speaker = runCatching { ConversationSpeaker.valueOf(item.optString("speaker")) }.getOrNull() ?: continue
                val input = runCatching { ConversationInput.valueOf(item.optString("input")) }.getOrNull() ?: continue
                val text = item.optString("text").trim()
                if (text.isEmpty()) continue
                add(
                    ConversationEntry(
                        id = item.optString("id").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
                        turnId = item.optLong("turnId", 0L),
                        speaker = speaker,
                        input = input,
                        text = text,
                        timestampEpochMs = item.optLong("timestampEpochMs", 0L)
                    )
                )
            }
        }
        return ConversationHistorySnapshot(root.optLong("revision", 0L), entries.takeLast(capacity))
    }

    companion object {
        private const val PREFS = "sage_conversation_history_v2"
        private const val KEY_STATE = "state"
    }
}
