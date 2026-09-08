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
        assertEquals(3_600, ordinary.combinedCharacterBudget)
        assertEquals(16, ordinary.outputTokens)
        assertTrue(ordinary.deterministic)
        assertTrue(ordinary.includeTwinContext)
        assertFalse(ordinary.includeToolContext)
        assertFalse(ordinary.includeTaskContext)
        assertNull(ordinary.expectedLiteral)

        val personal = BrainRequestPolicy.forPrompt("Do you remember what I said earlier?")
        assertEquals(4_800, personal.combinedCharacterBudget)
        assertEquals(24, personal.outputTokens)
        assertFalse(personal.deterministic)
        assertFalse(personal.includeToolContext)
        assertFalse(personal.includeTaskContext)
    }

    @Test fun actionAndContinuationContextAreOptIn() {
        val action = BrainRequestPolicy.forPrompt("Could you open Firefox for me?")
        assertTrue(action.includeToolContext)
        assertFalse(action.includeTaskContext)

        val continuation = BrainRequestPolicy.forPrompt("Continue the task from where we left off")
        assertTrue(continuation.includeTaskContext)
        assertFalse(continuation.includeToolContext)
    }

    @Test fun literalComparisonAllowsOnlyWrappingQuotesAndWhitespaceDifferences() {
        assertTrue(BrainRequestPolicy.literalMatches("  \"Brain   online.\" ", "Brain online."))
        assertFalse(BrainRequestPolicy.literalMatches("Brain is online.", "Brain online."))
    }
}
