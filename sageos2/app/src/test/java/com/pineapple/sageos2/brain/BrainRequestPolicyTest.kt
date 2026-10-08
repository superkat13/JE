package com.pineapple.sageos2.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainRequestPolicyTest {
    @Test fun exactCheckIsSmallDeterministicAndCarriesNoTwinContext() {
        val profile = BrainRequestPolicy.forPrompt(BrainRequestPolicy.SELF_CHECK_PROMPT)
        assertEquals("Brain online.", profile.expectedLiteral)
        assertTrue(profile.deterministic)
        assertFalse(profile.includeTwinContext)
        assertFalse(profile.includeToolContext)
        assertFalse(profile.includeTaskContext)
        assertTrue(profile.outputTokens in 4..12)
        assertEquals(320, profile.combinedCharacterBudget)
    }

    @Test fun ordinaryChatCarriesNoEngineeringContracts() {
        val ordinary = BrainRequestPolicy.forPrompt("Can you hear me?")
        assertEquals(1_600, ordinary.combinedCharacterBudget)
        assertEquals(40, ordinary.outputTokens)
        assertTrue(ordinary.deterministic)
        assertTrue(ordinary.includeTwinContext)
        assertFalse(ordinary.includeToolContext)
        assertFalse(ordinary.includeTaskContext)
        assertNull(ordinary.expectedLiteral)

        val coldOrdinary = BrainRequestPolicy.forPrompt("Can you hear me?", coldStart = true)
        assertEquals(900, coldOrdinary.combinedCharacterBudget)
        assertEquals(40, coldOrdinary.outputTokens)
        assertTrue(coldOrdinary.includeTwinContext)

        val personal = BrainRequestPolicy.forPrompt("Do you remember what I said earlier?")
        assertEquals(2_000, personal.combinedCharacterBudget)
        assertEquals(48, personal.outputTokens)
        assertFalse(personal.deterministic)
        assertFalse(personal.includeToolContext)
        assertFalse(personal.includeTaskContext)

        val coldPersonal = BrainRequestPolicy.forPrompt("Do you remember what I said earlier?", coldStart = true)
        assertEquals(1_200, coldPersonal.combinedCharacterBudget)
        assertEquals(48, coldPersonal.outputTokens)
    }

    @Test fun actionAndContinuationContextAreOptIn() {
        val action = BrainRequestPolicy.forPrompt("Could you open Firefox for me?")
        assertEquals(2_000, action.combinedCharacterBudget)
        assertTrue(action.includeToolContext)
        assertFalse(action.includeTaskContext)
        assertEquals(1_200, BrainRequestPolicy.forPrompt("Could you open Firefox for me?", coldStart = true).combinedCharacterBudget)

        val diagnosticAction = BrainRequestPolicy.forPrompt("check your root identity")
        assertTrue(diagnosticAction.includeToolContext)

        val continuation = BrainRequestPolicy.forPrompt("Continue the task from where we left off")
        assertTrue(continuation.includeTaskContext)
        assertFalse(continuation.includeToolContext)

        val toolResult = BrainRequestPolicy.forPrompt("SAGE_TOOL_RESULT\naction=open.app\nsuccess=true")
        assertTrue(toolResult.includeToolContext)
        assertTrue(toolResult.includeTaskContext)

        val realToolResult = BrainRequestPolicy.forPrompt(
            "<SAGE_TOOL_RESULT>\nname=root.health\nsuccess=true\n</SAGE_TOOL_RESULT>"
        )
        assertTrue(realToolResult.includeToolContext)
        assertTrue(realToolResult.includeTaskContext)
    }


    @Test fun goalVerificationAlwaysCarriesTaskAndToolContext() {
        val prompt = """
            <SAGE_GOAL_VERIFY>
            owner_goal=fix the printer
            completed_tool_calls=2
            verification_round=1
            </SAGE_GOAL_VERIFY>
            Continue the same owner goal and verify the result.
        """.trimIndent()

        val warm = BrainRequestPolicy.forPrompt(prompt)
        assertTrue(warm.includeToolContext)
        assertTrue(warm.includeTaskContext)
        assertTrue(warm.includeTwinContext)
        assertFalse(warm.deterministic)
        assertEquals(2_200, warm.combinedCharacterBudget)
        assertEquals(48, warm.outputTokens)

        val cold = BrainRequestPolicy.forPrompt(prompt, coldStart = true)
        assertEquals(1_400, cold.combinedCharacterBudget)
    }

    @Test fun recoveredGoalAlwaysCarriesTaskAndToolContext() {
        val prompt = """
            <SAGE_GOAL_RECOVER>
            owner_goal=finish the printer repair
            prior_phase=capability
            completed_tool_calls=1
            recovery_depth=1
            </SAGE_GOAL_RECOVER>
            Resume safely.
        """.trimIndent()

        val profile = BrainRequestPolicy.forPrompt(prompt)
        assertTrue(profile.includeToolContext)
        assertTrue(profile.includeTaskContext)
        assertTrue(profile.includeTwinContext)
        assertFalse(profile.deterministic)
        assertEquals(2_200, profile.combinedCharacterBudget)
        assertEquals(48, profile.outputTokens)
    }

    @Test fun literalComparisonAllowsOnlyWrappingQuotesAndWhitespaceDifferences() {
        assertTrue(BrainRequestPolicy.literalMatches("  \"Brain   online.\" ", "Brain online."))
        assertFalse(BrainRequestPolicy.literalMatches("Brain is online.", "Brain online."))
    }
}
