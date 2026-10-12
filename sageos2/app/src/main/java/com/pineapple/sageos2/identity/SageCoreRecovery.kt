package com.pineapple.sageos2.identity

import android.content.Context

/**
 * Owner-facing, private-content-free status of Sage's Core.
 *
 * The primary status is chosen so the owner always gets the most actionable truthful answer:
 * a valid current record wins, then a recoverable stored snapshot, then a legacy source, then a
 * previously imported owner package, and only then "damaged" or "missing".
 */
enum class SageCoreStatus {
    VALID_CURRENT,
    RECOVERABLE_REVISION,
    LEGACY_CURRENT,
    LEGACY_PREVIOUS,
    OWNER_IMPORT_AVAILABLE,
    INVALID_CURRENT,
    NO_CURRENT
}

data class SageCoreHealth(
    val currentStatus: SageCoreCurrentStatus,
    val currentRevision: Long,
    val validRevisions: List<Long>,
    val newestRecoverableRevision: Long?,
    val legacyCurrentAvailable: Boolean,
    val legacyPreviousAvailable: Boolean,
    val ownerImportAvailable: Boolean,
    val revisionKeyCount: Int,
    val issueCodes: List<String>
) {
    val valid: Boolean get() = currentStatus == SageCoreCurrentStatus.VALID && currentRevision > 0L

    fun primaryStatus(): SageCoreStatus = when {
        valid -> SageCoreStatus.VALID_CURRENT
        newestRecoverableRevision != null -> SageCoreStatus.RECOVERABLE_REVISION
        legacyCurrentAvailable -> SageCoreStatus.LEGACY_CURRENT
        legacyPreviousAvailable -> SageCoreStatus.LEGACY_PREVIOUS
        ownerImportAvailable -> SageCoreStatus.OWNER_IMPORT_AVAILABLE
        currentStatus == SageCoreCurrentStatus.INVALID -> SageCoreStatus.INVALID_CURRENT
        else -> SageCoreStatus.NO_CURRENT
    }

    fun ownerHeadline(): String = when (primaryStatus()) {
        SageCoreStatus.VALID_CURRENT -> "Sage's Core is saved and healthy (version $currentRevision)."
        SageCoreStatus.RECOVERABLE_REVISION ->
            "Sage's current Core record is damaged, but a saved snapshot from version $newestRecoverableRevision looks intact."
        SageCoreStatus.LEGACY_CURRENT ->
            "A Sage 1.33.3 Core was found and is available to review."
        SageCoreStatus.LEGACY_PREVIOUS ->
            "A previous Sage 1.33.3 Core was found and is available to review."
        SageCoreStatus.OWNER_IMPORT_AVAILABLE ->
            "No saved Core is present, but an owner continuity package has been imported before."
        SageCoreStatus.INVALID_CURRENT ->
            "Sage's current Core record is damaged and no saved snapshot was found."
        SageCoreStatus.NO_CURRENT ->
            "No saved Core is present yet."
    }

    /** Counts/codes only: safe to copy to diagnostics or share for support. Never any Core text. */
    fun safeReport(): String = buildString {
        append("Sage Core health: ").append(primaryStatus().name).append('\n')
        append("current status: ").append(currentStatus.name).append('\n')
        append("current revision: ").append(currentRevision).append('\n')
        append("valid snapshots: ").append(validRevisions.size)
        if (validRevisions.isNotEmpty()) append(" (").append(validRevisions.joinToString()).append(')')
        append('\n')
        append("revision keys stored: ").append(revisionKeyCount).append('\n')
        append("newest recoverable revision: ").append(newestRecoverableRevision ?: "none").append('\n')
        append("legacy current source: ").append(legacyCurrentAvailable).append('\n')
        append("legacy previous source: ").append(legacyPreviousAvailable).append('\n')
        append("owner import history available: ").append(ownerImportAvailable).append('\n')
        append("issue codes: ").append(if (issueCodes.isEmpty()) "none" else issueCodes.joinToString())
    }
}

/**
 * Read-only view of the Sage 1.33.3 legacy Core preferences. Never mutates, never throws on a
 * wrongly-typed key, and never exposes the owner-authored text outside an explicit owner action.
 */
class LegacyCoreSource(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun ownerInstructions(issues: MutableList<String>): String? = read(KEY_OWNER_INSTRUCTIONS, issues)
    fun previousOwnerInstructions(issues: MutableList<String>): String? = read(KEY_PREVIOUS_INSTRUCTIONS, issues)

    fun currentAvailable(): Boolean = !ownerInstructions(mutableListOf()).isNullOrBlank()
    fun previousAvailable(): Boolean = !previousOwnerInstructions(mutableListOf()).isNullOrBlank()

    private fun read(key: String, issues: MutableList<String>): String? {
        if (runCatching { prefs.contains(key) }.getOrDefault(false).not()) return null
        return try {
            prefs.getString(key, null)
        } catch (e: ClassCastException) {
            issues += "legacy_core_type_invalid:$key"
            null
        }
    }

    companion object {
        const val PREFS = "sage_core"
        const val KEY_OWNER_INSTRUCTIONS = "owner_instructions"
        const val KEY_PREVIOUS_INSTRUCTIONS = "previous_owner_instructions"
        const val IMPORT_MARKER = "Imported Sage 1.33.3 owner instructions"
    }
}

/**
 * Bounded, deterministic, read-only Core forensic assessment. Inspects only app-owned preferences:
 * the SageOS 2 Core store and the Sage 1.33.3 legacy Core source, plus the owner continuity import
 * marker. No unbounded scans, no external search, no private text in the report.
 */
