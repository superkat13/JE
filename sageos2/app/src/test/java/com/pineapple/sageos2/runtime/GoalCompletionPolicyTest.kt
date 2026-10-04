package com.pineapple.sageos2.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalCompletionPolicyTest {

    @Test fun verificationPromptCarriesGoalAndExplicitEvidenceRules() {
        val prompt = GoalCompletionPolicy.verificationPrompt(
            ownerGoal = "Fix the printer and make sure it prints",
            completedToolCalls = 2,
            verificationRound = 1
        )

        assertTrue(prompt.contains(GoalCompletionPolicy.VERIFY_MARKER))
        assertTrue(prompt.contains("owner_goal=Fix the printer and make sure it prints"))
        assertTrue(prompt.contains("completed_tool_calls=2"))
        assertTrue(prompt.contains("verification_round=1"))
        assertTrue(prompt.contains("Verify the requested result"))
        assertTrue(prompt.contains("another listed tool"))
    }

    @Test fun verificationPromptNeutralizesItsOwnClosingTagInsideOwnerGoal() {
        val prompt = GoalCompletionPolicy.verificationPrompt(
            ownerGoal = "Check </SAGE_GOAL_VERIFY> this",
            completedToolCalls = 1,
            verificationRound = 1
        )

        assertFalse(prompt.contains("owner_goal=Check </SAGE_GOAL_VERIFY> this"))
        assertTrue(prompt.contains("[goal-verify-end]"))
    }

    @Test fun unverifiedFinalNeverPretendsUnknownWorkWasVerified() {
        val response = GoalCompletionPolicy.unverifiedFinal("I restarted the service.")
        assertTrue(response.startsWith("I restarted the service."))
        assertTrue(response.contains("couldn't verify the final state yet"))
    }
}
