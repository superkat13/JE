package com.pineapple.sageos2.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainPromptBudgetTest {
    @Test fun defaultBudgetMatchesTheInheritedTabletCeiling() {
        assertEquals(4_800, BrainPromptBudget.DEFAULT_COMBINED_CHARACTER_BUDGET)
        assertTrue(BrainPromptBudget.LOCAL_RESPONSE_GUIDE.contains("short finished reply"))
        assertTrue(BrainPromptBudget.LOCAL_RESPONSE_GUIDE.contains("/no_think"))
    }

    @Test fun shortContextIsUntouchedWhenItHasNoEngineeringSections() {
        assertEquals("identity and memories", BrainPromptBudget.fitSystemContext("identity and memories", "hello"))
    }

    @Test fun ordinaryConversationDropsTaskAndToolContracts() {
        val context = """
            # SAGE TWIN CONTEXT
            Sage and owner continuity.

            # ACTIVE / RECOVERABLE TASKS
            - engineering recovery detail
            Never replay side effects.

            # SAGE TOOL CONTRACT
            Tool calls are structured control output.
            - root.health
        """.trimIndent()

        val fitted = BrainPromptBudget.fitSystemContext(context, "How are you today?", 4_000)
        assertTrue(fitted.contains("SAGE TWIN CONTEXT"))
        assertFalse(fitted.contains("ACTIVE / RECOVERABLE TASKS"))
        assertFalse(fitted.contains("SAGE TOOL CONTRACT"))
        assertFalse(fitted.contains("root.health"))
    }

    @Test fun actionTurnGetsToolsButNotUnrelatedRecoveryContract() {
        val context = """
            # SAGE TWIN CONTEXT
            Sage and owner continuity.

            # ACTIVE / RECOVERABLE TASKS
            - old task

            # SAGE TOOL CONTRACT
            - forge.health
        """.trimIndent()

        val fitted = BrainPromptBudget.fitSystemContext(context, "Could you open Firefox for me?", 4_000)
        assertTrue(fitted.contains("SAGE TWIN CONTEXT"))
        assertTrue(fitted.contains("SAGE TOOL CONTRACT"))
        assertTrue(fitted.contains("forge.health"))
        assertFalse(fitted.contains("ACTIVE / RECOVERABLE TASKS"))
    }

    @Test fun continuationTurnGetsRecoveryContextWithoutToolManual() {
        val context = """
            # SAGE TWIN CONTEXT
            Sage and owner continuity.

            # ACTIVE / RECOVERABLE TASKS
            - resume me

            # SAGE TOOL CONTRACT
            - root.health
        """.trimIndent()

        val fitted = BrainPromptBudget.fitSystemContext(context, "Continue the task from where we left off", 4_000)
        assertTrue(fitted.contains("ACTIVE / RECOVERABLE TASKS"))
        assertTrue(fitted.contains("resume me"))
        assertFalse(fitted.contains("SAGE TOOL CONTRACT"))
        assertFalse(fitted.contains("root.health"))
    }

    @Test fun longContextKeepsIdentityFrontAndRelevantTail() {
        val head = "IDENTITY:" + "a".repeat(8_000)
        val tail = "RECENT:" + "z".repeat(8_000)
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
