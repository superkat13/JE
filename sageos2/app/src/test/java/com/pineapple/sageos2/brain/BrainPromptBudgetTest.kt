package com.pineapple.sageos2.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainPromptBudgetTest {
    @Test fun shortContextIsUntouched() {
        assertEquals("identity and tools", BrainPromptBudget.fitSystemContext("identity and tools", "hello"))
    }

    @Test fun longContextKeepsIdentityFrontAndRecentToolTail() {
        val head = "IDENTITY:" + "a".repeat(8_000)
        val tail = "TOOLS:" + "z".repeat(8_000)
        val fitted = BrainPromptBudget.fitSystemContext(head + tail, "owner prompt", 4_000)
        assertTrue(fitted.startsWith("IDENTITY:"))
        assertTrue(fitted.endsWith("z".repeat(100)))
        assertTrue(fitted.contains("Older context omitted"))
        assertTrue(fitted.length <= 4_000 - "owner prompt".length)
    }

    @Test fun currentOwnerPromptIsNeverChangedByTheBudgeter() {
        val prompt = "keep every character of this request"
        BrainPromptBudget.fitSystemContext("x".repeat(20_000), prompt, 4_000)
        assertEquals("keep every character of this request", prompt)
    }
}
