package com.pineapple.sageos2.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SageCommandRouterTest {
    private val router = SageCommandRouter()

    @Test
    fun exactChickenTonightPhraseRoutesToSilentOwnerWorkflow() {
        val decision = router.route("Do you feel like chicken tonight?")
        assertEquals(SageRoute.OWNER_WORKFLOW, decision.route)
        assertEquals("chicken_tonight", decision.workflowId)
        assertEquals("do you feel like chicken tonight", decision.normalizedText)
    }

    @Test
    fun similarPhraseDoesNotLaunchChickenTonight() {
        val decision = router.route("I feel like chicken tonight")
        assertEquals(SageRoute.DEEP_REASONING, decision.route)
        assertNull(decision.workflowId)
    }

    @Test
    fun deepReasoningPreservesTheOwnersExactWords() {
        val decision = router.route("Can you read THIS, Sage? path=/My Folder/File.kt")
        assertEquals(SageRoute.DEEP_REASONING, decision.route)
        assertEquals("Can you read THIS, Sage? path=/My Folder/File.kt", decision.normalizedText)
    }

    @Test
    fun diagnosticShareRoutesAroundBrain() {
        val decision = router.route("Share diagnostic report")
        assertEquals(SageRoute.FAST_DEVICE, decision.route)
        assertNull(decision.workflowId)
        assertEquals("share diagnostic report", decision.normalizedText)
    }

    @Test
    fun brainModelImportRoutesAroundMissingBrain() {
        val decision = router.route("Import brain model")
        assertEquals(SageRoute.FAST_DEVICE, decision.route)
        assertNull(decision.workflowId)
        assertEquals("import brain model", decision.normalizedText)
    }
}
