package com.pineapple.sageos2.continuity

import android.content.Context
import com.pineapple.sageos2.identity.SageCoreSnapshot
import com.pineapple.sageos2.identity.SharedPreferencesSageCoreStore
import com.pineapple.sageos2.memory.SharedPreferencesTwinMemoryStore
import com.pineapple.sageos2.memory.TwinMemorySource
import com.pineapple.sageos2.memory.TwinMemorySubject
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

data class OwnerContinuityMemory(
    val subject: TwinMemorySubject,
    val key: String,
    val value: String,
    val confidence: Double
)

data class OwnerContinuityPackage(
    val source: String,
    val twinIdentity: String? = null,
    val principles: List<String> = emptyList(),
    val preferences: List<String> = emptyList(),
    val selfRestrictions: List<String> = emptyList(),
    val notes: String = "",
    val activeProjects: Map<String, String> = emptyMap(),
    val durableDecisions: List<String> = emptyList(),
    val activeTasks: List<String> = emptyList(),
    val lessonsLearned: List<String> = emptyList(),
    val memories: List<OwnerContinuityMemory> = emptyList()
)

data class OwnerContinuityImportResult(
    val source: String,
    val alreadyImported: Boolean,
    val coreRevision: Long,
    val memoriesApplied: Int
) {
    fun summary(): String = if (alreadyImported) {
        "This owner continuity package was already imported."
    } else {
        "Imported owner continuity from $source. Sage Core is now revision $coreRevision; memories applied: $memoriesApplied."
    }
}

object OwnerContinuityCodec {
    const val SCHEMA = "sage-owner-continuity-v1"
    private const val MAX_PACKAGE_CHARS = 64 * 1024

    fun decode(raw: String): OwnerContinuityPackage {
        require(raw.length in 2..MAX_PACKAGE_CHARS) { "continuity package is empty or too large" }
        val root = JSONObject(raw)
        require(root.optString("schema") == SCHEMA) { "unsupported continuity schema" }
        val source = clean(root.optString("source"), 160)
        require(source.isNotEmpty()) { "continuity source is required" }

        val core = root.optJSONObject("core") ?: JSONObject()
        val twinIdentity = core.optString("twinIdentity").trim().takeIf { it.isNotEmpty() }?.take(2_000)
        val notes = core.optString("notes").trim().take(12_000)
        val projects = core.optJSONObject("activeProjects").stringMap(32, 120, 1_000)
        val memories = root.optJSONArray("memories").memories()

        return OwnerContinuityPackage(
            source = source,
            twinIdentity = twinIdentity,
            principles = core.optJSONArray("principles").strings(64, 500),
            preferences = core.optJSONArray("preferences").strings(64, 500),
            selfRestrictions = core.optJSONArray("selfRestrictions").strings(64, 500),
            notes = notes,
            activeProjects = projects,
            durableDecisions = core.optJSONArray("durableDecisions").strings(64, 800),
            activeTasks = core.optJSONArray("activeTasks").strings(64, 800),
            lessonsLearned = core.optJSONArray("lessonsLearned").strings(64, 800),
            memories = memories
        )
    }

    private fun JSONArray?.strings(limit: Int, maxChars: Int): List<String> {
        if (this == null) return emptyList()
        require(length() <= limit) { "too many continuity entries" }
        return buildList {
            for (index in 0 until length()) {
                clean(optString(index), maxChars).takeIf { it.isNotEmpty() }?.let(::add)
            }
        }.distinct()
    }

    private fun JSONObject?.stringMap(limit: Int, keyChars: Int, valueChars: Int): Map<String, String> {
        if (this == null) return emptyMap()
        val keys = keys().asSequence().toList()
        require(keys.size <= limit) { "too many continuity projects" }
        return buildMap {
            keys.sorted().forEach { rawKey ->
                val key = clean(rawKey, keyChars)
                val value = clean(optString(rawKey), valueChars)
                if (key.isNotEmpty() && value.isNotEmpty()) put(key, value)
            }
        }
    }

