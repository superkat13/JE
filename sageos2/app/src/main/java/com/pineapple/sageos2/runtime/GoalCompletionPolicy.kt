package com.pineapple.sageos2.runtime

import com.pineapple.sageos2.brain.BrainToolContextRenderer
import com.pineapple.sageos2.brain.BrainToolExecutionEvidence

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
    const val VERIFIED_MARKER = "SAGE_GOAL_VERIFIED"
    const val UNVERIFIED_MARKER = "SAGE_GOAL_UNVERIFIED"

    enum class VerificationStatus { VERIFIED, UNVERIFIED }
    data class VerificationResult(val status: VerificationStatus, val ownerFacingText: String)

    data class EvidenceAssessment(val independentlyVerified: Boolean, val reason: String)

    // Only observation-only operations can be evidence for their own read-only result.
    // Side-effect acknowledgement cannot establish the owner's requested postcondition.
    // Add new operation types only alongside a typed, independently checked verifier.
    private val observationalOperations = setOf(
        "root.health", "forge.health", "forge.tools", "forge.job",
        "device.screenshot", "device.read_notifications"
    )

    fun assessEvidence(ownerGoal: String, evidence: List<BrainToolExecutionEvidence>): EvidenceAssessment {
        if (evidence.isEmpty()) return EvidenceAssessment(false, "No observed capability result is available.")
        val failure = evidence.firstOrNull { !it.success }
        if (failure != null) {
            return EvidenceAssessment(false, "Capability ${failure.name} reported failure; the requested outcome is not confirmed.")
        }
        val unknown = evidence.firstOrNull { it.name !in observationalOperations }
        if (unknown != null) {
            return EvidenceAssessment(false, "Capability ${unknown.name} executed, but its requested final state was not independently observed.")
        }
        // A read-only response cannot prove a separate mutation requested in the
        // owner's actual goal. The goal must itself be observational.
        val mutationGoal = Regex(
            "(?i)\\b(?:change|set|lower|raise|increase|decrease|reduce|install|uninstall|open|launch|" +
                "tap|click|send|move|delete|restart|reboot|stop|start|mute|unmute|write|edit)\\b"
        )
        if (mutationGoal.containsMatchIn(ownerGoal)) {
            return EvidenceAssessment(false, "The owner's requested state change has no independently observed postcondition.")
        }
        return EvidenceAssessment(true, "Read-only capability results were observed without reported failures.")
    }

    fun verificationPrompt(
        ownerGoal: String,
        completedToolCalls: Int,
        verificationRound: Int,
        executionEvidence: List<BrainToolExecutionEvidence> = emptyList()
    ): String {
        require(completedToolCalls > 0)
        require(verificationRound > 0)
        val goal = ownerGoal
            .replace("\u0000", "")
            .replace("</SAGE_GOAL_VERIFY>", "[goal-verify-end]")
            .trim()
        return buildString {
            appendLine(VERIFY_MARKER)
            appendLine("owner_goal=$goal")
            appendLine("completed_tool_calls=$completedToolCalls")
            appendLine("verification_round=$verificationRound")
            appendLine(BrainToolContextRenderer.renderEvidence(executionEvidence))
            appendLine("</SAGE_GOAL_VERIFY>")
            appendLine("Continue the same owner goal. Do not declare success merely because an action ran.")
            appendLine("Verify the requested result using direct available evidence when possible.")
            appendLine("If another listed tool is needed to verify or correct the result, emit exactly one SAGE_TOOL block.")
            appendLine("Compare every part of the original goal with the observed results. A tool's success is not proof of the whole goal.")
            appendLine("Do not repeat successful mutations to verify them; use read-only evidence. Missing or omitted results are unknown.")
            appendLine("If the goal is verified, reply with $VERIFIED_MARKER on the first line and the owner-facing finished result after it.")
            append("If the goal cannot be verified with available evidence, reply with $UNVERIFIED_MARKER on the first line and exactly what remains unknown after it.")
        }.trim()
    }

    fun parseVerificationResponse(response: String): VerificationResult? {
        val normalized = response.replace("\u0000", "").trim()
        val firstLine = normalized.lineSequence().firstOrNull()?.trim() ?: return null
        val body = normalized.substringAfter('\n', "").trim()
        return when (firstLine) {
            VERIFIED_MARKER -> VerificationResult(
                VerificationStatus.VERIFIED,
                body.ifBlank { "Verified complete." }
            )
            UNVERIFIED_MARKER -> VerificationResult(
                VerificationStatus.UNVERIFIED,
                body.ifBlank { "The final state could not be verified." }
            )
            else -> null
        }
    }

    fun unverifiedFinal(proposedAnswer: String): String {
        val clean = proposedAnswer.trim()
        val suffix = "I completed the available steps, but I couldn't verify the final state yet."
        return if (clean.isBlank()) suffix else "$clean\n\n$suffix"
    }
}
