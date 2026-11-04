package com.pineapple.sageos2.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import com.pineapple.sageos2.capability.Capability
import com.pineapple.sageos2.capability.CapabilitySnapshot
import com.pineapple.sageos2.capability.CapabilityStatus

class BrainPromptBudgetTest {
    @Test fun growingHistoryAndChangedRequestLengthKeepIdentityPrefixStable() {
        val stable = listOf(
            "System guide: Sage answers naturally.",
            "# WHO I AM\n" + "Sage identity and continuity. ".repeat(30),
            "# OWNER CORE\nOWNER_RULE " + "Preserve owner instructions. ".repeat(40),
            "# ME\n" + "Speak directly. ".repeat(20)
        ).joinToString("\n\n")
        fun fit(history: String, request: String) = BrainPromptBudget.fitSystemContext(
            stable + "\n\n# RECENT CONVERSATION\n" + history, request, 1600)
        val first = fit("Owner: hello\nSage: hello", "How are you?")
        val second = fit("Owner: hello\nSage: " + "More context. ".repeat(80) + "NEWEST", "Tell me a little more about that")
        assertEquals(first.substringBefore("# RECENT CONVERSATION"), second.substringBefore("# RECENT CONVERSATION"))
        assertTrue(second.contains("OWNER_RULE"))
        assertTrue(second.endsWith("NEWEST"))
        assertTrue(second.length + "Tell me a little more about that".length <= 1600)
        val edited = BrainPromptBudget.fitSystemContext(
            stable.replace("OWNER_RULE", "NEW_OWNER_RULE") + "\n\n# RECENT CONVERSATION\nhello", "How are you?", 1600)
        assertTrue(edited.contains("NEW_OWNER_RULE"))
        assertFalse(edited.substringBefore("# RECENT CONVERSATION") == first.substringBefore("# RECENT CONVERSATION"))
    }

