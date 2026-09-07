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
}
