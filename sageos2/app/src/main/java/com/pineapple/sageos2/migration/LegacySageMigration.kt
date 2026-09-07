package com.pineapple.sageos2.migration

import android.content.Context
import com.pineapple.sageos2.apps.OwnerAppRecord
import com.pineapple.sageos2.apps.SharedPreferencesOwnerAppRegistry
import com.pineapple.sageos2.continuity.SharedPreferencesTaskContinuityStore
import com.pineapple.sageos2.continuity.TaskCheckpoint
import com.pineapple.sageos2.continuity.TaskState
import com.pineapple.sageos2.identity.SharedPreferencesSageCoreStore
import com.pineapple.sageos2.memory.SharedPreferencesTwinMemoryStore
import com.pineapple.sageos2.memory.TwinMemoryRecord
import com.pineapple.sageos2.memory.TwinMemorySnapshot
import com.pineapple.sageos2.memory.TwinMemorySource
import com.pineapple.sageos2.memory.TwinMemorySubject
import com.pineapple.sageos2.speech.BuiltInWakeTokens
import com.pineapple.sageos2.speech.SharedPreferencesWakeProfileStore
import com.pineapple.sageos2.speech.WakeProfile
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale
import java.util.UUID

/**
 * One-way, idempotent import of durable Sage 1.33.3 data into SageOS 2 stores.
 *
 * The old preferences are never deleted. The migration only copies information into the
 * new explicit twin stores, so an interrupted migration can safely be retried.
 */
