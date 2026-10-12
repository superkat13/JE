package com.pineapple.sageos2.identity

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

/**
 * Durable, revision-monotonic, crash-safe persistence for Sage's Core.
 *
 * Design guarantees:
 *  - A missing `current` record is distinguishable from a damaged one ([currentStatus]).
 *  - [current] never auto-promotes a fallback or a historical snapshot; recovery is an explicit,
 *    owner-approved action so nothing is silently "restored".
 *  - Every write is two-phase: the immutable `revision_<n>` snapshot and a `write_journal` are
 *    committed together, then `current` is promoted and the journal cleared. A power loss between
 *    the phases is rolled forward on the next open ([recoverPendingWrite]).
 *  - New revisions are always strictly greater than every verified valid revision and never reuse
 *    an existing snapshot key, so a damaged `current` can never clobber intact history.
 */
class SharedPreferencesSageCoreStore(context: Context) : SageCoreProvider {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        runCatching { recoverPendingWrite() }
    }

    /** Whether `current` is absent, decodable, or present-but-damaged. */
    fun currentStatus(): SageCoreCurrentStatus {
        if (!currentPresent()) return SageCoreCurrentStatus.MISSING
        val raw = currentRaw() ?: return SageCoreCurrentStatus.INVALID
        return if (isAuthenticRecord(raw, null)) SageCoreCurrentStatus.VALID
        else SageCoreCurrentStatus.INVALID
    }

    fun currentPresent(): Boolean = runCatching { prefs.contains(KEY_CURRENT) }.getOrDefault(false)

    /** Raw stored bytes of `current`, or null when absent/wrongly typed. Never mutates. */
    fun currentRaw(): String? = runCatching { prefs.getString(KEY_CURRENT, null) }.getOrNull()

    /** Decoded `current` only when it is genuinely valid; null for missing or damaged. */
    fun currentValidOrNull(): SageCoreSnapshot? =
        currentRaw()?.takeIf { isAuthenticRecord(it, null) }?.let { raw -> runCatching { decode(raw) }.getOrNull() }

    /**
     * Whether raw bytes represent an authentic saved snapshot rather than a permissively-decoded
     * skeleton. A real record must be a JSON object with a strictly positive revision and a genuine
     * nonblank identity. When [expectedKeyRevision] is provided (a `revision_<n>` slot), the embedded
     * revision must match the slot so a forged or copied record cannot masquerade as that revision.
     */
    private fun isAuthenticRecord(raw: String, expectedKeyRevision: Long?): Boolean {
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return false
        if (!obj.has("revision")) return false
        val revision = obj.optLong("revision", 0L)
        if (revision <= 0L) return false
        if (expectedKeyRevision != null && revision != expectedKeyRevision) return false
        if (!obj.has("twinIdentity")) return false
        return obj.optString("twinIdentity", "").trim().isNotEmpty()
    }

    /**
     * Safe read for ordinary callers. A damaged or missing record returns the canonical Sage
     * identity fallback at revision 0 to keep the app responsive; it is never written back
     * implicitly, and UI/self-care must consult [currentStatus] before treating it as restored.
     */
    override fun current(): SageCoreSnapshot = currentValidOrNull() ?: EmptySageCoreProvider.current()

    fun replace(next: SageCoreSnapshot): SageCoreSnapshot = locked {
        // Ensure no unresolved write journal is left from a previous failed reconciliation;
        // an inconsistent journal must be preserved for diagnosis and the operation must fail closed.
        val journalExistsCheck = runCatching { prefs.contains(KEY_JOURNAL) }
        if (journalExistsCheck.isFailure) {
            error("Unresolved Sage Core write journal detected; reconciliation required")
        }
        if (journalExistsCheck.getOrThrow()) {
            val journalRawBefore = runCatching { prefs.getString(KEY_JOURNAL, null) }.getOrNull()
            if (journalRawBefore == null) {
                // Wrongly typed or unreadable journal; preserve original value and fail closed
                error("Unresolved Sage Core write journal detected; reconciliation required")
            }
            val journalValid = journalRawBefore.takeIf { isAuthenticRecord(it, null) }
                ?.let { runCatching { decode(it) }.getOrNull() }
            val historyForKey = journalValid?.let { historyRaw(it.revision) }
            if (historyForKey == null || historyForKey != journalRawBefore) {
                // Unresolved/inconsistent journal: do not overwrite; fail closed to preserve evidence
                error("Unresolved Sage Core write journal detected; reconciliation required")
            }
            // Otherwise a pending write exists - attempt to recover it first
            recoverPendingWrite()
            // Re-check after recovery attempt
            val journalAfter = runCatching { prefs.getString(KEY_JOURNAL, null) }.getOrNull()
            if (journalAfter != null) {
                error("Unresolved Sage Core write journal detected; reconciliation required")
            }
        }
        // Never silently destroy an unreadable `current` record. Before promoting anything, retain the
        // original raw bytes (whatever their type) under a durable forensic key so the owner can still
        // recover the damaged data even after an ordinary save.
        if (currentStatus() == SageCoreCurrentStatus.INVALID) preserveDamagedCurrent()
        val revision = nextRevision(next.revision)
        val saved = next.copy(revision = revision)
        val encoded = encode(saved)
        val phaseOne = prefs.edit()
            .putString(historyKey(revision), encoded)
            .putString(KEY_JOURNAL, encoded)
        check(phaseOne.commit()) { "Sage Core history write failed" }
        val phaseTwo = prefs.edit()
            .putString(KEY_CURRENT, encoded)
            .remove(KEY_JOURNAL)
        check(phaseTwo.commit()) { "Sage Core current write failed" }
        saved
    }

    /** Durably retain a damaged `current` value (any type) before it is ever replaced. */
    private fun preserveDamagedCurrent() {
        val raw: Any = runCatching { prefs.all[KEY_CURRENT] }.getOrNull() ?: return
        val count = runCatching { prefs.getInt(KEY_FORENSIC_COUNT, 0) }.getOrDefault(0)
        // Idempotent: if the most recent forensic copy already holds these exact damaged bytes,
        // do not create a second duplicate backup.
        if (count > 0) {
            val lastKey = "$FORENSIC_PREFIX$count"
            if (runCatching { prefs.all[lastKey] }.getOrNull() == raw) return
        }
        // Find the next free forensic key (never overwrite existing). Counter must always advance.
        var nextIndex = count + 1
        while (prefs.contains("$FORENSIC_PREFIX$nextIndex")) {
            nextIndex++
            if (nextIndex > count + 1000) {
                // Fail closed rather than overwriting existing forensics
                error("Sage Core forensic key space exhausted")
            }
        }
        val key = "$FORENSIC_PREFIX$nextIndex"
        val editor = prefs.edit()
            .putInt(KEY_FORENSIC_COUNT, nextIndex)
            .putString("$key.type", raw.javaClass.simpleName)
            .putLong("$key.at", System.currentTimeMillis())
        when (raw) {
            is String -> editor.putString(key, raw)
            is Int -> editor.putInt(key, raw)
            is Long -> editor.putLong(key, raw)
            is Float -> editor.putFloat(key, raw)
            is Boolean -> editor.putBoolean(key, raw)
            is Set<*> -> @Suppress("UNCHECKED_CAST") editor.putStringSet(key, raw.map { it.toString() }.toSet())
            else -> editor.putString(key, raw.toString())
        }
        check(editor.commit()) { "Sage Core forensic backup failed" }
    }

    fun revision(revision: Long): SageCoreSnapshot? =
        historyRaw(revision)?.takeIf { isAuthenticRecord(it, revision) }?.let { runCatching { decode(it) }.getOrNull() }

    /** Raw bytes of an immutable snapshot, for digests/previews. Never mutates. */
    fun historyRaw(revision: Long): String? =
        runCatching { prefs.getString(historyKey(revision), null) }.getOrNull()

    /**
     * Restore the *content* of an earlier snapshot as a brand-new, higher revision. The source
     * snapshot is left byte-for-byte intact.
     */
    fun restore(revision: Long): SageCoreSnapshot? = restore(revision, null, null)

    /**
     * Owner-approved restore bound to the exact preview the owner saw. When [expectedDigest] or
     * [expectedTargetRevision] is supplied and no longer matches, nothing is written and null is
     * returned, so a candidate that changed after review can never be applied by a stale confirmation.
     */
    fun restore(revision: Long, expectedDigest: String?, expectedTargetRevision: Long?): SageCoreSnapshot? = locked {
        val raw = historyRaw(revision) ?: return@locked null
        if (!isAuthenticRecord(raw, revision)) return@locked null
        if (expectedDigest != null && sageCoreSha256(raw) != expectedDigest) return@locked null
        val source = runCatching { decode(raw) }.getOrNull() ?: return@locked null
        if (expectedTargetRevision != null && nextRevision(source.revision) != expectedTargetRevision) return@locked null
        replace(source)
    }

    /** Every `revision_<n>` key that currently holds an authentic snapshot, sorted ascending. */
    fun validRevisions(): List<Long> = revisionKeys()
        .filter { r -> historyRaw(r)?.let { isAuthenticRecord(it, r) } == true }
        .sorted()

    /** Highest verified valid revision across history and a valid current. */
    fun newestValidRevision(): Long? {
        val currentValid = currentValidOrNull()?.revision ?: 0L
        return (validRevisions().maxOrNull() ?: 0L).let { maxOf(it, currentValid) }.takeIf { it > 0L }
    }

    /**
     * Newest valid snapshot that is not the live current record, i.e. the candidate a damaged or
     * empty current could be recovered from without any mutation.
     */
    fun newestRecoverableRevision(): Long? {
        val currentValidRev = currentValidOrNull()?.revision
        return validRevisions().filter { it != currentValidRev }.maxOrNull()
    }

    fun revisionCount(): Int = revisionKeys().size

    fun exportJson(): String = encode(current())

    /** Compute the safe preview of restoring a stored revision, without writing anything. */
    fun previewRestore(revision: Long): SageCoreRestorePreview? {
        val raw = historyRaw(revision) ?: return null
        if (!isAuthenticRecord(raw, revision)) return null
        val candidate = runCatching { decode(raw) }.getOrNull() ?: return null
        return previewOf("stored snapshot v$revision", raw, candidate)
    }

    /** Safe preview from an arbitrary (already validated) candidate core, used for legacy/import. */
    fun previewOf(sourceLabel: String, raw: String, candidate: SageCoreSnapshot): SageCoreRestorePreview {
        val current = currentValidOrNull()
        return SageCoreRestorePreview(
            sourceLabel = sourceLabel,
            sourceRevision = candidate.revision,
            sourceDigest = sageCoreSha256(raw),
            targetRevision = previewTargetRevision(candidate.revision),
            changedFields = changedTopLevelFields(current, candidate),
            fieldCounts = fieldCounts(candidate)
        )
    }

    /**
     * Build an owner-facing restoration candidate carrying the actual before/after snapshots. This is
     * private to the device and must never be logged, exported, or placed in diagnostics. The digest
     * and target revision returned here bind the later confirmation to exactly what was reviewed.
     */
    fun candidateForRestore(revision: Long): SageCoreRestoreCandidate? {
        val raw = historyRaw(revision) ?: return null
        if (!isAuthenticRecord(raw, revision)) return null
        val after = runCatching { decode(raw) }.getOrNull() ?: return null
        val before = currentValidOrNull() ?: EmptySageCoreProvider.current()
        return SageCoreRestoreCandidate(
            sourceLabel = "saved snapshot version $revision",
            sourceRevision = revision,
            targetRevision = previewTargetRevision(after.revision),
            sourceDigest = sageCoreSha256(raw),
            before = before,
            after = after,
            changedFields = changedTopLevelFields(currentValidOrNull(), after)
        )
    }

    /** The revision a snapshot restore/import would be written as right now, without writing. */
    fun previewTargetRevision(incoming: Long): Long = locked { nextRevision(incoming) }

    internal fun changedTopLevelFieldsOf(current: SageCoreSnapshot?, candidate: SageCoreSnapshot): List<String> =
        changedTopLevelFields(current, candidate)

    /**
     * Roll a torn two-phase write forward, but only when the journal is an authentic record and its
     * bytes exactly match the immutable `revision_<n>` snapshot it claims to complete. A journal that
     * is unreadable or inconsistent with history fails closed: both raw entries are preserved for
     * diagnosis and nothing is promoted. Idempotent and safe to call on every open and on retry.
     * Returns true only when `current` was actually advanced from the journal.
     */
    fun recoverPendingWrite(): Boolean = locked {
        val je = runCatching { prefs.contains(KEY_JOURNAL) }
        if (je.isFailure) return@locked false
        if (je.getOrThrow()) {
            val journalRaw = runCatching { prefs.getString(KEY_JOURNAL, null) }.getOrNull()
            if (journalRaw == null) {
                return@locked false
            }
            val journal = journalRaw.takeIf { isAuthenticRecord(it, null) }
                ?.let { runCatching { decode(it) }.getOrNull() }
                ?: return@locked false
            val historyForKey = historyRaw(journal.revision)
            if (historyForKey == null || historyForKey != journalRaw) {
                return@locked false
            }
            val currentValid = currentValidOrNull()
            if (currentValid == null || journal.revision > currentValid.revision) {
                if (currentStatus() == SageCoreCurrentStatus.INVALID) preserveDamagedCurrent()
                check(prefs.edit().putString(KEY_CURRENT, journalRaw).remove(KEY_JOURNAL).commit()) {
                    "Sage Core journal roll-forward failed"
                }
                return@locked true
            } else {
                check(prefs.edit().remove(KEY_JOURNAL).commit()) { "Sage Core journal clear failed" }
                return@locked false
            }
        }
        return@locked false
    }

    private fun nextRevision(incoming: Long): Long {
        var maxVerified = currentValidOrNull()?.revision ?: 0L
        val usedKeys = mutableSetOf<Long>()
        val keys = runCatching { prefs.all.keys.toList() }.getOrDefault(emptyList())
        keys.forEach { key ->
            val match = REVISION_KEY.matchEntire(key) ?: return@forEach
            val number = match.groupValues[1].toLongOrNull() ?: return@forEach
            usedKeys += number
            historyRaw(number)?.takeIf { isAuthenticRecord(it, number) }?.let { raw ->
                runCatching { decode(raw) }.getOrNull()
            }?.let { maxVerified = maxOf(maxVerified, it.revision, number) }
        }
        var candidate = maxOf(maxVerified, incoming) + 1
        while (candidate in usedKeys) candidate++
        return candidate
    }

    private fun revisionKeys(): List<Long> = runCatching {
        prefs.all.keys.mapNotNull { key -> REVISION_KEY.matchEntire(key)?.groupValues?.get(1)?.toLongOrNull() }
    }.getOrDefault(emptyList())

    private fun changedTopLevelFields(current: SageCoreSnapshot?, candidate: SageCoreSnapshot): List<String> {
        if (current == null) return TOP_LEVEL_FIELDS
        return TOP_LEVEL_FIELDS.filter { field ->
            when (field) {
                "twinIdentity" -> current.twinIdentity != candidate.twinIdentity
                "ownerModel" -> current.ownerModel != candidate.ownerModel
                "sageSelfModel" -> current.sageSelfModel != candidate.sageSelfModel
                "sharedContinuity" -> current.sharedContinuity != candidate.sharedContinuity
                "principles" -> current.principles != candidate.principles
                "preferences" -> current.preferences != candidate.preferences
                "selfRestrictions" -> current.selfRestrictions != candidate.selfRestrictions
                "notes" -> current.notes != candidate.notes
                else -> false
            }
        }
    }

    private fun fieldCounts(core: SageCoreSnapshot): Map<String, Int> = linkedMapOf(
        "preferredNames" to core.ownerModel.preferredNames.size,
        "vocabulary" to core.ownerModel.vocabulary.size,
        "workingStyle" to core.ownerModel.workingStyle.size,
        "ownerPreferences" to core.ownerModel.preferences.size,
        "habits" to core.ownerModel.habits.size,
        "trustedTools" to core.ownerModel.trustedTools.size,
        "recurringChoices" to core.ownerModel.recurringChoices.size,
        "learnedFacts" to core.ownerModel.learnedFacts.size,
        "capabilities" to core.sageSelfModel.capabilities.size,
        "limitations" to core.sageSelfModel.limitations.size,
        "experiences" to core.sageSelfModel.experiences.size,
        "activeProjects" to core.sharedContinuity.activeProjects.size,
        "durableDecisions" to core.sharedContinuity.durableDecisions.size,
        "activeTasks" to core.sharedContinuity.activeTasks.size,
        "lessonsLearned" to core.sharedContinuity.lessonsLearned.size,
        "principles" to core.principles.size,
        "preferences" to core.preferences.size,
        "selfRestrictions" to core.selfRestrictions.size,
        "notes" to if (core.notes.isBlank()) 0 else 1,
        "twinIdentity" to if (core.twinIdentity.isBlank()) 0 else 1
    )

    private fun encode(core: SageCoreSnapshot) = JSONObject().apply {
        put("revision", core.revision)
        put("twinIdentity", core.twinIdentity)
        put("ownerModel", JSONObject().apply {
            put("preferredNames", JSONArray(core.ownerModel.preferredNames))
            put("vocabulary", JSONObject(core.ownerModel.vocabulary))
            put("workingStyle", JSONArray(core.ownerModel.workingStyle))
            put("preferences", JSONArray(core.ownerModel.preferences))
            put("habits", JSONArray(core.ownerModel.habits))
            put("trustedTools", JSONArray(core.ownerModel.trustedTools))
            put("recurringChoices", JSONArray(core.ownerModel.recurringChoices))
            put("learnedFacts", JSONObject(core.ownerModel.learnedFacts))
        })
        put("sageSelfModel", JSONObject().apply {
            put("identity", core.sageSelfModel.identity)
            put("capabilities", JSONArray(core.sageSelfModel.capabilities))
            put("limitations", JSONArray(core.sageSelfModel.limitations))
            put("experiences", JSONArray(core.sageSelfModel.experiences))
        })
        put("sharedContinuity", JSONObject().apply {
            put("activeProjects", JSONObject(core.sharedContinuity.activeProjects))
            put("durableDecisions", JSONArray(core.sharedContinuity.durableDecisions))
            put("activeTasks", JSONArray(core.sharedContinuity.activeTasks))
            put("lessonsLearned", JSONArray(core.sharedContinuity.lessonsLearned))
        })
        put("principles", JSONArray(core.principles))
        put("preferences", JSONArray(core.preferences))
        put("selfRestrictions", JSONArray(core.selfRestrictions))
        put("notes", core.notes)
    }.toString()

    private fun decode(raw: String): SageCoreSnapshot {
        val obj = JSONObject(raw)
        val owner = obj.optJSONObject("ownerModel") ?: JSONObject()
        val self = obj.optJSONObject("sageSelfModel") ?: JSONObject()
        val continuity = obj.optJSONObject("sharedContinuity") ?: JSONObject()
        return SageCoreSnapshot(
            revision = obj.optLong("revision", 0L),
            twinIdentity = obj.optString("twinIdentity", EmptySageCoreProvider.current().twinIdentity),
            ownerModel = OwnerModel(
                preferredNames = owner.optJSONArray("preferredNames").strings(),
                vocabulary = owner.optJSONObject("vocabulary").stringMap(),
                workingStyle = owner.optJSONArray("workingStyle").strings(),
                preferences = owner.optJSONArray("preferences").strings(),
                habits = owner.optJSONArray("habits").strings(),
                trustedTools = owner.optJSONArray("trustedTools").strings(),
                recurringChoices = owner.optJSONArray("recurringChoices").strings(),
                learnedFacts = owner.optJSONObject("learnedFacts").stringMap()
            ),
            sageSelfModel = SageSelfModel(
                identity = self.optString("identity", "Sage"),
                capabilities = self.optJSONArray("capabilities").strings(),
                limitations = self.optJSONArray("limitations").strings(),
                experiences = self.optJSONArray("experiences").strings()
            ),
            sharedContinuity = SharedContinuity(
                activeProjects = continuity.optJSONObject("activeProjects").stringMap(),
                durableDecisions = continuity.optJSONArray("durableDecisions").strings(),
                activeTasks = continuity.optJSONArray("activeTasks").strings(),
                lessonsLearned = continuity.optJSONArray("lessonsLearned").strings()
            ),
            principles = obj.optJSONArray("principles").strings(),
            preferences = obj.optJSONArray("preferences").strings(),
            selfRestrictions = obj.optJSONArray("selfRestrictions").strings(),
            notes = obj.optString("notes", "")
        )
    }

    private fun JSONArray?.strings(): List<String> {
        if (this == null) return emptyList()
        return buildList { for (i in 0 until length()) optString(i).takeIf { it.isNotBlank() }?.let(::add) }
    }

    private fun JSONObject?.stringMap(): Map<String, String> {
        if (this == null) return emptyMap()
        return buildMap {
            val keys = keys()
            while (keys.hasNext()) {
                val key = keys.next()
                optString(key).takeIf { it.isNotBlank() }?.let { put(key, it) }
            }
        }
    }

    private fun historyKey(revision: Long) = "revision_$revision"

    companion object {
        private const val PREFS = "sage_core_v2"
        private const val KEY_CURRENT = "current"
        private const val KEY_JOURNAL = "write_journal"
        private const val KEY_FORENSIC_COUNT = "forensic_damaged_current_count"
        private const val FORENSIC_PREFIX = "forensic_damaged_current_"
        private val REVISION_KEY = Regex("revision_(\\d+)")

        val TOP_LEVEL_FIELDS = listOf(
            "twinIdentity", "ownerModel", "sageSelfModel", "sharedContinuity",
            "principles", "preferences", "selfRestrictions", "notes"
        )

        /** Process-wide so independently constructed stores cannot race a write. */
        private val WRITE_LOCK = Any()
    }

    private fun <T> locked(block: () -> T): T = synchronized(WRITE_LOCK) { block() }
}

