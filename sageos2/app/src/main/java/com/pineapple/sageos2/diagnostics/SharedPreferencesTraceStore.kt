package com.pineapple.sageos2.diagnostics

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class SharedPreferencesTraceStore(
    context: Context,
    private val capacity: Int = 400
) : TraceStore {
    init { require(capacity > 0) }
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized override fun append(event: TraceEvent) {
        val events = recent(capacity).toMutableList()
        events += event
        val trimmed = events.takeLast(capacity)
        prefs.edit().putString(KEY_EVENTS, JSONArray(trimmed.map(::encode)).toString()).apply()
    }

    fun record(
        stage: String,
        message: String,
        turnId: Long? = null,
        level: TraceLevel = TraceLevel.INFO,
        metadata: Map<String, String> = emptyMap()
    ) = append(TraceEvent(UUID.randomUUID().toString(), System.currentTimeMillis(), turnId, stage, message, level, metadata))

    @Synchronized override fun recent(limit: Int): List<TraceEvent> {
        if (limit <= 0) return emptyList()
        val raw = prefs.getString(KEY_EVENTS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) decode(array.getJSONObject(i))?.let(::add)
            }.takeLast(limit)
        }.getOrDefault(emptyList())
    }

    override fun clear() { prefs.edit().remove(KEY_EVENTS).apply() }

    private fun encode(event: TraceEvent) = JSONObject().apply {
        put("id", event.id); put("timestampMs", event.timestampMs)
        event.turnId?.let { put("turnId", it) }
        put("stage", event.stage); put("message", event.message); put("level", event.level.name)
        put("metadata", JSONObject(event.metadata))
    }

    private fun decode(obj: JSONObject): TraceEvent? = runCatching {
        val metadataObj = obj.optJSONObject("metadata") ?: JSONObject()
        val metadata = buildMap {
            val keys = metadataObj.keys(); while (keys.hasNext()) { val key = keys.next(); put(key, metadataObj.optString(key)) }
        }
        TraceEvent(
            id = obj.getString("id"),
            timestampMs = obj.getLong("timestampMs"),
            turnId = if (obj.has("turnId")) obj.optLong("turnId") else null,
            stage = obj.getString("stage"),
            message = obj.optString("message"),
            level = runCatching { TraceLevel.valueOf(obj.optString("level", TraceLevel.INFO.name)) }.getOrDefault(TraceLevel.INFO),
            metadata = metadata
        )
    }.getOrNull()

    companion object { private const val PREFS = "sageos2_trace"; private const val KEY_EVENTS = "events" }
}
