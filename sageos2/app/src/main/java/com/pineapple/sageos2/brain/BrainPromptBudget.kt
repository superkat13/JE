package com.pineapple.sageos2.brain

/**
 * Keeps durable identity at the front and relevant recent context at the end when a long-lived
 * Sage history outgrows the local model's prompt window. The current owner request is never
 * truncated. Engineering contracts are removed from ordinary conversation before fitting.
 */
object BrainPromptBudget {
    // The inherited Qwen3-1.7B-Q8 model runs at roughly mobile-CPU speed on the VASOUN tablet.
    // Sage 1.33.x bounded conversational prompts to 4,800 formatted characters. Retain that
    // physically informed ceiling so durable context cannot consume the whole response window.
    const val DEFAULT_COMBINED_CHARACTER_BUDGET = 4_800
    const val LOCAL_RESPONSE_GUIDE =
        "Reply in Sage's own voice as the owner's virtual twin. Be natural, direct, and complete. Prefer a short finished reply to a long unfinished reply. /no_think"
    private const val MINIMUM_CONTEXT_CHARACTERS = 1_200
    private const val OMISSION = "\n\n[Older context omitted to fit this local turn.]\n\n"
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

        val contentAllowance = (allowance - OMISSION.length).coerceAtLeast(2)
        val headLength = (contentAllowance * 3 / 5).coerceAtLeast(1)
        val tailLength = (contentAllowance - headLength).coerceAtLeast(1)
        return scopedContext.take(headLength).trimEnd() +
            OMISSION +
            scopedContext.takeLast(tailLength).trimStart()
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
}
