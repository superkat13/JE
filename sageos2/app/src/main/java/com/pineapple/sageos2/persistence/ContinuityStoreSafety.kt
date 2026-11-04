package com.pineapple.sageos2.persistence

import android.content.SharedPreferences

/** The durable state of a continuity store, distinct from whether it happens to be empty. */
enum class ContinuityStoreState { MISSING, EMPTY, VALID, DAMAGED }

data class ContinuityStoreHealth(
    val state: ContinuityStoreState,
    val unreadableRecords: Int = 0,
    val retainedDamagedCopies: Int = 0
)

internal data class ContinuityRaw(
    val present: Boolean,
    val text: String?,
    val value: Any?
)

internal data class ContinuityDecode<T>(
    val value: T,
    val unreadableRecords: Int,
    val empty: Boolean
)

internal data class ContinuityRead<T>(
    val health: ContinuityStoreHealth,
    val value: T,
    val raw: ContinuityRaw
)

/**
 * Shared protection for the small JSON continuity stores.
 *
 * A missing key is genuinely empty. A wrongly typed, unparseable, or partially unreadable value
 * is DAMAGED: readable records may still be returned, but the complete original value is copied to
 * a durable forensic slot before any save can replace it. Backups are append-only and idempotent.
 */
internal object ContinuityStoreSafety {
    private const val FORENSIC_COUNT = "forensic_damaged_state_count"
    private const val FORENSIC_PREFIX = "forensic_damaged_state_"
    private const val MAX_FORENSIC_KEYS = 10_000

    fun <T> read(
        prefs: SharedPreferences,
        key: String,
        emptyValue: T,
        decode: (String) -> ContinuityDecode<T>
    ): ContinuityRead<T> {
        val raw = readRaw(prefs, key)
        val retained = retainedDamagedCopies(prefs)
        if (!raw.present) {
            return ContinuityRead(
                ContinuityStoreHealth(ContinuityStoreState.MISSING, retainedDamagedCopies = retained),
                emptyValue,
                raw
            )
        }
        if (raw.text == null) {
            return ContinuityRead(
                ContinuityStoreHealth(ContinuityStoreState.DAMAGED, retainedDamagedCopies = retained),
                emptyValue,
                raw
            )
        }
        val decoded = runCatching { decode(raw.text) }.getOrNull()
            ?: return ContinuityRead(
                ContinuityStoreHealth(ContinuityStoreState.DAMAGED, retainedDamagedCopies = retained),
                emptyValue,
                raw
            )
        val unreadable = decoded.unreadableRecords.coerceAtLeast(0)
        val state = when {
            unreadable > 0 -> ContinuityStoreState.DAMAGED
            decoded.empty -> ContinuityStoreState.EMPTY
            else -> ContinuityStoreState.VALID
        }
        return ContinuityRead(
            ContinuityStoreHealth(state, unreadable, retained),
            decoded.value,
            raw
        )
    }

    fun readRaw(prefs: SharedPreferences, key: String): ContinuityRaw {
        val all = runCatching { prefs.all }.getOrNull()
            ?: return ContinuityRaw(present = true, text = null, value = null)
        if (!all.containsKey(key)) return ContinuityRaw(present = false, text = null, value = null)
        val value = all[key]
        return if (value is String) {
            ContinuityRaw(present = true, text = value, value = value)
        } else {
            ContinuityRaw(present = true, text = null, value = value)
        }
    }

    fun save(
        prefs: SharedPreferences,
        key: String,
        encoded: String,
        health: ContinuityStoreHealth,
        label: String
    ) {
        if (health.state == ContinuityStoreState.DAMAGED) preserveDamaged(prefs, key, label)
        check(prefs.edit().putString(key, encoded).commit()) { "$label save failed" }
    }

    fun clear(
        prefs: SharedPreferences,
        key: String,
        health: ContinuityStoreHealth,
        label: String
    ) {
        if (health.state == ContinuityStoreState.DAMAGED) preserveDamaged(prefs, key, label)
        check(prefs.edit().remove(key).commit()) { "$label clear failed" }
    }

    fun retainedDamagedCopies(prefs: SharedPreferences): Int = runCatching {
        val count = prefs.all[FORENSIC_COUNT]
        (count as? Number)?.toInt() ?: 0
    }.getOrDefault(0)

    private fun preserveDamaged(prefs: SharedPreferences, key: String, label: String) {
        val all = runCatching { prefs.all }.getOrElse {
            error("$label could not verify its damaged state before writing")
        }
        if (!all.containsKey(key)) return
        val raw = all[key] ?: return
        val count = (all[FORENSIC_COUNT] as? Number)?.toInt() ?: 0

        // Idempotent: a retry that still sees the same damaged bytes must not duplicate the backup.
        if (count > 0) {
            val lastKey = "$FORENSIC_PREFIX$count"
            if (all[lastKey] == raw) return
        }

        var next = count + 1
        while (all.containsKey("$FORENSIC_PREFIX$next")) {
            next++
            if (next > count + MAX_FORENSIC_KEYS) {
                error("$label forensic key space exhausted")
            }
        }

        val backupKey = "$FORENSIC_PREFIX$next"
        val editor = prefs.edit()
            .putInt(FORENSIC_COUNT, next)
            .putString("$backupKey.type", raw.javaClass.simpleName)
            .putLong("$backupKey.at", System.currentTimeMillis())
        when (raw) {
            is String -> editor.putString(backupKey, raw)
            is Int -> editor.putInt(backupKey, raw)
            is Long -> editor.putLong(backupKey, raw)
            is Float -> editor.putFloat(backupKey, raw)
            is Boolean -> editor.putBoolean(backupKey, raw)
            is Set<*> -> @Suppress("UNCHECKED_CAST") editor.putStringSet(
                backupKey,
                raw.map { it.toString() }.toSet()
            )
            else -> editor.putString(backupKey, raw.toString())
        }
        check(editor.commit()) { "$label forensic backup failed" }
    }
}
