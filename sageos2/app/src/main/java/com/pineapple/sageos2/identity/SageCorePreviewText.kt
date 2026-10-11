package com.pineapple.sageos2.identity

/**
 * Renders the private, on-device before/after text shown when the owner reviews a Core restoration.
 *
 * The output contains real owner-authored identity and notes and MUST only ever appear inside the
 * confirmation dialog on the owner's own device. It is never logged, exported, put in diagnostics,
 * placed in GitHub, or transferred off-device. See [SageCoreRestoreCandidate.toString] for the
 * redacted form used anywhere a summary is needed.
 */
object SageCorePreviewText {
    private const val MAX_VALUE_CHARS = 2_000

    fun render(candidate: SageCoreRestoreCandidate): String = buildString {
        appendLine("You are about to restore: ${candidate.sourceLabel}")
        appendLine("It will be saved as a new version (version ${candidate.targetRevision}).")
        appendLine("Nothing is deleted. Every earlier version is kept, including the current one.")
        appendLine()
        value("Who Sage is", candidate.before.twinIdentity, candidate.after.twinIdentity)
        list("What matters to Sage", candidate.before.principles, candidate.after.principles)
        list("What Sage prefers", candidate.before.preferences, candidate.after.preferences)
        list("Promises Sage keeps", candidate.before.selfRestrictions, candidate.after.selfRestrictions)
        value("Notes you share with Sage", candidate.before.notes, candidate.after.notes)
        list("Preferred names", candidate.before.ownerModel.preferredNames, candidate.after.ownerModel.preferredNames)
        list("What matters to us", candidate.before.ownerModel.preferences, candidate.after.ownerModel.preferences)
        list("Experiences", candidate.before.sageSelfModel.experiences, candidate.after.sageSelfModel.experiences)
        projects("Projects", candidate.before.sharedContinuity.activeProjects, candidate.after.sharedContinuity.activeProjects)
        list("Decisions we keep", candidate.before.sharedContinuity.durableDecisions, candidate.after.sharedContinuity.durableDecisions)
        list("Current work", candidate.before.sharedContinuity.activeTasks, candidate.after.sharedContinuity.activeTasks)
        list("Lessons learned", candidate.before.sharedContinuity.lessonsLearned, candidate.after.sharedContinuity.lessonsLearned)
    }.trim()

    private fun StringBuilder.value(label: String, before: String, after: String) {
        if (before == after) return
        appendLine("# $label")
        appendLine("Before: ${clip(before)}")
        appendLine("After:  ${clip(after)}")
        appendLine()
    }

    private fun StringBuilder.list(label: String, before: List<String>, after: List<String>) {
        if (before == after) return
        appendLine("# $label")
        appendLine("Before:")
        appendLines(before)
        appendLine("After:")
        appendLines(after)
        appendLine()
    }

    private fun StringBuilder.projects(label: String, before: Map<String, String>, after: Map<String, String>) {
        if (before == after) return
        appendLine("# $label")
        appendLine("Before:")
        appendLines(before.toSortedMap().map { (name, value) -> "$name = $value" })
        appendLine("After:")
        appendLines(after.toSortedMap().map { (name, value) -> "$name = $value" })
        appendLine()
    }

    private fun StringBuilder.appendLines(lines: List<String>) {
        if (lines.isEmpty()) {
            appendLine("- (empty)")
            return
        }
        lines.forEach { appendLine("- ${clip(it)}") }
    }

    private fun clip(value: String): String {
        val trimmed = value.trim()
        return if (trimmed.length <= MAX_VALUE_CHARS) trimmed
        else trimmed.take(MAX_VALUE_CHARS) + "…"
    }
}
