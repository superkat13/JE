package com.pineapple.sageos2.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakeProfileTokenTest {
    @Test fun builtInSageTokensMatchVerifiedBpeModel() {
        assertEquals("▁S AGE", BuiltInWakeTokens.forPhrase("sage"))
        assertEquals("▁S AGE ▁G LI T CH", BuiltInWakeTokens.forPhrase("SAGE   GLITCH"))
    }

    @Test fun customProfileCanCarryCompiledTokensWithoutChangingSpeechArchitecture() {
        val profile = WakeProfile("custom", "Custom", listOf("hello sage"), compiledPhrases = mapOf("hello sage" to "TOKENIZED"))
        assertEquals("TOKENIZED", profile.compiledTokensFor("HELLO SAGE"))
        assertNull(profile.compiledTokensFor("something else"))
    }
}
