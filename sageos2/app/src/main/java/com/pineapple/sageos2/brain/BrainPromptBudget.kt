package com.pineapple.sageos2.brain

/**
 * Keeps durable identity at the front and recent/tool context at the end when a long-lived Sage
 * history outgrows the local model's prompt window. The current owner request is never truncated.
 */
object BrainPromptBudget {
    const val DEFAULT_COMBINED_CHARACTER_BUDGET = 12_000
    private const val MINIMUM_CONTEXT_CHARACTERS = 1_200
    private const val OMISSION = "\n\n[Older context omitted to fit this local turn.]\n\n"

    fun fitSystemContext(
        systemContext: String,
        ownerPrompt: String,
        combinedCharacterBudget: Int = DEFAULT_COMBINED_CHARACTER_BUDGET
    ): String {
        require(combinedCharacterBudget > MINIMUM_CONTEXT_CHARACTERS + OMISSION.length)
        val allowance = (combinedCharacterBudget - ownerPrompt.length)
            .coerceAtLeast(MINIMUM_CONTEXT_CHARACTERS)
        if (systemContext.length <= allowance) return systemContext

        val contentAllowance = (allowance - OMISSION.length).coerceAtLeast(2)
        val headLength = (contentAllowance * 3 / 5).coerceAtLeast(1)
        val tailLength = (contentAllowance - headLength).coerceAtLeast(1)
        return systemContext.take(headLength).trimEnd() +
            OMISSION +
            systemContext.takeLast(tailLength).trimStart()
    }
}
