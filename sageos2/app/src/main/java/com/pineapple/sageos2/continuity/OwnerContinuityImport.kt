package com.pineapple.sageos2.continuity

import android.content.Context
import com.pineapple.sageos2.identity.SageCoreSnapshot
import com.pineapple.sageos2.identity.SharedPreferencesSageCoreStore
import com.pineapple.sageos2.identity.SageContinuityWriteLock
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

/** Owner-visible, private memory effects; never log the before/after values. */
data class OwnerContinuityMemoryChange(
    val subject: TwinMemorySubject,
    val key: String,
    val beforeValue: String?,
    val afterValue: String
)

data class OwnerContinuityImportPreview(
    val source: String,
    val before: SageCoreSnapshot,
    val after: SageCoreSnapshot,
    val coreChanged: Boolean,
    val memories: Int,
    val alreadyImported: Boolean = false,
    val digest: String = "",
    /** Captured with the owner preview; do not re-read at confirmation time. */
    val reviewedMemoryRevision: Long = 0L,
    val memoryChanges: List<OwnerContinuityMemoryChange> = emptyList()
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
        val twinIdentity = checkedTrim(core.optString("twinIdentity"), 2_000).takeIf { it.isNotEmpty() }
        val notes = checkedTrim(core.optString("notes"), 12_000)
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

    private fun clean(value: String, maxChars: Int): String {
        val normalized = value.replace(Regex("\\s+"), " ").trim()
        require(normalized.length <= maxChars) { "continuity value exceeds $maxChars characters; nothing was imported" }
        return normalized
    }

    private fun checkedTrim(value: String, maxChars: Int): String {
        val trimmed = value.trim()
        require(trimmed.length <= maxChars) { "continuity value exceeds $maxChars characters; nothing was imported" }
        return trimmed
    }
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

    private fun mergeParagraphs(left: String, right: String): String {
        val currentBlock = left.trim()
        val incomingBlock = right.trim()
        if (incomingBlock.isEmpty()) return currentBlock
        if (currentBlock.isEmpty()) return incomingBlock
        // Deduplicate at the block level (prior merges join blocks with blank lines) so a retried,
        // partially-applied import cannot append a second copy of the same owner note.
        val blocks = currentBlock.split("\n\n").map { it.trim() }.filter { it.isNotEmpty() }
        return if (blocks.any { it.equals(incomingBlock, ignoreCase = true) }) {
            currentBlock
        } else {
            listOf(currentBlock, incomingBlock).joinToString("\n\n")
        }
    }
}

