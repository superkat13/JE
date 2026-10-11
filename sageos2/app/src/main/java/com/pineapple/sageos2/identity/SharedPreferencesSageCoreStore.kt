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
        return if (runCatching { decode(raw) }.isSuccess) SageCoreCurrentStatus.VALID
        else SageCoreCurrentStatus.INVALID
    }

    fun currentPresent(): Boolean = runCatching { prefs.contains(KEY_CURRENT) }.getOrDefault(false)

    /** Raw stored bytes of `current`, or null when absent/wrongly typed. Never mutates. */
    fun currentRaw(): String? = runCatching { prefs.getString(KEY_CURRENT, null) }.getOrNull()

    /** Decoded `current` only when it is genuinely valid; null for missing or damaged. */
    fun currentValidOrNull(): SageCoreSnapshot? =
        currentRaw()?.let { raw -> runCatching { decode(raw) }.getOrNull() }

    /**
     * Safe read for ordinary callers. A damaged or missing record returns the canonical Sage
     * identity fallback at revision 0 to keep the app responsive; it is never written back
     * implicitly, and UI/self-care must consult [currentStatus] before treating it as restored.
     */
    override fun current(): SageCoreSnapshot = currentValidOrNull() ?: EmptySageCoreProvider.current()

    fun replace(next: SageCoreSnapshot): SageCoreSnapshot = locked {
        runCatching { recoverPendingWrite() }
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

    fun revision(revision: Long): SageCoreSnapshot? =
        historyRaw(revision)?.let { runCatching { decode(it) }.getOrNull() }

    /** Raw bytes of an immutable snapshot, for digests/previews. Never mutates. */
    fun historyRaw(revision: Long): String? =
        runCatching { prefs.getString(historyKey(revision), null) }.getOrNull()

    /**
     * Restore the *content* of an earlier snapshot as a brand-new, higher revision. The source
     * snapshot is left byte-for-byte intact.
     */
    fun restore(revision: Long): SageCoreSnapshot? = locked {
        val source = revision(revision) ?: return@locked null
        replace(source)
    }

    /** Every `revision_<n>` key that currently decodes to a valid snapshot, sorted ascending. */
    fun validRevisions(): List<Long> = revisionKeys()
        .filter { r -> historyRaw(r)?.let { runCatching { decode(it) }.isSuccess } == true }
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
        val candidate = runCatching { decode(raw) }.getOrNull() ?: return null
        return previewOf("stored snapshot v$revision", raw, candidate)
    }

    /** Safe preview from an arbitrary (already validated) candidate core, used for legacy/import. */
    fun previewOf(sourceLabel: String, raw: String, candidate: SageCoreSnapshot): SageCoreRestorePreview {
        val current = currentValidOrNull()
        return SageCoreRestorePreview(
            sourceLabel = sourceLabel,
            sourceRevision = candidate.revision,
            sourceDigest = sha256(raw),
            targetRevision = nextRevision(candidate.revision),
            changedFields = changedTopLevelFields(current, candidate),
            fieldCounts = fieldCounts(candidate)
        )
    }

    /**
     * Roll a torn two-phase write forward. Idempotent: safe to call on every open and on retry.
     * Returns true only when `current` was actually advanced from the journal.
     */
    fun recoverPendingWrite(): Boolean = locked {
        val journalRaw = runCatching { prefs.getString(KEY_JOURNAL, null) }.getOrNull() ?: return@locked false
        val journal = runCatching { decode(journalRaw) }.getOrNull()
        if (journal == null) {
            prefs.edit().remove(KEY_JOURNAL).commit()
            return@locked false
        }
        val currentValid = currentValidOrNull()
        if (currentValid == null || journal.revision > currentValid.revision) {
            prefs.edit().putString(KEY_CURRENT, journalRaw).remove(KEY_JOURNAL).commit()
            true
        } else {
            prefs.edit().remove(KEY_JOURNAL).commit()
            false
        }
    }

    private fun nextRevision(incoming: Long): Long {
        var maxVerified = currentValidOrNull()?.revision ?: 0L
        val usedKeys = mutableSetOf<Long>()
        val keys = runCatching { prefs.all.keys.toList() }.getOrDefault(emptyList())
        keys.forEach { key ->
            val match = REVISION_KEY.matchEntire(key) ?: return@forEach
            val number = match.groupValues[1].toLongOrNull() ?: return@forEach
            usedKeys += number
            historyRaw(number)?.let { raw -> runCatching { decode(raw) }.getOrNull() }
                ?.let { maxVerified = maxOf(maxVerified, it.revision, number) }
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
        private val REVISION_KEY = Regex("revision_(\\d+)")

        val TOP_LEVEL_FIELDS = listOf(
            "twinIdentity", "ownerModel", "sageSelfModel", "sharedContinuity",
            "principles", "preferences", "selfRestrictions", "notes"
        )

        /** Process-wide so independently constructed stores cannot race a write. */
        private val WRITE_LOCK = Any()
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") {
            String.format(Locale.US, "%02x", it.toInt() and 0xff)
        }

    private fun <T> locked(block: () -> T): T = synchronized(WRITE_LOCK) { block() }
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
