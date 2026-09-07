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
        assertTrue(profile.outputTokens in 4..12)
        assertEquals(320, profile.combinedCharacterBudget)
    }

    @Test fun ordinaryAndConversationalTurnsStayInsideInheritedTabletLimits() {
        val ordinary = BrainRequestPolicy.forPrompt("Can you hear me?")
        assertEquals(3_600, ordinary.combinedCharacterBudget)
        assertEquals(16, ordinary.outputTokens)
        assertTrue(ordinary.deterministic)
        assertNull(ordinary.expectedLiteral)

        val conversational = BrainRequestPolicy.forPrompt("Continue our conversation from earlier")
        assertEquals(4_800, conversational.combinedCharacterBudget)
        assertEquals(24, conversational.outputTokens)
        assertFalse(conversational.deterministic)
    }

    @Test fun literalComparisonAllowsOnlyWrappingQuotesAndWhitespaceDifferences() {
        assertTrue(BrainRequestPolicy.literalMatches("  \"Brain   online.\" ", "Brain online."))
        assertFalse(BrainRequestPolicy.literalMatches("Brain is online.", "Brain online."))
    }
}
