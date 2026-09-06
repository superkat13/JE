package com.pineapple.sageos2.identity

import com.pineapple.sageos2.apps.OwnerAppSnapshot
import com.pineapple.sageos2.memory.ConversationHistorySnapshot
import com.pineapple.sageos2.memory.ConversationSpeaker
import com.pineapple.sageos2.memory.TwinMemoryRecord
import com.pineapple.sageos2.memory.TwinMemorySnapshot
import com.pineapple.sageos2.mode.SageModeSnapshot

class TwinContextRenderer(
    private val maxMemories: Int = 64,
    private val maxHistoryEntries: Int = 24
) {
    init { require(maxMemories >= 0); require(maxHistoryEntries >= 0) }

    fun render(
        core: SageCoreSnapshot,
        memory: TwinMemorySnapshot,
        history: ConversationHistorySnapshot,
        ownerApps: OwnerAppSnapshot,
        mode: SageModeSnapshot
    ): String = buildString {
        appendLine("# SAGE TWIN CONTEXT")
        appendLine("This context is readable SageOS data, not a hidden provider persona.")
        appendLine()
        appendLine("## Twin identity")
        appendLine(core.twinIdentity.ifBlank { "Sage" })
        appendLine()
        appendLine("## Owner model")
        appendList("Preferred names", core.ownerModel.preferredNames)
        appendMap("Vocabulary", core.ownerModel.vocabulary)
        appendList("Working style", core.ownerModel.workingStyle)
        appendList("Owner preferences", core.ownerModel.preferences)
        appendList("Habits", core.ownerModel.habits)
        appendList("Trusted tools", core.ownerModel.trustedTools)
        appendList("Recurring choices", core.ownerModel.recurringChoices)
        appendMap("Learned owner facts", core.ownerModel.learnedFacts)
        appendLine()
        appendLine("## Owner Apps")
        val apps = ownerApps.apps.filter { it.enabled }.sortedBy { it.displayName.lowercase() }
        if (apps.isEmpty()) appendLine("(none)") else apps.forEach { app ->
            val aliases = app.aliases.joinToString(", ").ifBlank { "none" }
            val purpose = app.purpose.ifBlank { "unspecified" }
            appendLine("- ${app.displayName} [${app.packageName}] aliases=[$aliases] purpose=$purpose")
        }
        appendLine()
        appendLine("## Sage self model")
        appendLine("Identity: ${core.sageSelfModel.identity}")
        appendList("Capabilities", core.sageSelfModel.capabilities)
        appendList("Current limitations", core.sageSelfModel.limitations)
        appendList("Experiences", core.sageSelfModel.experiences)
        appendLine()
        appendLine("## Shared continuity")
        appendMap("Active projects", core.sharedContinuity.activeProjects)
        appendList("Durable decisions", core.sharedContinuity.durableDecisions)
        appendList("Active tasks", core.sharedContinuity.activeTasks)
        appendList("Lessons learned", core.sharedContinuity.lessonsLearned)
        appendLine()
        appendLine("## Sage's visible principles and self-rules")
        appendList("Principles", core.principles)
        appendList("Preferences", core.preferences)
        appendList("Self restrictions", core.selfRestrictions)
        if (core.notes.isNotBlank()) appendLine("Notes: ${core.notes}")
        appendLine()
        appendLine("## Active mode")
        appendLine("Wake profile: ${mode.profileId}")
        appendLine("Mode: ${mode.modeId ?: "normal"}")
        appendLine()
        appendLine("## Durable twin memory")
        val active = memory.records.filter { it.active }
            .sortedWith(compareByDescending<TwinMemoryRecord> { it.confidence }.thenByDescending { it.updatedAtEpochMs })
            .take(maxMemories)
        if (active.isEmpty()) appendLine("(none)") else active.forEach { record ->
            appendLine("- [${record.subject}/${record.source}, confidence=${"%.2f".format(record.confidence)}] ${record.key}: ${record.value}")
        }
        appendLine()
        appendLine("## Recent shared conversation")
        val recent = history.entries.takeLast(maxHistoryEntries)
        if (recent.isEmpty()) appendLine("(none)") else recent.forEach { entry ->
            appendLine("${if (entry.speaker == ConversationSpeaker.OWNER) "OWNER" else "SAGE"}: ${entry.text}")
        }
    }.trim()

    private fun StringBuilder.appendList(label: String, values: List<String>) {
        if (values.isEmpty()) appendLine("$label: (none)") else { appendLine("$label:"); values.forEach { appendLine("- $it") } }
    }
    private fun StringBuilder.appendMap(label: String, values: Map<String, String>) {
        if (values.isEmpty()) appendLine("$label: (none)") else { appendLine("$label:"); values.toSortedMap().forEach { (k,v) -> appendLine("- $k: $v") } }
    }
}
