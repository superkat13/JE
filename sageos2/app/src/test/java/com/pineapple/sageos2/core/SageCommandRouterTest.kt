package com.pineapple.sageos2.core

import com.pineapple.sageos2.personal.SagePersonalResolution
import com.pineapple.sageos2.personal.SagePersonalResponder
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

    @Test
    fun familiarSageReplyRoutesWithoutWaitingForBrain() {
        val router = SageCommandRouter(object : SagePersonalResponder {
            override fun resolve(rawText: String) = SagePersonalResolution.Reply("I'm right here.")
        })
        val decision = router.route("How do I use Sage?")
        assertEquals(SageRoute.LOCAL_SAGE, decision.route)
        assertEquals("I'm right here.", decision.localReply)
    }

    @Test
    fun learnedPhraseCanResolveIntoExistingFastDevicePath() {
        val router = SageCommandRouter(object : SagePersonalResponder {
            override fun resolve(rawText: String) = SagePersonalResolution.RewrittenRequest("open YouTube")
        })
        val decision = router.route("movie time")
        assertEquals(SageRoute.FAST_DEVICE, decision.route)
        assertEquals("open youtube", decision.normalizedText)
    }

    @Test
    fun unsupportedDeviceWordsReachBrainInsteadOfDeadFastPath() {
        listOf(
            "Close YouTube",
            "Set timer for ten minutes",
            "Set alarm for seven",
            "Turn on Wi-Fi"
        ).forEach { request ->
            val decision = router.route(request)
            assertEquals(request, SageRoute.DEEP_REASONING, decision.route)
            assertEquals(request, decision.normalizedText)
        }
    }

    @Test
    fun everySupportedFastShapeStillRoutesAroundBrain() {
        listOf(
            "Open YouTube",
            "go back",
            "home",
            "show recents",
            "show notifications",
            "quick settings",
            "set a timer for 10 minutes",
            "set alarm for 7:30 pm",
            "take screenshot",
            "read notifications",
            "pause music",
            "next track",
            "tap Submit",
            "scroll down",
            "swipe left",
            "tap 420, 815",
            "volume up"
        ).forEach { request ->
            assertEquals(request, SageRoute.FAST_DEVICE, router.route(request).route)
        }
    }
}
