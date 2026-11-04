package com.pineapple.sageos2.memory

import android.content.Context
import com.pineapple.sageos2.persistence.ContinuityDecode
import com.pineapple.sageos2.persistence.ContinuityRead
import com.pineapple.sageos2.persistence.ContinuityStoreHealth
import com.pineapple.sageos2.persistence.ContinuityStoreSafety
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
        val state = readState().value
        val bounded = limit.coerceAtLeast(0)
        return ConversationHistorySnapshot(state.revision, state.entries.takeLast(bounded))
    }

    /** Whether the stored value is missing, empty, readable, or damaged. Never mutates. */
    fun health(): ContinuityStoreHealth = readState().health

    /** Raw stored text for private recovery inspection; null when missing or wrongly typed. */
    fun rawStateTextOrNull(): String? = ContinuityStoreSafety.readRaw(prefs, KEY_STATE).text

    @Synchronized
    override fun record(entry: ConversationEntry) {
        val read = readState()
        val state = read.value
        val next = (state.entries + entry).takeLast(capacity)
        save(ConversationHistorySnapshot(state.revision + 1, next), read.health)
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
        val read = readState()
        ContinuityStoreSafety.clear(prefs, KEY_STATE, read.health, "Conversation history")
    }

    private fun readState(): ContinuityRead<ConversationHistorySnapshot> = ContinuityStoreSafety.read(
        prefs,
        KEY_STATE,
        ConversationHistorySnapshot(0L, emptyList()),
        ::decode
    )

    private fun save(snapshot: ConversationHistorySnapshot, health: ContinuityStoreHealth) {
        ContinuityStoreSafety.save(prefs, KEY_STATE, encode(snapshot), health, "Conversation history")
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

    private fun decode(raw: String): ContinuityDecode<ConversationHistorySnapshot> {
        val root = JSONObject(raw)
        val array = root.optJSONArray("entries")
            ?: throw IllegalArgumentException("entries array is missing or wrongly typed")
        var unreadable = 0
        val entries = buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i)
                if (item == null) {
                    unreadable++
                    continue
                }
                val speaker = runCatching { ConversationSpeaker.valueOf(item.optString("speaker")) }.getOrNull()
                val input = runCatching { ConversationInput.valueOf(item.optString("input")) }.getOrNull()
                val text = item.optString("text").trim()
                if (speaker == null || input == null || text.isEmpty()) {
                    unreadable++
                    continue
                }
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
        return ContinuityDecode(
            value = ConversationHistorySnapshot(root.optLong("revision", 0L), entries.takeLast(capacity)),
            unreadableRecords = unreadable,
            empty = entries.isEmpty()
        )
    }

    companion object {
        private const val PREFS = "sage_conversation_history_v2"
        private const val KEY_STATE = "state"
    }
}
