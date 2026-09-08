package com.pineapple.sageos2.memory

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class SharedPreferencesTwinMemoryStore(context: Context) : TwinMemoryStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun snapshot(): TwinMemorySnapshot {
        val raw = prefs.getString(KEY_STATE, null) ?: return EmptyTwinMemoryProvider.snapshot()
        return runCatching { decode(raw) }.getOrElse { EmptyTwinMemoryProvider.snapshot() }
    }

    @Synchronized
    override fun remember(
        subject: TwinMemorySubject,
        key: String,
        value: String,
        source: TwinMemorySource,
        confidence: Double,
        nowEpochMs: Long
    ): TwinMemoryRecord {
        val current = snapshot()
        val existing = current.records.firstOrNull { it.active && it.subject == subject && it.key.equals(key, ignoreCase = true) }
        val record = if (existing == null) {
            TwinMemoryRecord(
                id = UUID.randomUUID().toString(),
                subject = subject,
                key = key.trim(),
                value = value.trim(),
                source = source,
                confidence = confidence.coerceIn(0.0, 1.0),
                createdAtEpochMs = nowEpochMs,
                updatedAtEpochMs = nowEpochMs
            )
        } else {
            existing.copy(
                value = value.trim(),
                source = source,
                confidence = confidence.coerceIn(0.0, 1.0),
                updatedAtEpochMs = nowEpochMs,
                active = true
            )
        }
        val nextRecords = current.records.filterNot { it.id == record.id } + record
        save(TwinMemorySnapshot(current.revision + 1, nextRecords))
        return record
    }

    @Synchronized
    override fun forget(id: String, nowEpochMs: Long): Boolean {
        val current = snapshot()
        val target = current.records.firstOrNull { it.id == id } ?: return false
        val next = current.records.map {
            if (it.id == id) target.copy(active = false, updatedAtEpochMs = nowEpochMs) else it
        }
        save(TwinMemorySnapshot(current.revision + 1, next))
        return true
    }

    @Synchronized
    fun replace(snapshot: TwinMemorySnapshot) {
        save(snapshot.copy(revision = maxOf(this.snapshot().revision + 1, snapshot.revision)))
    }

    fun exportJson(): String = encode(snapshot())

    private fun save(snapshot: TwinMemorySnapshot) {
        prefs.edit().putString(KEY_STATE, encode(snapshot)).apply()
    }

    private fun encode(snapshot: TwinMemorySnapshot): String = JSONObject().apply {
        put("revision", snapshot.revision)
        put("records", JSONArray().apply {
            snapshot.records.forEach { record ->
                put(JSONObject().apply {
                    put("id", record.id)
                    put("subject", record.subject.name)
                    put("key", record.key)
                    put("value", record.value)
                    put("source", record.source.name)
                    put("confidence", record.confidence)
                    put("createdAtEpochMs", record.createdAtEpochMs)
                    put("updatedAtEpochMs", record.updatedAtEpochMs)
                    put("active", record.active)
                })
            }
        })
    }.toString()

    private fun decode(raw: String): TwinMemorySnapshot {
        val root = JSONObject(raw)
        val array = root.optJSONArray("records") ?: JSONArray()
        val records = buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val subject = runCatching { TwinMemorySubject.valueOf(item.optString("subject")) }.getOrNull() ?: continue
                val source = runCatching { TwinMemorySource.valueOf(item.optString("source")) }.getOrNull() ?: continue
                val key = item.optString("key").trim()
                val value = item.optString("value").trim()
                if (key.isEmpty() || value.isEmpty()) continue
                add(
                    TwinMemoryRecord(
                        id = item.optString("id").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
                        subject = subject,
                        key = key,
                        value = value,
                        source = source,
                        confidence = item.optDouble("confidence", 0.5).coerceIn(0.0, 1.0),
                        createdAtEpochMs = item.optLong("createdAtEpochMs", 0L),
                        updatedAtEpochMs = item.optLong("updatedAtEpochMs", 0L),
                        active = item.optBoolean("active", true)
                    )
                )
            }
        }
        return TwinMemorySnapshot(root.optLong("revision", 0L), records)
    }

    companion object {
        private const val PREFS = "sage_twin_memory_v2"
        private const val KEY_STATE = "state"
    }
}
