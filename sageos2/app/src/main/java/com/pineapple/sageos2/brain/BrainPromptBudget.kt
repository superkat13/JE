package com.pineapple.sageos2.brain

/**
 * Keeps durable identity at the front and relevant recent context at the end when a long-lived
 * Sage history outgrows the local model's prompt window. The current owner request is never
 * truncated. Engineering contracts are removed from ordinary conversation before fitting.
 */
object BrainPromptBudget {
    // The inherited model's exact family and quantization must come from its GGUF metadata.
    // Sage 1.33.x bounded conversational prompts to 4,800 formatted characters; retain that
    // physically informed, family-neutral ceiling until the installed model is inspected.
    const val DEFAULT_COMBINED_CHARACTER_BUDGET = 4_800
    const val LOCAL_RESPONSE_GUIDE =
        "Reply in Sage's own voice as the owner's virtual twin. Be natural, direct, and complete. Prefer a short finished reply to a long unfinished reply."
    private const val MINIMUM_CONTEXT_CHARACTERS = 1_200
    private const val OMISSION = "\n\n[Older context omitted to fit this local turn.]\n\n"
    private const val SECTION_OMISSION = "\n[Section shortened for this local turn.]\n"
    private const val TASK_SECTION = "# ACTIVE / RECOVERABLE TASKS"
    private const val TOOL_SECTION = "# SAGE TOOL CONTRACT"

    fun fitSystemContext(
        systemContext: String,
        ownerPrompt: String,
        combinedCharacterBudget: Int = DEFAULT_COMBINED_CHARACTER_BUDGET
    ): String {
        require(combinedCharacterBudget > MINIMUM_CONTEXT_CHARACTERS + OMISSION.length)
        val profile = BrainRequestPolicy.forPrompt(ownerPrompt)
        val scopedContext = scopeEngineeringContext(systemContext, profile)
        val allowance = (combinedCharacterBudget - ownerPrompt.length)
            .coerceAtLeast(MINIMUM_CONTEXT_CHARACTERS)
        if (scopedContext.length <= allowance) return scopedContext

        val sections = semanticSections(scopedContext)
        if (sections.size > 1) return fitSemanticSections(sections, allowance)

        val contentAllowance = (allowance - OMISSION.length).coerceAtLeast(2)
        val headLength = (contentAllowance * 3 / 5).coerceAtLeast(1)
        val tailLength = (contentAllowance - headLength).coerceAtLeast(1)
        return scopedContext.take(headLength).trimEnd() +
            OMISSION +
            scopedContext.takeLast(tailLength).trimStart()
    }

    /**
     * Keeps every applicable semantic section represented instead of allowing a single large
     * Core, app list, or tool contract to erase memory/history in the middle of the prompt.
     */
    private fun fitSemanticSections(sections: List<PromptSection>, allowance: Int): String {
        val separators = (sections.size - 1).coerceAtLeast(0) * 2
        val available = (allowance - separators).coerceAtLeast(1)
        val quotas = IntArray(sections.size) { index ->
            minOf(sections[index].text.length, minimumQuota(sections[index]))
        }
        if (quotas.sum() > available) {
            // Extremely small synthetic budgets retain the established safe fallback. Production
            // budgets leave enough room for all normal Sage section headings and a useful sample.
            val joined = sections.joinToString("\n\n") { it.text }
            val contentAllowance = (allowance - OMISSION.length).coerceAtLeast(2)
            val headLength = (contentAllowance * 3 / 5).coerceAtLeast(1)
            val tailLength = (contentAllowance - headLength).coerceAtLeast(1)
            return joined.take(headLength).trimEnd() + OMISSION +
                joined.takeLast(tailLength).trimStart()
        }

        var remaining = available - quotas.sum()
        while (remaining > 0) {
            val expandable = sections.indices.filter { quotas[it] < sections[it].text.length }
            if (expandable.isEmpty()) break
            val totalWeight = expandable.sumOf { sections[it].weight }
            var assigned = 0
            expandable.forEach { index ->
                val room = sections[index].text.length - quotas[index]
                val share = maxOf(1, remaining * sections[index].weight / totalWeight)
                val addition = minOf(room, share, remaining - assigned)
                if (addition > 0) {
                    quotas[index] += addition
                    assigned += addition
                }
            }
            if (assigned == 0) break
            remaining -= assigned
        }

        return sections.mapIndexed { index, section -> clipSection(section, quotas[index]) }
            .joinToString("\n\n")
            .take(allowance)
            .trimEnd()
    }

