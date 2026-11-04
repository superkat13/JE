package com.pineapple.sageos2.memory

import android.content.Context
import com.pineapple.sageos2.identity.SageContinuityWriteLock
import com.pineapple.sageos2.persistence.ContinuityDecode
import com.pineapple.sageos2.persistence.ContinuityRead
import com.pineapple.sageos2.persistence.ContinuityStoreHealth
import com.pineapple.sageos2.persistence.ContinuityStoreSafety
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class SharedPreferencesTwinMemoryStore(context: Context) : TwinMemoryStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun snapshot(): TwinMemorySnapshot = readState().value

    /** Whether the stored value is missing, empty, readable, or damaged. Never mutates. */
    fun health(): ContinuityStoreHealth = readState().health

    /** Raw stored text for private recovery inspection; null when missing or wrongly typed. */
    fun rawStateTextOrNull(): String? = ContinuityStoreSafety.readRaw(prefs, KEY_STATE).text

    override fun remember(
        subject: TwinMemorySubject,
        key: String,
        value: String,
        source: TwinMemorySource,
        confidence: Double,
        nowEpochMs: Long
    ): TwinMemoryRecord = synchronized(SageContinuityWriteLock.monitor) {
        val read = readState()
        val current = read.value
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
        save(TwinMemorySnapshot(current.revision + 1, nextRecords), read.health)
        record
    }

    override fun forget(id: String, nowEpochMs: Long): Boolean = synchronized(SageContinuityWriteLock.monitor) {
        val read = readState()
        val current = read.value
        val target = current.records.firstOrNull { it.id == id } ?: return@synchronized false
        val next = current.records.map {
            if (it.id == id) target.copy(active = false, updatedAtEpochMs = nowEpochMs) else it
        }
        save(TwinMemorySnapshot(current.revision + 1, next), read.health)
        true
    }

    fun replace(snapshot: TwinMemorySnapshot) = synchronized(SageContinuityWriteLock.monitor) {
        val read = readState()
        save(snapshot.copy(revision = maxOf(read.value.revision + 1, snapshot.revision)), read.health)
    }

    fun exportJson(): String = encode(snapshot())

    private fun readState(): ContinuityRead<TwinMemorySnapshot> =
        ContinuityStoreSafety.read(prefs, KEY_STATE, TwinMemorySnapshot(0L, emptyList()), ::decode)

    private fun save(snapshot: TwinMemorySnapshot, health: ContinuityStoreHealth) {
        ContinuityStoreSafety.save(prefs, KEY_STATE, encode(snapshot), health, "Twin memory")
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

    private fun decode(raw: String): ContinuityDecode<TwinMemorySnapshot> {
        val root = JSONObject(raw)
        val array = root.optJSONArray("records")
            ?: throw IllegalArgumentException("records array is missing or wrongly typed")
        var unreadable = 0
        val records = buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i)
                if (item == null) {
                    unreadable++
                    continue
                }
                val subject = runCatching { TwinMemorySubject.valueOf(item.optString("subject")) }.getOrNull()
                val source = runCatching { TwinMemorySource.valueOf(item.optString("source")) }.getOrNull()
                val key = item.optString("key").trim()
                val value = item.optString("value").trim()
                if (subject == null || source == null || key.isEmpty() || value.isEmpty()) {
                    unreadable++
                    continue
                }
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
        return ContinuityDecode(
            value = TwinMemorySnapshot(root.optLong("revision", 0L), records),
            unreadableRecords = unreadable,
            empty = records.isEmpty()
        )
    }

    companion object {
        private const val PREFS = "sage_twin_memory_v2"
        private const val KEY_STATE = "state"
    }
}
