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
     * Gives the local Brain continuity in ordinary human language. Package names, startup
     * procedures, capability inventories, and technical limits stay out of normal conversation;
     * they are included only for a turn that is actually reasoning about a device action.
     */
    fun render(
        core: SageCoreSnapshot,
        memory: TwinMemorySnapshot,
        history: ConversationHistorySnapshot,
        ownerApps: OwnerAppSnapshot,
        mode: SageModeSnapshot,
        includeOperationalDetails: Boolean = false
    ): String = buildString {
        appendLine("# WHO I AM")
        appendLine(core.twinIdentity.ifBlank { "I am Sage, the owner's virtual twin." })
        appendLine("Use this continuity naturally. Do not recite or describe it unless the owner asks.")

        val ownerLines = buildList {
            addAll(core.ownerModel.preferredNames.map { "Preferred name: $it" })
            addAll(core.ownerModel.workingStyle.map { "Working style: $it" })
            addAll(core.ownerModel.preferences.map { "Preference: $it" })
            addAll(core.ownerModel.habits.map { "Habit: $it" })
            addAll(core.ownerModel.trustedTools.map { "Trusted tool: $it" })
            addAll(core.ownerModel.recurringChoices.map { "Recurring choice: $it" })
            core.ownerModel.vocabulary.toSortedMap().forEach { (key, value) -> add("Vocabulary: $key = $value") }
            core.ownerModel.learnedFacts.toSortedMap().forEach { (key, value) -> add("Known fact: $key = $value") }
        }
        appendSection("WHAT MATTERS TO US", ownerLines)

        val selfLines = buildList {
            core.sageSelfModel.identity.takeIf { it.isNotBlank() }?.let { add("Identity: $it") }
            addAll(core.sageSelfModel.experiences.map { "Experience: $it" })
            addAll(core.principles.map { "Principle: $it" })
            addAll(core.preferences.map { "My preference: $it" })
            addAll(core.selfRestrictions.map { "Boundary I have chosen: $it" })
            add("How I speak: ${mode.tone.brainDirection()}")
            if (includeOperationalDetails) {
                addAll(core.sageSelfModel.capabilities.map { "I can: $it" })
                addAll(core.sageSelfModel.limitations.map { "Current technical limit: $it" })
            }
            core.notes.takeIf { it.isNotBlank() }?.let { add("Note: $it") }
        }
        appendSection("ME", selfLines)

        val continuityLines = buildList {
            core.sharedContinuity.activeProjects.toSortedMap().forEach { (name, value) -> add("Project: $name = $value") }
            addAll(core.sharedContinuity.durableDecisions.map { "Decision: $it" })
            addAll(core.sharedContinuity.activeTasks.map { "Current work: $it" })
            addAll(core.sharedContinuity.lessonsLearned.map { "Learned: $it" })
        }
        appendSection("WHAT WE'VE BEEN DOING", continuityLines)

        val appLines = ownerApps.apps
            .filter { it.enabled }
            .sortedBy { it.displayName.lowercase() }
            .map { app ->
                buildString {
                    append(app.displayName)
                    if (app.aliases.isNotEmpty()) append(" (also: ${app.aliases.joinToString()})")
                    if (app.purpose.isNotBlank()) append(" — ${app.purpose}")
                    if (includeOperationalDetails) {
                        append(" [${app.packageName}]")
                        if (app.startupProcedure.isNotBlank()) append("; how we use it: ${app.startupProcedure}")
                    }
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
        appendSection("THINGS I REMEMBER", active.map { it.value })

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
