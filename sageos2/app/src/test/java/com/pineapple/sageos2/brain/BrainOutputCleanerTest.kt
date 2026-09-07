package com.pineapple.sageos2.brain

import org.junit.Assert.assertEquals
import org.junit.Test

class BrainOutputCleanerTest {
    @Test fun removesQwenThinkingAndControlTokensFromVisibleReply() {
        val raw = "<|assistant|><think>I should reason privately.</think>\nI'm here with you.<|im_end|>"
        assertEquals("I'm here with you.", BrainOutputCleaner.clean(raw))
    }

    @Test fun rejectsOutputThatContainsOnlyUnclosedHiddenReasoning() {
        assertEquals("", BrainOutputCleaner.clean("<think>still reasoning"))
        assertEquals("", BrainOutputCleaner.clean("<|assistant|><think>still reasoning"))
    }

    @Test fun preservesOrdinaryParagraphAndCodeFormatting() {
        val raw = "First paragraph.\n\n```kotlin\nval sage = true\n```"
        assertEquals(raw, BrainOutputCleaner.clean(raw))
    }
}
