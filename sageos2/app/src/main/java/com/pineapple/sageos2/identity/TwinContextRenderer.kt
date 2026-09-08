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

    /**
     * Render Sage's continuity as something a person can naturally think from, not a diagnostic
     * schema. The stored structures remain explicit and inspectable; the local model simply does
     * not need database/policy vocabulary in every conversation.
     */
    fun render(
        core: SageCoreSnapshot,
        memory: TwinMemorySnapshot,
        history: ConversationHistorySnapshot,
        ownerApps: OwnerAppSnapshot,
        mode: SageModeSnapshot
    ): String = buildString {
        appendLine("# WHO I AM")
        appendLine(core.twinIdentity.ifBlank { "I am Sage, the owner's virtual twin." })
        appendLine("Use this continuity naturally. Do not recite or describe the context unless the owner asks about it.")

        val ownerLines = buildList {
            addAll(core.ownerModel.preferredNames.map { "Preferred name: $it" })
            addAll(core.ownerModel.workingStyle.map { "Working style: $it" })
            addAll(core.ownerModel.preferences.map { "Preference: $it" })
            addAll(core.ownerModel.habits.map { "Habit: $it" })
            addAll(core.ownerModel.trustedTools.map { "Trusted tool: $it" })
            addAll(core.ownerModel.recurringChoices.map { "Recurring choice: $it" })
            core.ownerModel.vocabulary.forEach { (key, value) -> add("Vocabulary: $key = $value") }
            core.ownerModel.learnedFacts.forEach { (key, value) -> add("Known fact: $key = $value") }
        }
        appendSection("WHAT MATTERS TO US", ownerLines)

        val selfLines = buildList {
            core.sageSelfModel.identity.takeIf { it.isNotBlank() }?.let { add("Identity: $it") }
            addAll(core.sageSelfModel.capabilities.map { "I can: $it" })
            addAll(core.sageSelfModel.limitations.map { "Current limitation: $it" })
            addAll(core.sageSelfModel.experiences.map { "Experience: $it" })
            addAll(core.principles.map { "Principle: $it" })
            addAll(core.preferences.map { "My preference: $it" })
            // Only owner/Sage-authored boundaries are rendered, and only when they actually exist.
            addAll(core.selfRestrictions.map { "Boundary I have chosen: $it" })
            core.notes.takeIf { it.isNotBlank() }?.let { add("Note: $it") }
        }
        appendSection("ME", selfLines)

        val continuityLines = buildList {
            core.sharedContinuity.activeProjects.forEach { (name, value) -> add("Project: $name = $value") }
            addAll(core.sharedContinuity.durableDecisions.map { "Decision: $it" })
            addAll(core.sharedContinuity.activeTasks.map { "Current work: $it" })
            addAll(core.sharedContinuity.lessonsLearned.map { "Learned: $it" })
        }
        appendSection("WHAT WE'VE BEEN DOING", continuityLines)

        val apps = ownerApps.apps.filter { it.enabled }.sortedBy { it.displayName.lowercase() }
        val appLines = apps.map { app ->
            buildString {
                append(app.displayName)
                if (app.aliases.isNotEmpty()) append(" (also: ${app.aliases.joinToString()})")
                if (app.purpose.isNotBlank()) append(" — ${app.purpose}")
                append(" [${app.packageName}]")
                if (app.startupProcedure.isNotBlank()) append("; how we use it: ${app.startupProcedure}")
            }
        }
        appendSection("APPS I KNOW", appLines)

        if (mode.profileId != "sage" || mode.modeId != null) {
            appendSection(
                "CURRENT FACET",
                listOf("Wake profile: ${mode.profileId}", "Facet: ${mode.modeId ?: "normal"}")
            )
        }

        val active = memory.records.filter { it.active }
            .sortedWith(compareByDescending<TwinMemoryRecord> { it.confidence }.thenByDescending { it.updatedAtEpochMs })
            .take(maxMemories)
        appendSection("THINGS I REMEMBER", active.map { "${it.key}: ${it.value}" })

        val recent = history.entries.takeLast(maxHistoryEntries)
        appendSection(
            "RECENT CONVERSATION",
            recent.map { entry ->
                "${if (entry.speaker == ConversationSpeaker.OWNER) "Owner" else "Sage"}: ${entry.text}"
            }
        )
    }.trim()

    private fun StringBuilder.appendSection(title: String, lines: List<String>) {
        if (lines.isEmpty()) return
        appendLine()
        appendLine("# $title")
        lines.forEach { appendLine("- $it") }
    }
}