class OwnerContinuityImporter(
    context: Context,
    private val core: SharedPreferencesSageCoreStore,
    private val memory: SharedPreferencesTwinMemoryStore
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun preview(raw: String): OwnerContinuityImportPreview = synchronized(SageContinuityWriteLock.monitor) {
        val trimmed = raw.trim()
        val incoming = OwnerContinuityCodec.decode(trimmed)
        val digest = sha256(trimmed)
        val current = core.current()
        val existingMemory = memory.snapshot()
        if (prefs.getBoolean("digest_$digest", false)) {
            return@synchronized OwnerContinuityImportPreview(
                incoming.source, current, current, false, 0, true, digest, existingMemory.revision
            )
        }
        val merged = OwnerContinuityMerger.merge(current, incoming)
        val coreChanged = merged.copy(revision = current.revision) != current
        val changes = incoming.memories.map { item ->
            val before = existingMemory.records.firstOrNull { record ->
                record.active && record.subject == item.subject && record.key.equals(item.key, ignoreCase = true)
            }
            OwnerContinuityMemoryChange(item.subject, item.key, before?.value, item.value)
        }
        OwnerContinuityImportPreview(
            incoming.source, current, merged, coreChanged, incoming.memories.size,
            digest = digest, reviewedMemoryRevision = existingMemory.revision,
            memoryChanges = changes
        )
    }

    /** Owner-approved path; the source and BOTH reviewed baselines are mandatory. */
    fun importBound(
        raw: String,
        expectedDigest: String,
        expectedCore: SageCoreSnapshot,
        expectedMemoryRev: Long
    ): OwnerContinuityImportResult = synchronized(SageContinuityWriteLock.monitor) {
        val trimmed = raw.trim()
        val digest = sha256(trimmed)
        if (digest != expectedDigest) throw IllegalStateException("stale continuity package confirmation")
        val incoming = OwnerContinuityCodec.decode(trimmed)
        if (prefs.getBoolean("digest_$digest", false)) {
            return@synchronized OwnerContinuityImportResult(incoming.source, true, core.current().revision, 0)
        }

        // All normal Core/memory writes use the same process lock: no writer can slip between
        // comparing the preview baselines and applying the import.
        val current = core.current()
        if (current != expectedCore || memory.snapshot().revision != expectedMemoryRev) {
            throw IllegalStateException("stale continuity package confirmation")
        }
        val merged = OwnerContinuityMerger.merge(current, incoming)
        val coreChanged = merged.copy(revision = current.revision) != current
        if (coreChanged && current.revision == 0L && incoming.twinIdentity.isNullOrBlank()) {
            throw IllegalArgumentException("new Core identity must come from the owner, not the fallback")
        }

        // Only after approval and baseline validation may we write the durable intent marker.
        check(prefs.edit().putBoolean("pending_$digest", true).commit()) {
            "could not record pending owner continuity import"
        }
        val savedCore = if (coreChanged) core.replace(merged) else current
        incoming.memories.forEach { item ->
            memory.remember(
                subject = item.subject,
                key = item.key,
                value = item.value,
                source = TwinMemorySource.EXPLICIT_OWNER,
                confidence = item.confidence,
                nowEpochMs = System.currentTimeMillis()
            )
        }
        check(
            prefs.edit()
                .putBoolean("digest_$digest", true)
                .putString("source_$digest", incoming.source)
                .putLong("imported_at_$digest", System.currentTimeMillis())
                .remove("pending_$digest")
                .commit()
        ) { "could not finalize owner continuity import; it will retry safely on the next attempt" }
        OwnerContinuityImportResult(
            source = incoming.source,
            alreadyImported = false,
            coreRevision = savedCore.revision,
            memoriesApplied = incoming.memories.size
        )
    }

    @Synchronized
    fun import(raw: String, nowMs: Long = System.currentTimeMillis()): OwnerContinuityImportResult {
        // Validate the whole package before any mutation so a malformed package can never leave a
        // partial write or a stray pending marker behind.
        val incoming = OwnerContinuityCodec.decode(raw)
        val digest = sha256(raw.trim())
        if (prefs.getBoolean("digest_$digest", false)) {
            return OwnerContinuityImportResult(incoming.source, true, core.current().revision, 0)
        }

        // Durable intent marker. If the process dies after this point, the work below is idempotent
        // (core merge is additive/distinct and memories upsert by subject+key), so a retry safely
        // finishes the same import instead of double-applying it.
        check(prefs.edit().putBoolean("pending_$digest", true).commit()) {
            "could not record pending owner continuity import"
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

        check(
            prefs.edit()
                .putBoolean("digest_$digest", true)
                .putString("source_$digest", incoming.source)
                .putLong("imported_at_$digest", nowMs)
                .remove("pending_$digest")
                .commit()
        ) { "could not finalize owner continuity import; it will retry safely on the next attempt" }

        return OwnerContinuityImportResult(
            source = incoming.source,
            alreadyImported = false,
            coreRevision = savedCore.revision,
            memoriesApplied = incoming.memories.size
        )
    }

    /** True when a prior import of this exact package was started but never finalized. */
    fun hasPending(digest: String): Boolean = prefs.getBoolean("pending_$digest", false)

    /** Count of imports that started but never finalized, for honest self-care/diagnostics. */
    fun pendingImportCount(): Int =
        runCatching { prefs.all.keys.count { it.startsWith("pending_") && prefs.getBoolean(it, false) } }.getOrDefault(0)

    /** Digest used to key import markers, exposed so callers can query pending state. */
    fun digestOf(raw: String): String = sha256(raw.trim())

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
