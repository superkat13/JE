package com.pineapple.sageos2.maintenance

import com.pineapple.sageos2.personal.EmptySagePersonalResponder
import com.pineapple.sageos2.personal.SagePersonalCommandEngine
import com.pineapple.sageos2.personal.SagePersonalResolution
import org.junit.Assert.*
import org.junit.Test

class SageSelfCheckResponderTest {
    private val healthy = SelfCareSnapshot(true, "native ready", true, "listening", 3, true,
        commandSpeechReady = true, commandSpeechDetail = "sherpa ready")

    @Test fun supportedCommandsReadFreshStatusEveryTime() {
        var checks = 0
        val responder = SageSelfCheckResponder(EmptySagePersonalResponder) {
            checks++
            SelfCheckReport(healthy, emptyList(), checks)
        }
        listOf("check yourself", "Sage, check your health!", "Please check yourself.",
            "check yourself please", "run a self check").forEach { command ->
            val reply = responder.resolve(command) as SagePersonalResolution.Reply
            assertTrue(reply.text.contains("excluding health findings): $checks"))
            assertTrue(reply.text.contains("haven't applied repairs"))
            assertTrue(reply.text.contains("Command speech dependencies: present"))
            assertTrue(reply.text.contains("Inference, microphone capture, and speech recognition were not tested."))
        }
        assertEquals(5, checks)
    }

    @Test fun arbitraryRequestsNeverTriggerCheckBySubstring() {
        val responder = SageSelfCheckResponder(EmptySagePersonalResponder) { error("must not run") }
        listOf("don't check yourself", "explain how to check your health", "check yourself and delete memories",
            "remember check yourself", "check your health later").forEach { assertNull(responder.resolve(it)) }
    }

    @Test fun failedProviderProducesHonestLocalFailure() {
        val responder = SageSelfCheckResponder(EmptySagePersonalResponder) { error("broken store") }
        val reply = responder.resolve("check yourself") as SagePersonalResolution.Reply
        assertTrue(reply.text.contains("couldn't complete"))
        assertTrue(reply.text.contains("haven't verified"))
        assertFalse(reply.text.contains("No problems"))
    }

    @Test fun normalPersonalRepliesAndTeachingKeepPrecedence() {
        val personal = SagePersonalCommandEngine()
        val responder = SageSelfCheckResponder(personal) { error("must not steal teaching input") }
        val teaching = responder.resolve("Teach me something")
        assertNotNull(teaching)
        val next = responder.resolve("check yourself") as SagePersonalResolution.Reply
        assertTrue(next.text.contains("mean", ignoreCase = true))
    }

    @Test fun brokenBrainAndSpeechAreVisibleWithoutCallingEither() {
        val failed = healthy.copy(brainReady = false, brainDetail = "model missing",
            commandSpeechReady = false, commandSpeechDetail = "speech model missing")
        val responder = SageSelfCheckResponder(EmptySagePersonalResponder) {
            SelfCheckReport(failed, SelfCarePolicy.evaluate(failed), 2)
        }
        val text = (responder.resolve("check yourself") as SagePersonalResolution.Reply).text
        assertTrue(text.contains("Local brain reported status: not available — model missing"))
        assertTrue(text.contains("Command speech dependencies: missing — speech model missing"))
        assertTrue(text.contains("Next step:"))
        assertTrue(text.contains("not an end-to-end task test"))
    }

    @Test fun unknownSpeechDoesNotClaimReadiness() {
        val report = SelfCheckReport(healthy.copy(commandSpeechReady = null, commandSpeechDetail = ""), emptyList(), 0)
        assertTrue(report.render().contains("Command speech dependencies: not checked"))
    }
}