    private fun semanticSections(context: String): List<PromptSection> {
        val starts = SECTION_HEADER.findAll(context).map { it.range.first }.toList()
        if (starts.isEmpty()) return listOf(PromptSection("", context, 4, false))
        val result = mutableListOf<PromptSection>()
        if (starts.first() > 0) {
            context.substring(0, starts.first()).trim().takeIf { it.isNotEmpty() }?.let {
                result += PromptSection("SYSTEM GUIDE", it, 8, false)
            }
        }
        starts.forEachIndexed { index, start ->
            val end = starts.getOrNull(index + 1) ?: context.length
            val text = context.substring(start, end).trim()
            val title = text.lineSequence().first().removePrefix("# ").trim()
            result += PromptSection(
                title = title,
                text = text,
                weight = sectionWeight(title),
                preserveTail = title == "RECENT CONVERSATION"
            )
        }
        return result
    }

    private fun minimumQuota(section: PromptSection): Int {
        val headerLength = section.text.lineSequence().firstOrNull()?.length ?: 0
        return (headerLength + SECTION_OMISSION.length + 64).coerceAtMost(section.text.length)
    }

    private fun clipSection(section: PromptSection, quota: Int): String {
        if (section.text.length <= quota) return section.text
        val headerEnd = section.text.indexOf('\n').takeIf { it >= 0 } ?: section.text.length
        val header = section.text.take(headerEnd)
        val content = section.text.drop(headerEnd).trim()
        // Reserve one character for the explicit header/content separator used by clipped
        // sections. Some branches already get that separator from SECTION_OMISSION; leaving one
        // byte unused there is preferable to cutting the newest conversation tail at the final
        // aggregate bound.
        val contentQuota = (quota - header.length - SECTION_OMISSION.length - 1).coerceAtLeast(1)
        if (section.preserveTail) {
            return header + SECTION_OMISSION + content.takeLast(contentQuota).trimStart()
        }
        if (section.title in HEAD_ONLY_SECTIONS) {
            return header + "\n" + content.take(contentQuota).trimEnd() + SECTION_OMISSION.trimEnd()
        }
        val headLength = (contentQuota * 2 / 3).coerceAtLeast(1)
        val tailLength = (contentQuota - headLength).coerceAtLeast(0)
        return header + "\n" + content.take(headLength).trimEnd() + SECTION_OMISSION +
            content.takeLast(tailLength).trimStart()
    }

    private fun sectionWeight(title: String): Int = when (title) {
        "WHO I AM" -> 7
        "OWNER CORE" -> 10
        "WHAT MATTERS TO US", "ME" -> 6
        "WHAT WE'VE BEEN DOING" -> 9
        "THINGS I REMEMBER", "RECENT CONVERSATION" -> 8
        "ACTIVE / RECOVERABLE TASKS" -> 7
        "SAGE TOOL CONTRACT" -> 10
        "CURRENT FACET" -> 5
        else -> 3
    }

    private fun scopeEngineeringContext(context: String, profile: BrainRequestPolicy.Profile): String {
        var scoped = context
        if (!profile.includeTaskContext) scoped = removeSection(scoped, TASK_SECTION, TOOL_SECTION)
        if (!profile.includeToolContext) scoped = removeSection(scoped, TOOL_SECTION, null)
        return scoped.replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    private fun removeSection(context: String, startMarker: String, nextMarker: String?): String {
        val start = context.indexOf(startMarker)
        if (start < 0) return context
        val sectionStart = context.lastIndexOf("\n\n", start).takeIf { it >= 0 } ?: start
        val end = if (nextMarker == null) {
            context.length
        } else {
            context.indexOf(nextMarker, start + startMarker.length)
                .takeIf { it >= 0 }
                ?.let { context.lastIndexOf("\n\n", it).takeIf { boundary -> boundary >= 0 } ?: it }
                ?: context.length
        }
        return context.removeRange(sectionStart, end)
    }

    private data class PromptSection(
        val title: String,
        val text: String,
        val weight: Int,
        val preserveTail: Boolean
    )

    private val SECTION_HEADER = Regex("(?m)^# ")
    private val HEAD_ONLY_SECTIONS = setOf(
        "OWNER CORE",
        "WHAT MATTERS TO US",
        "ME",
        "WHAT WE'VE BEEN DOING",
        "THINGS I REMEMBER",
        "ACTIVE / RECOVERABLE TASKS"
    )
}