    @Test fun defaultBudgetMatchesTheInheritedTabletCeiling() {
        assertEquals(4_800, BrainPromptBudget.DEFAULT_COMBINED_CHARACTER_BUDGET)
        assertTrue(BrainPromptBudget.LOCAL_RESPONSE_GUIDE.contains("short finished reply"))
        assertFalse(BrainPromptBudget.LOCAL_RESPONSE_GUIDE.contains("/no_think"))
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

    @Test fun compactTabletBudgetKeepsSharedProjectSectionRepresented() {
        val context = listOf(
            "# WHO I AM\nSage " + "i".repeat(700),
            "# ME\nSelf " + "s".repeat(700),
            "# WHAT WE'VE BEEN DOING\nProject: Recovered Face Project = private face-swap app " + "p".repeat(900),
            "# THINGS I REMEMBER\nOther memory " + "m".repeat(900),
            "# RECENT CONVERSATION\nRecent " + "r".repeat(900)
        ).joinToString("\n\n")

        val fitted = BrainPromptBudget.fitSystemContext(context, "What is the name of our face-swap app?", 1_600)

        assertTrue(fitted.contains("# WHAT WE'VE BEEN DOING"))
        assertTrue(fitted.contains("Recovered Face Project"))
    }

    @Test fun compactColdBudgetKeepsIdentityOwnerCoreAndRecentTailRepresented() {
        val marker = "OWNER_RULE_SURVIVES_COLD_START"
        val context = listOf(
            "# WHO I AM\nSage identity " + "i".repeat(500),
            "# OWNER CORE\n$marker " + "c".repeat(900),
            "# WHAT MATTERS TO US\nOwner context " + "o".repeat(800),
            "# ME\nSelf " + "s".repeat(800),
            "# THINGS I REMEMBER\nMemory " + "m".repeat(900),
            "# RECENT CONVERSATION\nold " + "r".repeat(900) + " newest-turn"
        ).joinToString("\n\n")

        val fitted = BrainPromptBudget.fitSystemContext(context, "hello", 900)

        assertTrue(fitted.length <= 900 - "hello".length)
        assertTrue(fitted.startsWith("# WHO I AM"))
        assertTrue(fitted.contains("# OWNER CORE"))
        assertTrue(fitted.contains(marker))
        assertTrue(fitted.endsWith("newest-turn"))
    }

    @Test fun currentOwnerPromptIsNeverChangedByTheBudgeter() {
        val prompt = "keep every character of this request"
        BrainPromptBudget.fitSystemContext("x".repeat(20_000), prompt, 4_000)
        assertEquals("keep every character of this request", prompt)
    }

    @Test fun operationalTurnsKeepTheEntireRealToolContractAcrossColdWarmAndActiveCapabilities() {
        val identity = listOf(
            "# WHO I AM\nI am Sage, the owner's virtual twin. " + "identity ".repeat(80),
            "# OWNER CORE\nOWNER_RULE " + "owner continuity ".repeat(80),
            "# ME\n" + "Speak directly. ".repeat(80),
            "# THINGS I REMEMBER\n" + "Remembered facts. ".repeat(80),
            "# RECENT CONVERSATION\n" + "Past conversation. ".repeat(80)
        ).joinToString("\n\n")
        val prompt = "Could you please open Firefox and set a timer for five minutes?"
        for (active in listOf(false, true)) {
            val snapshot = CapabilitySnapshot(if (active) mapOf(
                Capability.SAGEOS_ROOT_BROKER to CapabilityStatus.ACTIVE,
                Capability.FORGE to CapabilityStatus.ACTIVE
            ) else emptyMap())
            val contract = BrainToolContextRenderer.render(snapshot)
            for (cold in listOf(true, false)) {
                val profile = BrainRequestPolicy.forPrompt(prompt, coldStart = cold)
                val fitted = BrainPromptBudget.fitSystemContext(identity + "\n\n" + contract,
                    prompt, profile.combinedCharacterBudget)
                assertTrue("active=$active cold=$cold", fitted.endsWith(contract))
                assertTrue(fitted.contains("OWNER_RULE"))
                assertTrue(fitted.contains("# WHO I AM"))
                assertTrue(fitted.length + prompt.length <= BrainPromptBudget.DEFAULT_COMBINED_CHARACTER_BUDGET)
            }
        }
    }

    @Test fun oversizedEssentialToolContextFailsInsteadOfClippingSignaturesOrOwnerRequest() {
        val contract = "# SAGE TOOL CONTRACT\n" + "device.open_app: app\n".repeat(300)
        assertThrows(IllegalArgumentException::class.java) {
            BrainPromptBudget.fitSystemContext("# WHO I AM\nSage identity\n\n" + contract, "Open Firefox", 1_200)
        }
    }

    @Test fun toolContinuationKeepsApplicableAppsMemoriesRecentHistoryAndTasksAlongsideTheIntactContract() {
        val sections = listOf(
            "# WHO I AM\nSage identity " + "i".repeat(700),
            "# OWNER CORE\nOWNER_RULE " + "o".repeat(700),
            "# WHAT MATTERS TO US\nOwner preferences " + "p".repeat(700),
            "# ME\nSelf context " + "s".repeat(700),
            "# APPS I KNOW\nFirefox [org.mozilla.firefox] " + "a".repeat(700),
            "# THINGS I REMEMBER\nRELEVANT_MEMORY " + "m".repeat(700),
            "# RECENT CONVERSATION\nOld conversation " + "h".repeat(700) + " NEWEST_TURN",
            "# ACTIVE / RECOVERABLE TASKS\nREQUESTED_TASK " + "t".repeat(700)
        )
        val prompt = "<SAGE_TOOL_RESULT>\nowner_goal=Open Firefox and finish the requested task\nname=device.open_app\nsuccess=true\n</SAGE_TOOL_RESULT>"
        val contract = BrainToolContextRenderer.render(CapabilitySnapshot(emptyMap()))
        for (cold in listOf(true, false)) {
            val profile = BrainRequestPolicy.forPrompt(prompt, coldStart = cold)
            val fitted = BrainPromptBudget.fitSystemContext(sections.joinToString("\n\n") + "\n\n" + contract,
                prompt, profile.combinedCharacterBudget)
            sections.forEach { section -> assertTrue(section.lineSequence().first(), fitted.contains(section.lineSequence().first())) }
            listOf("OWNER_RULE", "Firefox [org.mozilla.firefox]", "RELEVANT_MEMORY", "NEWEST_TURN", "REQUESTED_TASK")
                .forEach { assertTrue(it, fitted.contains(it)) }
            assertTrue(fitted.endsWith(contract))
            assertTrue(fitted.length + prompt.length <= BrainPromptBudget.DEFAULT_COMBINED_CHARACTER_BUDGET)
        }
    }

    @Test fun tooManyEssentialOperationalSectionsFailInsteadOfSilentlyDroppingContext() {
        val continuity = (1..60).joinToString("\n\n") { "# REQUIRED SECTION $it\n" + "context ".repeat(80) }
        val contract = BrainToolContextRenderer.render(CapabilitySnapshot(emptyMap()))
        assertThrows(IllegalArgumentException::class.java) {
            BrainPromptBudget.fitSystemContext(continuity + "\n\n" + contract, "Open Firefox", 1_200)
        }
    }

    @Test fun longStructuredContextKeepsIdentityCoreMemoryAndRecentHistoryRepresented() {
        val context = listOf(
            "System guide " + "g".repeat(1_000),
            "# WHO I AM\nSage identity " + "i".repeat(2_000),
            "# OWNER CORE\nKat's instructions " + "c".repeat(3_000),
            "# WHAT MATTERS TO US\nOwner context " + "o".repeat(2_000),
            "# ME\nSelf model " + "s".repeat(2_000),
            "# APPS I KNOW\nApps " + "a".repeat(2_000),
            "# THINGS I REMEMBER\nrelevant-coffee-memory " + "m".repeat(2_000),
            "# RECENT CONVERSATION\nold-turn " + "h".repeat(2_000) + " newest-turn"
        ).joinToString("\n\n")

        val fitted = BrainPromptBudget.fitSystemContext(context, "coffee follow-up", 4_800)

        assertTrue(fitted.length <= 4_800 - "coffee follow-up".length)
        assertTrue(fitted.contains("# WHO I AM"))
        assertTrue(fitted.contains("# OWNER CORE"))
        assertTrue(fitted.contains("# WHAT MATTERS TO US"))
        assertTrue(fitted.contains("# ME"))
        assertTrue(fitted.contains("# THINGS I REMEMBER"))
        assertTrue(fitted.contains("relevant-coffee-memory"))
        assertTrue(fitted.contains("# RECENT CONVERSATION"))
        assertTrue(fitted.endsWith("newest-turn"))
    }
}
