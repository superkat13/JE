package com.pineapple.sageos2.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SageResponseCopyTest {
    @Test fun missingModelGivesTheOwnerARealNextStep() {
        val text = SageResponseCopy.forBrainFailure(
            "No configured Brain completed the request: local model file is missing"
        )
        assertTrue(text.contains("Settings → Local Brain"))
        assertFalse(text.contains("No configured Brain"))
    }

    @Test fun nativeFailureDoesNotLeakRawEngineLanguageIntoChat() {
        val text = SageResponseCopy.forBrainFailure(
            "sage-local-native: MODEL_ERROR (llama_decode failed with code 1)"
        )
        assertTrue(text.startsWith("I couldn't finish"))
        assertFalse(text.contains("llama_decode"))
        assertFalse(text.contains("MODEL_ERROR"))
    }

    @Test fun timeoutExplainsThatChatWasReleased() {
        val text = SageResponseCopy.forBrainFailure("local Brain timed out after 180 seconds")
        assertTrue(text.contains("stopped this turn"))
        assertTrue(text.contains("message is saved"))
    }
}