class SageCoreHealthReader(
    context: Context,
    private val store: SharedPreferencesSageCoreStore = SharedPreferencesSageCoreStore(context),
    private val legacy: LegacyCoreSource = LegacyCoreSource(context)
) {
    private val prefs = context.applicationContext.getSharedPreferences(CONTINUITY_IMPORT_PREFS, Context.MODE_PRIVATE)

    fun assess(): SageCoreHealth {
        val currentStatus = store.currentStatus()
        val currentValid = store.currentValidOrNull()
        val validRevisions = store.validRevisions()
        val legacyIssues = mutableListOf<String>()
        val legacyCurrent = legacy.ownerInstructions(legacyIssues)
        val legacyPrevious = legacy.previousOwnerInstructions(legacyIssues)

        val issues = buildList {
            addAll(legacyIssues)
            if (currentStatus == SageCoreCurrentStatus.INVALID) add("current_record_damaged")
            if (store.newestRecoverableRevision() != null && !store.valid) add("history_recoverable")
        }

        return SageCoreHealth(
            currentStatus = currentStatus,
            currentRevision = currentValid?.revision ?: 0L,
            validRevisions = validRevisions,
            newestRecoverableRevision = store.newestRecoverableRevision(),
            legacyCurrentAvailable = !legacyCurrent.isNullOrBlank(),
            legacyPreviousAvailable = !legacyPrevious.isNullOrBlank(),
            ownerImportAvailable = ownerImportMarkerCount() > 0,
            revisionKeyCount = store.revisionCount(),
            issueCodes = issues
        )
    }

    private fun ownerImportMarkerCount(): Int =
        runCatching { prefs.all.keys.count { it.startsWith("digest_") } }.getOrDefault(0)

    private val SharedPreferencesSageCoreStore.valid: Boolean
        get() = currentStatus() == SageCoreCurrentStatus.VALID && (currentValidOrNull()?.revision ?: 0L) > 0L

    companion object {
        private const val CONTINUITY_IMPORT_PREFS = "sageos2_owner_continuity_imports"
    }
}

/** An owner-authored legacy Core candidate, surfaced for review but never auto-applied. */
data class LegacyCoreCandidate(val sourceKey: String, val text: String)

/**
 * Owner-approved recovery from a Sage 1.33.3 legacy Core. Prefers the current legacy text and falls
 * back to a previous-only source. Restoration writes a brand-new monotonic revision and never
 * mutates the legacy preferences.
 */
class LegacyCoreRecovery(
    private val store: SharedPreferencesSageCoreStore,
    private val legacy: LegacyCoreSource
) {
    fun candidate(): LegacyCoreCandidate? {
        val issues = mutableListOf<String>()
        legacy.ownerInstructions(issues)?.takeIf { it.isNotBlank() }
            ?.let { return LegacyCoreCandidate(LegacyCoreSource.KEY_OWNER_INSTRUCTIONS, it.trim()) }
        legacy.previousOwnerInstructions(issues)?.takeIf { it.isNotBlank() }
            ?.let { return LegacyCoreCandidate(LegacyCoreSource.KEY_PREVIOUS_INSTRUCTIONS, it.trim()) }
        return null
    }

    fun preview(): SageCoreRestorePreview? {
        val candidate = candidate() ?: return null
        val merged = merge(base = base(), candidate)
        return store.previewOf("legacy:${candidate.sourceKey}", candidate.text, merged)
    }

    /**
     * Private, on-device before/after candidate carrying the real legacy identity text for the owner
     * to read before approving. Never logged, exported, or sent off-device.
     */
    fun previewCandidate(): SageCoreRestoreCandidate? {
        val candidate = candidate() ?: return null
        val before = base()
        val merged = merge(before, candidate)
        return SageCoreRestoreCandidate(
            sourceLabel = "legacy Sage 1.33.3 Core (${candidate.sourceKey})",
            sourceRevision = merged.revision,
            targetRevision = store.previewTargetRevision(merged.revision),
            sourceDigest = legacyDigest(candidate),
            before = before,
            after = merged,
            changedFields = store.changedTopLevelFieldsOf(store.currentValidOrNull(), merged)
        )
    }

    fun restore(): SageCoreSnapshot? = restore(null, null)

    /**
     * Owner-approved legacy recovery bound to the exact preview the owner saw. A stale confirmation
     * (candidate digest or target revision changed after review) writes nothing and returns null.
     */
    fun restore(expectedDigest: String?, expectedTargetRevision: Long?): SageCoreSnapshot? {
        val candidate = candidate() ?: return null
        val merged = merge(base = base(), candidate)
        if (expectedDigest != null && legacyDigest(candidate) != expectedDigest) return null
        if (expectedTargetRevision != null && store.previewTargetRevision(merged.revision) != expectedTargetRevision) return null
        return store.replace(merged)
    }

    private fun base(): SageCoreSnapshot = store.currentValidOrNull() ?: EmptySageCoreProvider.current()

    private fun legacyDigest(candidate: LegacyCoreCandidate): String =
        sageCoreSha256("${candidate.sourceKey}\u0000${candidate.text}")

    private fun merge(base: SageCoreSnapshot, candidate: LegacyCoreCandidate): SageCoreSnapshot {
        val block = "${LegacyCoreSource.IMPORT_MARKER} (${candidate.sourceKey}):\n${candidate.text}"
        return base.copy(
            notes = mergeParagraphs(base.notes, block),
            provenance = if (base.revision == 0L) "legacy_owner_instructions" else base.provenance
        )
    }

    private fun mergeParagraphs(left: String, right: String): String {
        val existing = left.trim()
        val incoming = right.trim()
        if (incoming.isEmpty() || incoming in existing) return existing
        return listOf(existing, incoming).filter { it.isNotEmpty() }.joinToString("\n\n")
    }
}
