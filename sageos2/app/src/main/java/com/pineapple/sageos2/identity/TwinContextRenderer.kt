package com.pineapple.sageos2.identity

import com.pineapple.sageos2.apps.OwnerAppSnapshot
import com.pineapple.sageos2.memory.ConversationEntry
import com.pineapple.sageos2.memory.ConversationHistorySnapshot
import com.pineapple.sageos2.memory.ConversationSpeaker
import com.pineapple.sageos2.memory.TwinMemoryRecord
import com.pineapple.sageos2.memory.TwinMemorySnapshot
import com.pineapple.sageos2.mode.SageModeSnapshot
import java.util.Locale

class TwinContextRenderer(
    private val maxMemories: Int = 64,
    private val maxHistoryEntries: Int = 12
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
        includeOperationalDetails: Boolean = false,
        currentRequest: String = ""
    ): String = buildString {
        appendLine("# WHO I AM")
        appendLine(core.twinIdentity.ifBlank { "I am Sage, the owner's virtual twin." })
        appendLine("Use this continuity naturally. Do not recite or describe it unless the owner asks.")

        legacyOwnerCore(core.notes)?.let { ownerCore ->
            appendLine()
            appendLine("# OWNER CORE")
            appendLine("This is the owner's authoritative Sage identity and behavior continuity. Apply it naturally. Device actions still require the runtime's capability, caller, transport, OS-integrity, and execution validation.")
            appendLine(ownerCore)
        }

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
            notesWithoutLegacyOwnerCore(core.notes).takeIf { it.isNotBlank() }?.let { add("Note: $it") }
        }
        appendSection("ME", selfLines)

        val continuityLines = buildList {
            core.sharedContinuity.activeProjects.toSortedMap().forEach { (name, value) -> add("Project: $name = $value") }
            addAll(core.sharedContinuity.durableDecisions.map { "Decision: $it" })
            addAll(core.sharedContinuity.activeTasks.map { "Current work: $it" })
            addAll(core.sharedContinuity.lessonsLearned.map { "Learned: $it" })
        }
        appendSection("WHAT WE'VE BEEN DOING", continuityLines)

        val requestTokens = searchableTokens(currentRequest)
        val includeApps = includeOperationalDetails || wantsAppContext(ownerApps, requestTokens)
        if (includeApps) {
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
        }

        if (mode.profileId != "sage" || mode.modeId != null) {
            appendSection(
                "CURRENT FACET",
                listOf("Wake profile: ${mode.profileId}", "Facet: ${mode.modeId ?: "normal"}")
            )
        }

        val active = memory.records.filter { it.active }
            .sortedWith(
                compareByDescending<TwinMemoryRecord> { relevanceScore(it, requestTokens) }
                    .thenByDescending { it.confidence }
                    .thenByDescending { it.updatedAtEpochMs }
            )
            .take(maxMemories)
        appendSection("THINGS I REMEMBER", active.map { it.value })

        val recent = compactConversation(history.entries).takeLast(maxHistoryEntries)
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

    private fun legacyOwnerCore(notes: String): String? {
        val markerIndex = notes.indexOf(LEGACY_OWNER_CORE_MARKER)
        if (markerIndex < 0) return null
        return notes.substring(markerIndex + LEGACY_OWNER_CORE_MARKER.length)
            .trimStart(':', ' ', '\n', '\r', '\t')
            .trim()
            .take(MAX_LEGACY_OWNER_CORE_PROMPT_CHARACTERS)
            .trimEnd()
            .takeIf { it.isNotEmpty() }
    }

    private fun notesWithoutLegacyOwnerCore(notes: String): String {
        val markerIndex = notes.indexOf(LEGACY_OWNER_CORE_MARKER)
        return if (markerIndex < 0) notes.trim() else notes.substring(0, markerIndex).trim()
    }

    private fun wantsAppContext(ownerApps: OwnerAppSnapshot, requestTokens: Set<String>): Boolean {
        if (requestTokens.any { it in APP_REQUEST_TOKENS }) return true
        return ownerApps.apps.any { app ->
            val appTokens = searchableTokens(
                buildString {
                    append(app.displayName)
                    append(' ')
                    append(app.aliases.joinToString(" "))
                    append(' ')
                    append(app.purpose)
                }
            )
            appTokens.any { it in requestTokens }
        }
    }

    private fun compactConversation(entries: List<ConversationEntry>): List<ConversationEntry> {
        val compacted = mutableListOf<ConversationEntry>()
        entries.forEach { entry ->
            if (entry.speaker == ConversationSpeaker.SAGE && isRuntimeFailureCopy(entry.text)) {
                return@forEach
            }
            val previous = compacted.lastOrNull()
            val sameAsPrevious = previous != null &&
                previous.speaker == entry.speaker &&
                normalizeConversationText(previous.text) == normalizeConversationText(entry.text)
            if (sameAsPrevious) compacted[compacted.lastIndex] = entry else compacted += entry
        }
        return compacted
    }

    private fun isRuntimeFailureCopy(text: String): Boolean {
        val normalized = normalizeConversationText(text)
        return RUNTIME_FAILURE_PREFIXES.any { normalized.startsWith(it) }
    }

    private fun normalizeConversationText(value: String): String =
        value.lowercase(Locale.US).trim().replace(Regex("\\s+"), " ")

    private fun relevanceScore(record: TwinMemoryRecord, requestTokens: Set<String>): Int {
        if (requestTokens.isEmpty()) return 0
        val memoryTokens = searchableTokens(record.key + " " + record.value)
        return requestTokens.count { it in memoryTokens }
    }

    private fun searchableTokens(value: String): Set<String> = SEARCHABLE_TOKEN
        .findAll(value.lowercase(Locale.US))
        .map { it.value }
        .filter { it.length >= 3 }
        .toSet()

    companion object {
        private const val LEGACY_OWNER_CORE_MARKER = "Imported Sage 1.33.3 owner instructions"
        // Matches the last-good Sage Core contribution limit; the complete source remains stored.
        private const val MAX_LEGACY_OWNER_CORE_PROMPT_CHARACTERS = 2_400
        private val APP_REQUEST_TOKENS = setOf("app", "apps", "application", "applications")
        private val RUNTIME_FAILURE_PREFIXES = listOf(
            "i was taking too long, so i stopped this turn",
            "i couldn't finish that reply, but i'm still here",
            "i couldn't finish getting ready to answer",
            "i hit a brain problem, but i'm still here"
        )
        private val SEARCHABLE_TOKEN = Regex("[a-z0-9]+")
    }
}
