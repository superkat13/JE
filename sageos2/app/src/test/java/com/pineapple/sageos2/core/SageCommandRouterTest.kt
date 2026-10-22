package com.pineapple.sageos2.core

import com.pineapple.sageos2.apps.OwnerAppProvider
import com.pineapple.sageos2.apps.OwnerAppRecord
import com.pineapple.sageos2.apps.OwnerAppSnapshot
import com.pineapple.sageos2.action.FastCommand
import com.pineapple.sageos2.action.FastCommandParser
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
    fun rememberedAppWithStartupProcedureRoutesThroughBrainInsteadOfBlindOpen() {
        val apps = object : OwnerAppProvider {
            override fun snapshot() = OwnerAppSnapshot(
                1,
                listOf(
                    OwnerAppRecord(
                        packageName = "org.mozilla.firefox",
                        displayName = "Firefox",
                        aliases = listOf("browser"),
                        purpose = "web",
                        startupProcedure = "Open Firefox\nTap Private browsing"
                    )
                )
            )
        }
        val router = SageCommandRouter(ownerApps = apps)

        val remembered = router.route("Open Firefox")
        val alias = router.route("launch browser")
        val ordinary = router.route("open YouTube")

        assertEquals(SageRoute.DEEP_REASONING, remembered.route)
        assertEquals("Open Firefox", remembered.normalizedText)
        assertEquals(SageRoute.DEEP_REASONING, alias.route)
        assertEquals(SageRoute.FAST_DEVICE, ordinary.route)
    }

    @Test
    fun rememberedAppWithoutStartupProcedureKeepsFastOpenPath() {
        val apps = object : OwnerAppProvider {
            override fun snapshot() = OwnerAppSnapshot(
                1,
                listOf(OwnerAppRecord("org.mozilla.firefox", "Firefox", startupProcedure = ""))
            )
        }
        assertEquals(
            SageRoute.FAST_DEVICE,
            SageCommandRouter(ownerApps = apps).route("open Firefox").route
        )
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

    @Test fun coordinatePayloadReachesTheFastExecutorWithoutLosingDecimalPunctuation() {
        val decision = router.route("tap 100.5, 200.5")

        assertEquals(SageRoute.FAST_DEVICE, decision.route)
        assertEquals(FastCommand.Tap(100.5f, 200.5f), FastCommandParser().parse(decision.normalizedText))
    }

    @Test fun compoundAppRequestReachesBrainWithTheEntireOwnerGoal() {
        val apps = object : OwnerAppProvider {
            override fun snapshot() = OwnerAppSnapshot(1, listOf(OwnerAppRecord("com.google.youtube", "YouTube")))
        }
        val router = SageCommandRouter(ownerApps = apps)
        listOf("Open YouTube and search for cats", "Open YouTube, then play my saved playlist",
            "Open YouTube and subscribe to this channel", "Open YouTube and bookmark the video").forEach { request ->
            val decision = router.route(request)
            assertEquals("opening the app cannot satisfy the remaining request", SageRoute.DEEP_REASONING, decision.route)
            assertEquals(request, decision.normalizedText)
        }
    }

    @Test fun completeSavedAppNameWithAConjunctionStillOpensDirectly() {
        val apps = object : OwnerAppProvider {
            override fun snapshot() = OwnerAppSnapshot(1,
                listOf(OwnerAppRecord("com.example.game", "Plants and Zombies")))
        }
        assertEquals(SageRoute.FAST_DEVICE,
            SageCommandRouter(ownerApps = apps).route("Open Plants and Zombies").route)
    }
}
