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
        "You are Sage, the owner's virtual twin. Reply naturally, directly, and completely. Prefer a short finished reply."
    private const val MINIMUM_CONTEXT_CHARACTERS = 640
    private const val OMISSION = "\n\n[Older context omitted to fit this local turn.]\n\n"
    private const val SECTION_OMISSION = "\n[Section shortened for this local turn.]\n"
    private const val TASK_SECTION = "# ACTIVE / RECOVERABLE TASKS"
    private const val TOOL_SECTION = "# SAGE TOOL CONTRACT"
    private const val MINIMUM_OPERATIONAL_IDENTITY_CHARACTERS = 320

    fun fitSystemContext(
        systemContext: String,
        ownerPrompt: String,
        combinedCharacterBudget: Int = DEFAULT_COMBINED_CHARACTER_BUDGET,
        requiredContext: String = ""
    ): String {
        require(combinedCharacterBudget > MINIMUM_CONTEXT_CHARACTERS + OMISSION.length)
        val profile = BrainRequestPolicy.forPrompt(ownerPrompt)
        val scopedContext = scopeEngineeringContext(systemContext, profile)
        if (requiredContext.isBlank() && profile.includeToolContext) {
            val sections = semanticSections(scopedContext)
            val contracts = sections.filter { it.title == "SAGE TOOL CONTRACT" }
            if (contracts.isNotEmpty()) {
                // Tool signatures and the control-output example are a single contract. Cutting
                // out its middle can leave "never invent a tool" followed by no usable tools.
                val contract = contracts.joinToString("\n\n") { it.text }
                val continuity = sections.filter { it.title != "SAGE TOOL CONTRACT" }
                require(continuity.isNotEmpty()) { "No room for Sage identity in this operational turn" }
                // Tools need the selected app, relevant memory, recent conversation and requested
                // task context too. Reserve every applicable section's semantic minimum instead
                // of fitting only identity and silently dropping the context that grounds actions.
                val minimumContinuity = maxOf(MINIMUM_OPERATIONAL_IDENTITY_CHARACTERS,
                    continuity.sumOf { minimumQuota(it) } + (continuity.size - 1) * 2)
                val minimumBudget = ownerPrompt.length.toLong() + contract.length + 2 +
                    minimumContinuity
                require(minimumBudget <= DEFAULT_COMBINED_CHARACTER_BUDGET) {
                    "Owner request and required tool contract exceed this local turn's budget"
                }
                // Grow only when essential operational context cannot fit the latency profile;
                // retain the existing inherited ceiling and keep ordinary chat profiles intact.
                val operationalBudget = maxOf(combinedCharacterBudget, minimumBudget.toInt())
                val fittedContinuity = fitSemanticSections(continuity,
                    operationalBudget - ownerPrompt.length - contract.length - 2)
                return fittedContinuity + "\n\n" + contract
            }
        }
        if (requiredContext.isNotBlank()) {
            // Never cut a tool signature or a saved procedure. Reserve it first and keep
            // identity in the remaining space, without enlarging the local turn budget.
            val identityAllowance = combinedCharacterBudget - ownerPrompt.length - requiredContext.length - 2
            require(identityAllowance >= MINIMUM_OPERATIONAL_IDENTITY_CHARACTERS) { "Saved startup context exceeds this local turn's budget" }
            val sections = semanticSections(scopedContext)
            return fitRequiredContext(sections, identityAllowance, requiredContext)
        }
        val allowance = (combinedCharacterBudget - ownerPrompt.length)
            .coerceAtLeast(MINIMUM_CONTEXT_CHARACTERS)
        val sections = semanticSections(scopedContext)
        if (sections.size > 1) {
            fitStableIdentityPrefix(sections, allowance, combinedCharacterBudget)?.let { return it }
            if (scopedContext.length <= allowance) return scopedContext
            return fitSemanticSections(sections, allowance)
        }
        if (scopedContext.length <= allowance) return scopedContext

        val contentAllowance = (allowance - OMISSION.length).coerceAtLeast(2)
        val headLength = (contentAllowance * 3 / 5).coerceAtLeast(1)
        val tailLength = (contentAllowance - headLength).coerceAtLeast(1)
        return scopedContext.take(headLength).trimEnd() +
            OMISSION +
            scopedContext.takeLast(tailLength).trimStart()
    }

    private fun fitRequiredContext(sections: List<PromptSection>, identityAllowance: Int, required: String): String {
        val selected = mutableSetOf<Int>()
        var used = 0
        val priority = sections.indices.sortedBy { index ->
            when (sections[index].title) {
                "OWNER CORE" -> 0
                "WHO I AM" -> 1
                "WHAT MATTERS TO US" -> 2
                "ME" -> 3
                else -> 4
            }
        }
        for (index in priority) {
            val needed = minimumQuota(sections[index]) + if (selected.isEmpty()) 0 else 2
            if (used + needed <= identityAllowance) {
                selected += index
                used += needed
            }
        }
        require(selected.isNotEmpty()) { "No room for Sage identity in this operational turn" }
        val identity = fitSemanticSections(sections.filterIndexed { index, _ -> index in selected }, identityAllowance)
        return identity + "\n\n" + required
    }

    /**
     * History length and request-dependent memories must not resize the identity prefix on
     * every turn. Reserve a fixed share of this profile's budget for durable sections. The
     * remaining space goes to dynamic context; unused dynamic space does not enlarge identity.
     * This affects only the rendered prompt, never the stored Core or memory. Tiny budgets
     * fall back to the existing all-section fitter rather than dropping semantic sections.
     */
    private fun fitStableIdentityPrefix(
        sections: List<PromptSection>, allowance: Int, profileBudget: Int
    ): String? {
        val identity = sections.filter { it.title in STABLE_IDENTITY_SECTIONS }
        val dynamic = sections.filter { it.title !in STABLE_IDENTITY_SECTIONS }
        if (identity.isEmpty() || dynamic.isEmpty()) return null
        val identityBudget = profileBudget * 55 / 100
        fun minimum(parts: List<PromptSection>) =
            parts.sumOf { minimumQuota(it) } + (parts.size - 1).coerceAtLeast(0) * 2
        if (identityBudget < minimum(identity)) return null
        val prefix = fitSemanticSections(identity, identityBudget)
        val dynamicBudget = allowance - prefix.length - 2
        if (dynamicBudget < minimum(dynamic)) return null
        return prefix + "\n\n" + fitSemanticSections(dynamic, dynamicBudget)
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
    private val STABLE_IDENTITY_SECTIONS = setOf(
        "SYSTEM GUIDE", "WHO I AM", "OWNER CORE", "WHAT MATTERS TO US", "ME",
        "WHAT WE'VE BEEN DOING", "CURRENT FACET"
    )
    private val HEAD_ONLY_SECTIONS = setOf(
        "OWNER CORE",
        "WHAT MATTERS TO US",
        "ME",
        "WHAT WE'VE BEEN DOING",
        "THINGS I REMEMBER",
        "ACTIVE / RECOVERABLE TASKS"
    )
}