class LegacySageMigration(
    context: Context,
    private val core: SharedPreferencesSageCoreStore,
    private val memory: SharedPreferencesTwinMemoryStore,
    private val ownerApps: SharedPreferencesOwnerAppRegistry,
    private val wakeProfiles: SharedPreferencesWakeProfileStore,
    private val tasks: SharedPreferencesTaskContinuityStore
) {
    private val appContext = context.applicationContext
    private val marker = appContext.getSharedPreferences(MIGRATION_PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun runIfNeeded(nowMs: Long = System.currentTimeMillis()): LegacyMigrationReport {
        if (marker.getBoolean(KEY_COMPLETE, false)) {
            return LegacyMigrationReport(alreadyCompleted = true)
        }

        val errors = mutableListOf<String>()
        var coreImported = false
        var memoriesImported = 0
        var appsImported = 0
        var wakeImported = 0
        var recoverableTasksImported = 0

        runCatching { coreImported = importCore() }
            .onFailure { errors += "core: ${it.message ?: it::class.simpleName}" }
        runCatching { memoriesImported = importMemories(nowMs) }
            .onFailure { errors += "memory: ${it.message ?: it::class.simpleName}" }
        runCatching { appsImported = importOwnerApps() }
            .onFailure { errors += "owner_apps: ${it.message ?: it::class.simpleName}" }
        runCatching { wakeImported = importWakeProfiles() }
            .onFailure { errors += "wake_profiles: ${it.message ?: it::class.simpleName}" }
        runCatching { recoverableTasksImported = importAutonomyTask(nowMs) }
            .onFailure { errors += "autonomy_task: ${it.message ?: it::class.simpleName}" }

        val completed = errors.isEmpty()
        if (completed) {
            marker.edit()
                .putBoolean(KEY_COMPLETE, true)
                .putLong(KEY_COMPLETED_AT, nowMs)
                .putString(KEY_SOURCE, "Sage 1.33.3")
                .commit()
        }

        return LegacyMigrationReport(
            alreadyCompleted = false,
            completed = completed,
            coreImported = coreImported,
            memoriesImported = memoriesImported,
            ownerAppsImported = appsImported,
            wakeProfilesImported = wakeImported,
            recoverableTasksImported = recoverableTasksImported,
            errors = errors
        )
    }

    private fun importCore(): Boolean {
        val legacy = appContext.getSharedPreferences(LEGACY_CORE_PREFS, Context.MODE_PRIVATE)
        if (!legacy.contains(LEGACY_CORE_TEXT)) return false
        val currentText = legacy.getString(LEGACY_CORE_TEXT, "").orEmpty().trim()
        if (currentText.isEmpty()) return false

        val markerText = "Imported Sage 1.33.3 owner instructions:"
        if (core.current().notes.contains(markerText)) return false

        val current = core.current()
        val previousText = legacy.getString(LEGACY_CORE_PREVIOUS, "").orEmpty().trim()
        if (current.revision == 0L && previousText.isNotEmpty() && previousText != currentText) {
            core.replace(
                current.copy(
                    notes = mergeParagraphs(
                        current.notes,
                        "Imported previous Sage 1.33.3 Core revision:\n$previousText"
                    )
                )
            )
        }
        val latest = core.current()
        core.replace(latest.copy(notes = mergeParagraphs(latest.notes, "$markerText\n$currentText")))
        return true
    }

    private fun importMemories(nowMs: Long): Int {
        val legacy = appContext.getSharedPreferences(LEGACY_STATE_PREFS, Context.MODE_PRIVATE)
        val entries = legacy.getStringSet(LEGACY_MEMORY_ITEMS, emptySet()).orEmpty()
        if (entries.isEmpty()) return 0

        val current = memory.snapshot()
        val existingValues = current.records.map { normalize(it.value) }.toMutableSet()
        val additions = mutableListOf<TwinMemoryRecord>()
        entries.sorted().forEach { encoded ->
            val parsed = LegacySageCodec.parseMemory(encoded) ?: return@forEach
            val normalizedValue = normalize(parsed.value)
            if (normalizedValue.isEmpty() || normalizedValue in existingValues) return@forEach
            existingValues += normalizedValue
            val created = parsed.createdAtMs.takeIf { it > 0L } ?: nowMs
            additions += TwinMemoryRecord(
                id = stableId("memory", encoded),
                subject = subjectForCategory(parsed.category),
                key = "Imported ${parsed.category.replace('_', ' ')}",
                value = parsed.value,
                source = TwinMemorySource.IMPORTED,
                confidence = parsed.confidence.coerceIn(0.0, 1.0),
                createdAtEpochMs = created,
                updatedAtEpochMs = created,
                active = true
            )
        }
        if (additions.isNotEmpty()) {
            memory.replace(TwinMemorySnapshot(current.revision + 1, current.records + additions))
        }
        return additions.size
    }

    private fun importOwnerApps(): Int {
        val legacy = appContext.getSharedPreferences(LEGACY_OWNER_APPS_PREFS, Context.MODE_PRIVATE)
        val raw = legacy.getString(LEGACY_OWNER_APPS_KEY, "").orEmpty().trim()
        if (raw.isEmpty()) return 0
        val imported = LegacySageCodec.parseOwnerApps(raw)
        if (imported.isEmpty()) return 0

        var changed = 0
        imported.forEach { item ->
            val existing = ownerApps.snapshot().apps.firstOrNull { it.packageName == item.packageName }
            val merged = OwnerAppRecord(
                packageName = item.packageName,
                displayName = existing?.displayName?.takeIf { it.isNotBlank() } ?: item.displayName,
                aliases = ((existing?.aliases ?: emptyList()) + item.aliases).distinctBy { it.lowercase(Locale.US) },
                purpose = mergeParagraphs(existing?.purpose.orEmpty(), item.purpose),
                enabled = existing?.enabled ?: true,
                startupProcedure = mergeParagraphs(existing?.startupProcedure.orEmpty(), item.startupProcedure)
            )
            if (existing != merged) {
                ownerApps.upsert(merged)
                changed++
            }
        }
        return changed
    }

    private fun importWakeProfiles(): Int {
        val legacy = appContext.getSharedPreferences(LEGACY_STATE_PREFS, Context.MODE_PRIVATE)
        val encoded = legacy.getStringSet(LEGACY_WAKE_PROFILES, emptySet()).orEmpty().toMutableSet()
        legacy.getStringSet(LEGACY_WAKE_ALIASES, emptySet()).orEmpty().forEach { alias ->
            LegacySageCodec.normalizeWakePhrase(alias).takeIf { it.isNotBlank() }?.let { phrase ->
                encoded += LegacySageCodec.encodeLegacyWake(phrase, LEGACY_WAKE_MODE_NORMAL, "")
            }
        }
        if (encoded.isEmpty()) return 0

        var changed = 0
        encoded.sorted().forEach { value ->
            val parsed = LegacySageCodec.parseWakeProfile(value) ?: return@forEach
            val phrase = LegacySageCodec.normalizeWakePhrase(parsed.phrase)
            if (phrase.isEmpty()) return@forEach
            val existing = wakeProfiles.profiles().firstOrNull { profile ->
                profile.phrases.any { WakeProfile.normalizePhrase(it) == phrase }
            }
            val builtInTokens = BuiltInWakeTokens.forPhrase(phrase)
            val legacyCommand = parsed.command.takeIf { parsed.mode == LEGACY_WAKE_MODE_COMMAND && it.isNotBlank() }
            val canWakeNow = builtInTokens != null && legacyCommand == null
            val modeId = if (parsed.mode == LEGACY_WAKE_MODE_RED_QUEEN) "red_queen" else existing?.modeId
            val wanted = WakeProfile(
                id = existing?.id ?: stableWakeId(phrase),
                displayName = existing?.displayName ?: "Imported ${phrase.split(' ').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }}",
                phrases = (existing?.phrases.orEmpty() + phrase).distinct(),
                compiledPhrases = buildMap {
                    putAll(existing?.compiledPhrases.orEmpty())
                    if (builtInTokens != null) put(phrase, builtInTokens)
                },
                modeId = modeId,
                acknowledgement = existing?.acknowledgement ?: "Yes",
                enabled = existing?.enabled == true || canWakeNow,
                legacyCommand = existing?.legacyCommand ?: legacyCommand
            )
            if (existing != wanted) {
                wakeProfiles.upsert(wanted)
                changed++
            }
        }
        return changed
    }

    private fun importAutonomyTask(nowMs: Long): Int {
        val legacy = appContext.getSharedPreferences(LEGACY_AUTONOMY_PREFS, Context.MODE_PRIVATE)
        val raw = legacy.getString(LEGACY_AUTONOMY_ACTIVE, "").orEmpty().trim()
        if (raw.isEmpty()) return 0
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return 0
        val oldState = obj.optString("state").uppercase(Locale.US)
        if (oldState in setOf("SOLVED", "CANCELLED", "ROLLED_BACK")) return 0
        val oldId = obj.optString("job_id").trim().ifEmpty { stableId("task", raw).take(18) }
        val taskId = "legacy1333_$oldId"
        if (tasks.get(taskId) != null) return 0
        val goal = obj.optString("goal").trim()
        val problem = obj.optString("problem").trim()
        val oldNext = obj.optString("next_action").trim()
        val summary = listOf(goal, problem).filter { it.isNotEmpty() }.joinToString("\n").ifBlank {
            "Imported unfinished Sage 1.33.3 work."
        }
        tasks.upsert(
            TaskCheckpoint(
                taskId = taskId,
                title = "Imported Sage 1.33.3 work",
                state = TaskState.WAITING,
                summary = summary,
                nextStep = buildString {
                    append("Review the imported checkpoint before continuing. Do not replay a prior side effect automatically.")
                    if (oldNext.isNotEmpty()) append(" Previous next action: ").append(oldNext)
                },
                updatedAtMs = obj.optLong("updated_at", nowMs).takeIf { it > 0L } ?: nowMs,
                metadata = mapOf("source" to "sage_1.33.3", "legacy_state" to oldState)
            )
        )
        return 1
    }

    private fun subjectForCategory(category: String): TwinMemorySubject = when (category) {
        "device" -> TwinMemorySubject.DEVICE
        "project" -> TwinMemorySubject.PROJECT
        "skill_instruction" -> TwinMemorySubject.APP
        else -> TwinMemorySubject.OWNER
    }

    private fun stableWakeId(phrase: String): String = when (phrase) {
        "sage" -> "sage"
        "sage glitch" -> "sage_glitch"
        else -> "legacy_${stableId("wake", phrase).substring(0, 8)}"
    }

    companion object {
        private const val MIGRATION_PREFS = "sageos2_legacy_migration"
        private const val KEY_COMPLETE = "sage_1_33_3_complete"
        private const val KEY_COMPLETED_AT = "sage_1_33_3_completed_at"
        private const val KEY_SOURCE = "source"

        private const val LEGACY_CORE_PREFS = "sage_core"
        private const val LEGACY_CORE_TEXT = "owner_instructions"
        private const val LEGACY_CORE_PREVIOUS = "previous_owner_instructions"
        private const val LEGACY_STATE_PREFS = "sage_state"
        private const val LEGACY_MEMORY_ITEMS = "memory_items"
        private const val LEGACY_WAKE_PROFILES = "wake_profiles_v1"
        private const val LEGACY_WAKE_ALIASES = "wake_aliases"
        private const val LEGACY_OWNER_APPS_PREFS = "sage_owner_apps"
        private const val LEGACY_OWNER_APPS_KEY = "entries"
        private const val LEGACY_AUTONOMY_PREFS = "sage_autonomy_jobs_v1"
        private const val LEGACY_AUTONOMY_ACTIVE = "active_job"

        private const val LEGACY_WAKE_MODE_NORMAL = "normal"
        private const val LEGACY_WAKE_MODE_RED_QUEEN = "red_queen"
        private const val LEGACY_WAKE_MODE_COMMAND = "command"

        private fun normalize(value: String): String = value.lowercase(Locale.US).replace(Regex("\\s+"), " ").trim()
        private fun stableId(kind: String, value: String): String =
            UUID.nameUUIDFromBytes("sage1333:$kind:$value".toByteArray(StandardCharsets.UTF_8)).toString()
        private fun mergeParagraphs(left: String, right: String): String =
            listOf(left.trim(), right.trim()).filter { it.isNotEmpty() }.distinct().joinToString("\n\n")
    }
}

data class LegacyMigrationReport(
    val alreadyCompleted: Boolean = false,
    val completed: Boolean = alreadyCompleted,
    val coreImported: Boolean = false,
    val memoriesImported: Int = 0,
    val ownerAppsImported: Int = 0,
    val wakeProfilesImported: Int = 0,
    val recoverableTasksImported: Int = 0,
    val errors: List<String> = emptyList()
) {
    fun summary(): String = buildString {
        append(if (alreadyCompleted) "legacy migration already complete" else if (completed) "legacy migration complete" else "legacy migration incomplete")
        append("; core=").append(coreImported)
        append(" memories=").append(memoriesImported)
        append(" apps=").append(ownerAppsImported)
        append(" wake=").append(wakeProfilesImported)
        append(" tasks=").append(recoverableTasksImported)
        if (errors.isNotEmpty()) append(" errors=").append(errors.joinToString(" | "))
    }
}

data class LegacyMemory(
    val category: String,
    val value: String,
    val confidence: Double,
    val source: String,
    val createdAtMs: Long
)

data class LegacyOwnerApp(
    val packageName: String,
    val displayName: String,
    val aliases: List<String>,
    val purpose: String,
    val startupProcedure: String
)

data class LegacyWakeProfile(val phrase: String, val mode: String, val command: String)

/** Pure decoders for the verified Sage 1.33.3 persistence formats. */
object LegacySageCodec {
    fun parseMemory(entry: String): LegacyMemory? {
        val raw = entry.trim()
        if (raw.isEmpty()) return null
        val parts = entry.split('\t')
        if (parts.size >= 7 && parts[0] == "v2") {
            val value = parts.drop(6).joinToString(" ").trim()
            if (value.isEmpty()) return null
            return LegacyMemory(
                category = cleanCategory(parts[2]),
                value = value,
                confidence = parts[3].toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 1.0,
                source = parts[4].trim().ifEmpty { "legacy" },
                createdAtMs = parts[5].toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            )
        }
        if (parts.size >= 3) return LegacyMemory(cleanCategory(parts[1]), parts.drop(2).joinToString(" ").trim(), 1.0, "legacy_owner_memory", 0L)
        if (parts.size == 2) return LegacyMemory("factual_memory", parts[1].trim(), 1.0, "legacy_owner_memory", 0L)
        return LegacyMemory("factual_memory", raw, 1.0, "legacy_owner_memory", 0L)
    }

    fun parseOwnerApps(raw: String): List<LegacyOwnerApp> = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val packageName = item.optString("package").trim()
                if (!packageName.matches(Regex("[a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)+"))) continue
                val label = item.optString("label").trim().ifEmpty { packageName }
                add(
                    LegacyOwnerApp(
                        packageName = packageName,
                        displayName = label,
                        aliases = splitList(item.optString("aliases").ifBlank { label }),
                        purpose = item.optString("purposes").trim(),
                        startupProcedure = item.optString("launch_steps").trim()
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    fun parseWakeProfile(encoded: String): LegacyWakeProfile? {
        val parts = encoded.split('.', limit = 3)
        if (parts.size != 3) return null
        return runCatching {
            val phrase = normalizeWakePhrase(decodeUrl(parts[0]))
            val mode = decodeUrl(parts[1]).trim().lowercase(Locale.US)
            val command = decodeUrl(parts[2]).trim()
            if (phrase.isEmpty() || mode !in setOf("normal", "red_queen", "brain", "command")) return null
            if (mode == "command" && command.isEmpty()) return null
            LegacyWakeProfile(phrase, mode, if (mode == "red_queen") "red queen mode" else command)
        }.getOrNull()
    }

    fun encodeLegacyWake(phrase: String, mode: String, command: String): String =
        listOf(phrase, mode, command).joinToString(".") { encodeUrl(it) }

    fun normalizeWakePhrase(value: String): String = value.lowercase(Locale.US)
        .replace(Regex("[^a-z0-9']+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun cleanCategory(value: String): String = value.lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
        .takeIf { it.isNotEmpty() } ?: "factual_memory"

    private fun splitList(value: String): List<String> = value.split(Regex("[,;|\\n]"))
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinctBy { it.lowercase(Locale.US) }

    private fun encodeUrl(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decodeUrl(value: String): String {
        val padded = value + "=".repeat((4 - value.length % 4) % 4)
        return String(Base64.getUrlDecoder().decode(padded), StandardCharsets.UTF_8)
    }
}
