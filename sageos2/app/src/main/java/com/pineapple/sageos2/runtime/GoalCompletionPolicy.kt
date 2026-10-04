package com.pineapple.sageos2.runtime

/**
 * Policy for the gap between "a tool returned" and "the owner's goal is actually done".
 *
 * Sage must not treat a plausible-sounding post-tool sentence as proof of completion. A tool-backed
 * turn gets an explicit verification pass. If verification discovers that more work is needed, the
 * normal structured-tool loop continues and verification runs again after the corrective action.
 */
object GoalCompletionPolicy {
    const val DEFAULT_MAX_TOOL_CALLS = 8
    const val DEFAULT_MAX_VERIFICATION_ROUNDS = 3
    const val VERIFY_MARKER = "<SAGE_GOAL_VERIFY>"

    fun verificationPrompt(
        ownerGoal: String,
        completedToolCalls: Int,
        verificationRound: Int
    ): String {
        require(completedToolCalls > 0)
        require(verificationRound > 0)
        val goal = ownerGoal
            .replace("\u0000", "")
            .replace("</SAGE_GOAL_VERIFY>", "[goal-verify-end]")
            .trim()
            .take(4_000)
        return buildString {
            appendLine(VERIFY_MARKER)
            appendLine("owner_goal=$goal")
            appendLine("completed_tool_calls=$completedToolCalls")
            appendLine("verification_round=$verificationRound")
            appendLine("</SAGE_GOAL_VERIFY>")
            appendLine("Continue the same owner goal. Do not declare success merely because an action ran.")
            appendLine("Verify the requested result using direct available evidence when possible.")
            appendLine("If another listed tool is needed to verify or correct the result, emit exactly one SAGE_TOOL block.")
            appendLine("Do not repeat an action that already succeeded unless verification itself requires it.")
            append("If the goal is achieved, answer the owner with the finished result. If it cannot be verified, say exactly what remains unknown.")
        }.trim()
    }

    fun unverifiedFinal(proposedAnswer: String): String {
        val clean = proposedAnswer.trim()
        val suffix = "I completed the available steps, but I couldn't verify the final state yet."
        return if (clean.isBlank()) suffix else "$clean\n\n$suffix"
    }
}
