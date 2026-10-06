package com.pineapple.sageos2.speech.voicerepair

import com.pineapple.sageos2.personal.EmptySagePersonalResponder
import com.pineapple.sageos2.personal.SagePersonalCommandEngine
import com.pineapple.sageos2.personal.SagePersonalResolution
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceRepairResponderTest {
    private val manager = VoiceRepairSessionManager()
    private val responder = VoiceRepairResponder(EmptySagePersonalResponder, manager)

    @Test fun diagnoseVoiceStartsSessionAndExplains() {
        val result = responder.resolve("diagnose my voice")
        assertNotNull(result)
        val reply = (result as SagePersonalResolution.Reply).text
        assertTrue(reply.contains("voice repair"))
        assertTrue(reply.contains("expected short phrase"))
    }

    @Test fun fixHearingStartsSession() {
        val result = responder.resolve("fix my hearing")
        assertNotNull(result)
        val reply = (result as SagePersonalResolution.Reply).text
        assertTrue(reply.contains("retest"))
        assertTrue(reply.contains("repair"))
    }

    @Test fun cancelWithoutSessionGivesClearMessage() {
        val result = responder.resolve("cancel voice repair")
        assertNotNull(result)
        val reply = (result as SagePersonalResolution.Reply).text
        assertTrue(reply.contains("No active voice repair session"))
    }

    @Test fun cancelAfterStartWorks() {
        responder.resolve("diagnose my voice")
        val result = responder.resolve("cancel voice repair")
        assertNotNull(result)
        val reply = (result as SagePersonalResolution.Reply).text
        assertTrue(reply.contains("Cancelled voice repair session"))
        assertTrue(manager.current()?.cancelled == true)
    }

    @Test fun unknownCommandsDoNotTrigger() {
        val personal = SagePersonalCommandEngine()
        val r = VoiceRepairResponder(personal, manager)
        assertNull(r.resolve("hello world"))
    }

    @Test fun brainUnavailableDoesNotAffectLocalCommands() {
        val result = responder.resolve("diagnose my voice")
        assertNotNull(result)
    }

    @Test fun testPhrasesNeverTrigger() {
        listOf("delete everything", "send money", "open door").forEach { cmd ->
            val res = responder.resolve(cmd)
            assertNull("command-like should not trigger repair", res)
        }
    }
}