/** Stable SHA-256 hex helper shared across Core recovery and continuity binding. */
internal fun sageCoreSha256(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") {
        String.format(Locale.US, "%02x", it.toInt() and 0xff)
    }

enum class SageCoreCurrentStatus { MISSING, VALID, INVALID }

/** Safe, private-content-free view of a candidate Core restoration. */
data class SageCoreRestorePreview(
    val sourceLabel: String,
    val sourceRevision: Long,
    val sourceDigest: String,
    val targetRevision: Long,
    val changedFields: List<String>,
    val fieldCounts: Map<String, Int>
) {
    fun safeSummary(): String = buildString {
        append("Source: ").append(sourceLabel)
        append("; source revision ").append(sourceRevision)
        append("; new revision ").append(targetRevision)
        append("; digest ").append(sourceDigest.take(12))
        append("; fields changing: ").append(if (changedFields.isEmpty()) "none" else changedFields.joinToString())
    }
}

/**
 * Owner-facing restoration candidate that carries the actual before/after identity so the owner can
 * read exactly what they are approving. This object holds private Core content and MUST stay on the
 * device: it is never logged, exported, placed in diagnostics, or sent off-device. [toString] is
 * deliberately redacted so accidental interpolation cannot leak identity text.
 */
class SageCoreRestoreCandidate internal constructor(
    val sourceLabel: String,
    val sourceRevision: Long,
    val targetRevision: Long,
    val sourceDigest: String,
    val before: SageCoreSnapshot,
    val after: SageCoreSnapshot,
    val changedFields: List<String>
) {
    /** True only when this candidate is byte-identical to what the owner reviewed. */
    fun matches(expectedDigest: String, expectedTargetRevision: Long): Boolean =
        sourceDigest == expectedDigest && targetRevision == expectedTargetRevision

    override fun toString(): String =
        "SageCoreRestoreCandidate(source=$sourceLabel, digest=${sourceDigest.take(12)}, " +
            "target=$targetRevision, fields=${changedFields.size})"
}
