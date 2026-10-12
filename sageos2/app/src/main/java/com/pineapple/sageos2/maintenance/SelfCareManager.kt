package com.pineapple.sageos2.maintenance

import com.pineapple.sageos2.continuity.TaskCheckpoint
import com.pineapple.sageos2.continuity.TaskContinuityStore
import com.pineapple.sageos2.continuity.TaskState

data class SelfCareSnapshot(
    val brainReady: Boolean,
    val brainDetail: String,
    val wakeReady: Boolean,
    val wakeDetail: String,
    val coreRevision: Long,
    val legacyCorePresent: Boolean,
    val migrationErrors: List<String> = emptyList(),
    val commandSpeechReady: Boolean? = null,
    val commandSpeechDetail: String = "",
    /** True when `current` exists but cannot be decoded, so revision 0 is not "just empty". */
    val coreCurrentDamaged: Boolean = false,
    /** Newest intact stored snapshot the owner could review and restore, if any. */
    val coreRecoverableRevision: Long? = null
)

data class SelfCareFinding(
    val code: String,
    val title: String,
    val summary: String,
    val nextStep: String,
    val severity: String
)

object SelfCarePolicy {
    fun evaluate(snapshot: SelfCareSnapshot): List<SelfCareFinding> = buildList {
        if (!snapshot.brainReady) add(
            SelfCareFinding(
                code = "brain_not_ready",
                title = "Local Brain needs attention",
                summary = snapshot.brainDetail.ifBlank { "The local Brain is not ready." },
                nextStep = "Preserve diagnostics and repair the local Brain path without clearing Sage data or replacing the model blindly.",
                severity = "high"
            )
        )

        if (snapshot.coreRevision == 0L) {
            when {
                snapshot.coreRecoverableRevision != null -> add(
                    SelfCareFinding(
                        code = "core_recoverable",
                        title = "Sage Core can be recovered",
                        summary = "A damaged or missing current Core record was found, but a saved snapshot from version ${snapshot.coreRecoverableRevision} is intact and can be reviewed.",
                        nextStep = "Open Settings → Advanced → Identity & continuity, review the saved snapshot, and restore it yourself. Sage never restores identity silently.",
                        severity = "high"
                    )
                )
                snapshot.coreCurrentDamaged -> add(
                    SelfCareFinding(
                        code = "core_damaged",
                        title = "Sage Core record needs attention",
                        summary = "Sage's saved Core record could not be read and no intact stored snapshot was found.",
                        nextStep = "Open Settings → Advanced → Identity & continuity to copy safe diagnostics and restore from an owner-reviewed package. Never fabricate replacement identity data.",
                        severity = "high"
                    )
                )
                else -> {
                    val sourceNote = if (snapshot.legacyCorePresent) {
                        "Legacy Sage Core source exists but SageOS 2 still has no Core revision."
                    } else {
                        "No legacy Sage Core payload is present in app-private storage."
                    }
                    val next = if (snapshot.legacyCorePresent) {
                        "Reconcile the legacy Core into the current store. If reconciliation still fails, inspect migration evidence; do not invent replacement identity data."
                    } else {
                        "Keep Sage identity continuity intact. Import an owner-reviewed package from Advanced → Identity & continuity → Restore owner continuity; never fabricate a Core."
                    }
                    add(SelfCareFinding("core_empty", "Sage Core continuity needs attention", sourceNote, next, "high"))
                }
            }
        }

        if (!snapshot.wakeReady) add(
            SelfCareFinding(
                code = "wake_not_ready",
                title = "Offline wake needs attention",
                summary = snapshot.wakeDetail.ifBlank { "Offline wake is not ready." },
                nextStep = "Use bounded automatic wake-process recovery. If retries pause, keep diagnostics and escalate the exact wake failure instead of looping.",
                severity = "medium"
            )
        )

        if (snapshot.commandSpeechReady == false) add(
            SelfCareFinding(
                code = "command_speech_not_ready",
                title = "Offline command speech needs attention",
                summary = snapshot.commandSpeechDetail.ifBlank { "Offline command speech is not ready." },
                nextStep = "Use text chat while inspecting speech readiness and microphone diagnostics. Do not clear speech models or Sage data.",
                severity = "medium"
            )
        )

        if (snapshot.migrationErrors.isNotEmpty()) add(
            SelfCareFinding(
                code = "continuity_migration_error",
                title = "Continuity migration needs attention",
                summary = snapshot.migrationErrors.joinToString(" | ").take(800),
                nextStep = "Repair the migration error idempotently. Never delete the legacy source while recovery is incomplete.",
                severity = "high"
            )
        )
    }
}

class SelfCareManager(private val tasks: TaskContinuityStore) {
    fun reconcile(snapshot: SelfCareSnapshot, nowMs: Long = System.currentTimeMillis()): List<SelfCareFinding> {
        val findings = SelfCarePolicy.evaluate(snapshot)
        val activeCodes = findings.map { it.code }.toSet()

        findings.forEach { finding ->
            val id = taskId(finding.code)
            val prior = tasks.get(id)
            // Keep the health finding visible, but respect the owner's task cancellation.
            if (prior?.state == TaskState.CANCELLED) return@forEach
            // Do not churn the task (and its timestamp) while the finding is unchanged, so repeated
            // health checks do not spam the owner with a "new" task every interval.
            val unchanged = prior != null &&
                prior.state == TaskState.WAITING &&
                prior.metadata["kind"] == KIND &&
                prior.metadata["severity"] == finding.severity &&
                prior.metadata["cleared"] != "true" &&
                prior.title == finding.title &&
                prior.summary == finding.summary &&
                prior.nextStep == finding.nextStep
            if (unchanged) return@forEach
            tasks.upsert(
                TaskCheckpoint(
                    taskId = id,
                    title = finding.title,
                    state = TaskState.WAITING,
                    summary = finding.summary,
                    nextStep = finding.nextStep,
                    updatedAtMs = nowMs,
                    metadata = ((prior?.metadata.orEmpty() - "cleared") + mapOf(
                        "kind" to KIND,
                        "code" to finding.code,
                        "severity" to finding.severity
                    ))
                )
            )
        }

        tasks.recent(100).filter { it.metadata["kind"] == KIND }.forEach { prior ->
            val code = prior.metadata["code"].orEmpty()
            if (code !in activeCodes && prior.state in setOf(TaskState.ACTIVE, TaskState.WAITING, TaskState.FAILED)) {
                tasks.upsert(
                    prior.copy(
                        state = TaskState.COMPLETED,
                        summary = "Self-care condition cleared. ${prior.summary}".trim(),
                        nextStep = "",
                        updatedAtMs = nowMs,
                        metadata = prior.metadata + ("cleared" to "true")
                    )
                )
            }
        }
        return findings
    }

    companion object {
        const val KIND = "self_care"
        fun taskId(code: String) = "selfcare:$code"
    }
}
