package com.pineapple.sageos2.speech.voicerepair

import com.pineapple.sageos2.personal.EmptySagePersonalResponder
import com.pineapple.sageos2.personal.SagePersonalResolution
import com.pineapple.sageos2.personal.SagePersonalResponder
import org.junit.Assert.*
import org.junit.Test

class VoiceRepairResponderTest {
    private val manager = VoiceRepairSessionManager()
    private val responder = VoiceRepairResponder(EmptySagePersonalResponder, manager)

    @Test fun exactOwnerRequestIsLocalAndHonestWhenControllerMissing() {
        val reply = (responder.resolve("fix your hearing") as SagePersonalResolution.Reply).text
        assertTrue(reply.contains("not connected"))
        assertTrue(reply.contains("not started"))
        assertNull(manager.current())
    }

    @Test fun allRepairAliasesAvoidConversationalFallback() {
        val inner = object : SagePersonalResponder {
            override fun resolve(rawText: String): SagePersonalResolution? = error("must not reach personality")
        }
        val local = VoiceRepairResponder(inner, manager)
        (VoiceRepairResponder.FIX_COMMANDS + VoiceRepairResponder.DIAGNOSE_COMMANDS).forEach {
            assertNotNull(local.resolve(it))
        }
    }

    @Test fun politeOwnerVariantsWork() {
        listOf("Sage, fix your hearing!", "Please Sage fix your hearing.", "Sage please fix your hearing please").forEach {
            assertNotNull(responder.resolve(it))
        }
    }

    @Test fun connectedControllerReceivesRequestExactlyOnce() {
        var calls = 0
        val local = VoiceRepairResponder(EmptySagePersonalResponder, manager, startRepair = {
            calls++
            "Controller accepted the request."
        })
        assertEquals("Controller accepted the request.", (local.resolve("fix your hearing") as SagePersonalResolution.Reply).text)
        assertEquals(1, calls)
    }

    @Test fun cancelledSessionIsNotReportedActiveAgain() {
        manager.startSession("hello")
        assertTrue((responder.resolve("cancel voice repair") as SagePersonalResolution.Reply).text.contains("Cancelled"))
        assertTrue((responder.resolve("cancel voice repair") as SagePersonalResolution.Reply).text.contains("No active"))
    }

    @Test fun unrelatedConversationStillDelegates() {
        val inner = object : SagePersonalResponder {
            override fun resolve(rawText: String) = SagePersonalResolution.Reply("inner: $rawText")
        }
        val local = VoiceRepairResponder(inner, manager)
        assertEquals("inner: hello", (local.resolve("hello") as SagePersonalResolution.Reply).text)
        assertNull(responder.resolve("tell me a story about fixing your hearing"))
    }
    @Test fun ownerRequestRoutesLocallyWithoutBrainOrDeviceAction() {
        val router = com.pineapple.sageos2.core.SageCommandRouter(personal = responder)
        val decision = router.route("fix your hearing")
        assertEquals(com.pineapple.sageos2.core.SageRoute.LOCAL_SAGE, decision.route)
        assertTrue(decision.localReply!!.contains("not connected"))
    }
}
