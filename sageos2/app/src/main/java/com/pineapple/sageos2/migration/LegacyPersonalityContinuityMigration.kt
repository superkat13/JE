package com.pineapple.sageos2.migration

import android.content.Context
import com.pineapple.sageos2.memory.SharedPreferencesTwinMemoryStore
import com.pineapple.sageos2.memory.TwinMemorySource
import com.pineapple.sageos2.memory.TwinMemorySubject
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale

/**
 * Small follow-up pass for durable 1.33.3 personality continuity.
 *
 * It intentionally does not recreate the old deterministic Easter-egg dispatcher. Owner-taught
 * phrase/reply pairs become explicit Sage memories so the knowledge survives the upgrade, while
 * old temporary-context memories are kept in storage but deactivated so they do not become
 * permanent twin truth.
 */
class LegacyPersonalityContinuityMigration(
    context: Context,
    private val memory: SharedPreferencesTwinMemoryStore
) {
    private val appContext = context.applicationContext
    private val marker = appContext.getSharedPreferences(MIGRATION_PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun runIfNeeded(nowMs: Long = System.currentTimeMillis()): LegacyPersonalityMigrationReport {
        if (marker.getBoolean(KEY_COMPLETE, false)) {
            return LegacyPersonalityMigrationReport(alreadyCompleted = true)
        }

        val errors = mutableListOf<String>()
        var temporaryContextDeactivated = 0
        var personalityRepliesImported = 0

        runCatching { temporaryContextDeactivated = deactivateImportedTemporaryContext() }
            .onFailure { errors += "temporary_context: ${it.message ?: it::class.simpleName}" }
        runCatching { personalityRepliesImported = importPersonalityReplies(nowMs) }
            .onFailure { errors += "personality_replies: ${it.message ?: it::class.simpleName}" }

        val completed = errors.isEmpty()
        if (completed) {
            marker.edit()
                .putBoolean(KEY_COMPLETE, true)
                .putLong(KEY_COMPLETED_AT, nowMs)
                .putString(KEY_SOURCE, "Sage 1.33.3")
                .commit()
        }

        return LegacyPersonalityMigrationReport(
            alreadyCompleted = false,
            completed = completed,
            temporaryContextDeactivated = temporaryContextDeactivated,
            personalityRepliesImported = personalityRepliesImported,
            errors = errors
        )
    }

    private fun deactivateImportedTemporaryContext(): Int {
        val current = memory.snapshot()
        var changed = 0
        val next = current.records.map { record ->
            if (
                record.active &&
                record.source == TwinMemorySource.IMPORTED &&
                record.key.equals("Imported temporary context", ignoreCase = true)
            ) {
                changed++
                record.copy(active = false)
            } else {
                record
            }
        }
        if (changed > 0) memory.replace(current.copy(records = next))
        return changed
    }

    private fun importPersonalityReplies(nowMs: Long): Int {
        val prefs = appContext.getSharedPreferences(LEGACY_STATE_PREFS, Context.MODE_PRIVATE)
        val encoded = prefs.getStringSet(LEGACY_EASTER_EGGS, emptySet()).orEmpty()
        if (encoded.isEmpty()) return 0

        var changed = 0
        encoded.sorted().forEach { raw ->
            val entry = LegacyPersonalityCodec.parse(raw) ?: return@forEach
            val key = "Owner-taught reply for: ${entry.phrase}"
            val existing = memory.snapshot().records.firstOrNull {
                it.subject == TwinMemorySubject.SAGE && it.key.equals(key, ignoreCase = true)
            }
            if (existing?.value == entry.response && existing.active) return@forEach
            memory.remember(
                subject = TwinMemorySubject.SAGE,
                key = key,
                value = entry.response,
                source = TwinMemorySource.IMPORTED,
                confidence = 1.0,
                nowEpochMs = existing?.createdAtEpochMs?.takeIf { it > 0L } ?: nowMs
            )
            changed++
        }
        return changed
    }

    companion object {
        private const val MIGRATION_PREFS = "sageos2_legacy_personality_migration"
        private const val KEY_COMPLETE = "sage_1_33_3_personality_complete"
        private const val KEY_COMPLETED_AT = "completed_at"
        private const val KEY_SOURCE = "source"
        private const val LEGACY_STATE_PREFS = "sage_state"
        private const val LEGACY_EASTER_EGGS = "easter_egg_replies_v1"
    }
}

data class LegacyPersonalityMigrationReport(
    val alreadyCompleted: Boolean = false,
    val completed: Boolean = alreadyCompleted,
    val temporaryContextDeactivated: Int = 0,
    val personalityRepliesImported: Int = 0,
    val errors: List<String> = emptyList()
) {
    fun summary(): String = buildString {
        append(if (alreadyCompleted) "legacy personality migration already complete" else if (completed) "legacy personality migration complete" else "legacy personality migration incomplete")
        append("; temp_context_deactivated=").append(temporaryContextDeactivated)
        append(" personality_replies=").append(personalityRepliesImported)
        if (errors.isNotEmpty()) append(" errors=").append(errors.joinToString(" | "))
    }
}

data class LegacyPersonalityReply(val phrase: String, val response: String)

/** Decoder for SageEasterEggStore's verified URL-safe base64 phrase.response format. */
object LegacyPersonalityCodec {
    fun parse(encoded: String): LegacyPersonalityReply? {
        val parts = encoded.split('.', limit = 2)
        if (parts.size != 2) return null
        return runCatching {
            val phrase = normalize(decodeUrl(parts[0]))
            val response = decodeUrl(parts[1]).trim()
            if (phrase.isEmpty() || response.isEmpty() || phrase.length > 80 || response.length > 600) return null
            LegacyPersonalityReply(phrase, response)
        }.getOrNull()
    }

    fun encode(phrase: String, response: String): String =
        encodeUrl(normalize(phrase)) + "." + encodeUrl(response.trim())

    private fun normalize(value: String): String = value.lowercase(Locale.US)
        .replace(Regex("[^a-z0-9']+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun encodeUrl(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decodeUrl(value: String): String {
        val padded = value + "=".repeat((4 - value.length % 4) % 4)
        return String(Base64.getUrlDecoder().decode(padded), StandardCharsets.UTF_8)
    }
}