    private fun JSONArray?.memories(): List<OwnerContinuityMemory> {
        if (this == null) return emptyList()
        require(length() <= 128) { "too many continuity memories" }
        return buildList {
            for (index in 0 until length()) {
                val item = optJSONObject(index) ?: continue
                val subject = runCatching {
                    TwinMemorySubject.valueOf(item.optString("subject", "SHARED").uppercase(Locale.US))
                }.getOrElse { throw IllegalArgumentException("invalid memory subject at index $index") }
                val key = clean(item.optString("key"), 160)
                val value = clean(item.optString("value"), 2_000)
                if (key.isEmpty() || value.isEmpty()) continue
                val confidence = item.optDouble("confidence", 1.0)
                require(confidence.isFinite()) { "invalid memory confidence at index $index" }
                add(
                    OwnerContinuityMemory(
                        subject = subject,
                        key = key,
                        value = value,
                        confidence = confidence.coerceIn(0.0, 1.0)
                    )
                )
            }
        }
    }

    private fun clean(value: String, maxChars: Int): String =
        value.replace(Regex("\\s+"), " ").trim().take(maxChars)
}

object OwnerContinuityMerger {
    fun merge(current: SageCoreSnapshot, incoming: OwnerContinuityPackage): SageCoreSnapshot {
        val continuity = current.sharedContinuity
        return current.copy(
            twinIdentity = incoming.twinIdentity ?: current.twinIdentity,
            sharedContinuity = continuity.copy(
                activeProjects = continuity.activeProjects + incoming.activeProjects,
                durableDecisions = mergeList(continuity.durableDecisions, incoming.durableDecisions),
                activeTasks = mergeList(continuity.activeTasks, incoming.activeTasks),
                lessonsLearned = mergeList(continuity.lessonsLearned, incoming.lessonsLearned)
            ),
            principles = mergeList(current.principles, incoming.principles),
            preferences = mergeList(current.preferences, incoming.preferences),
            selfRestrictions = mergeList(current.selfRestrictions, incoming.selfRestrictions),
            notes = mergeParagraphs(current.notes, incoming.notes)
        )
    }

    private fun mergeList(left: List<String>, right: List<String>): List<String> =
        (left + right).distinctBy { it.lowercase(Locale.US).trim() }

    private fun mergeParagraphs(left: String, right: String): String =
        listOf(left.trim(), right.trim()).filter { it.isNotEmpty() }.distinct().joinToString("\n\n")
}

class OwnerContinuityImporter(
    context: Context,
    private val core: SharedPreferencesSageCoreStore,
    private val memory: SharedPreferencesTwinMemoryStore
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun import(raw: String, nowMs: Long = System.currentTimeMillis()): OwnerContinuityImportResult {
        val incoming = OwnerContinuityCodec.decode(raw)
        val digest = sha256(raw.trim())
        if (prefs.getBoolean("digest_$digest", false)) {
            return OwnerContinuityImportResult(incoming.source, true, core.current().revision, 0)
        }

        val current = core.current()
        val merged = OwnerContinuityMerger.merge(current, incoming)
        val coreChanged = merged.copy(revision = current.revision) != current
        val savedCore = if (coreChanged) core.replace(merged) else current

        incoming.memories.forEach { item ->
            memory.remember(
                subject = item.subject,
                key = item.key,
                value = item.value,
                source = TwinMemorySource.EXPLICIT_OWNER,
                confidence = item.confidence,
                nowEpochMs = nowMs
            )
        }

        prefs.edit()
            .putBoolean("digest_$digest", true)
            .putString("source_$digest", incoming.source)
            .putLong("imported_at_$digest", nowMs)
            .apply()

        return OwnerContinuityImportResult(
            source = incoming.source,
            alreadyImported = false,
            coreRevision = savedCore.revision,
            memoriesApplied = incoming.memories.size
        )
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
        return buildString(digest.size * 2) {
            digest.forEach { byte -> append(String.format(Locale.US, "%02x", byte.toInt() and 0xff)) }
        }
    }

    companion object {
        private const val PREFS = "sageos2_owner_continuity_imports"
    }
}
