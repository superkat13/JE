package com.pineapple.sageos2.speech.voicerepair

import com.pineapple.sageos2.personal.EmptySagePersonalResponder
import com.pineapple.sageos2.personal.SagePersonalResolution
import com.pineapple.sageos2.personal.SagePersonalResponder
import org.junit.Assert.*
import org.junit.Test

class VoiceRepairResponderTest {
    private val manager = VoiceRepairSessionManager()
    private val responder = VoiceRepairResponder(EmptySagePersonalResponder, manager)

    private fun reply(text: String, respond: VoiceRepairResponder = responder): String =
        (respond.resolve(text) as SagePersonalResolution.Reply).text

    @Test fun repairRequestAsksForATypedPhraseWithoutStartingAnything() {
        val text = reply("fix your hearing")
        assertTrue(text.contains("Type one short phrase"))
        assertNull(manager.current())
    }

    @Test fun allRepairAliasesAvoidConversationalFallback() {
        val inner = object : SagePersonalResponder {
            override fun resolve(rawText: String): SagePersonalResolution? = error("must not reach personality")
        }
        (VoiceRepairResponder.FIX_COMMANDS + VoiceRepairResponder.DIAGNOSE_COMMANDS).forEach {
            val local = VoiceRepairResponder(inner, manager)
            val resolution = local.resolve(it)
            assertNotNull(resolution)
            assertTrue((resolution as SagePersonalResolution.Reply).text.contains("Type one short phrase"))
        }
    }

    @Test fun politeOwnerVariantsWork() {
        listOf("Sage, fix your hearing!", "Please Sage fix your hearing.", "Sage please fix your hearing please").forEach {
            val local = VoiceRepairResponder(EmptySagePersonalResponder, manager)
            assertTrue(reply(it, local).contains("Type one short phrase"))
        }
    }

    @Test fun typedPhraseStartsRepairExactlyOnceWithThePhrase() {
        var received: String? = null
        var calls = 0
        val local = VoiceRepairResponder(
            EmptySagePersonalResponder, manager,
            startRepair = { phrase -> calls++; received = phrase; true }
        )
        reply("fix my voice", local)
        val started = reply("good morning Sage", local)
        assertTrue(started.contains("Microphone test starting"))
        assertTrue(started.contains("good morning Sage"))
        assertTrue(started.contains("actually listening"))
        assertFalse(started.contains("now while I listen"))
        assertEquals(1, calls)
        assertEquals("good morning Sage", received)
    }

    @Test fun phraseIsConsumedOnceAndLaterTurnsDelegateAgain() {
        var calls = 0
        val inner = object : SagePersonalResponder {
            override fun resolve(rawText: String) = SagePersonalResolution.Reply("inner: $rawText")
        }
        val local = VoiceRepairResponder(inner, manager, startRepair = { calls++; true })
        reply("fix my voice", local)
        reply("hello", local)
        assertEquals(1, calls)
        assertEquals("inner: hello", reply("hello", local))
    }

    @Test fun commandShapedPhraseIsConsumedAsAPhraseNotACommand() {
        var received: String? = null
        val local = VoiceRepairResponder(EmptySagePersonalResponder, manager, startRepair = { p -> received = p; true })
        reply("fix my voice", local)
        reply("fix your hearing", local)
        assertEquals("fix your hearing", received)
    }

    @Test fun cancelWhileAwaitingPhraseStartsNothing() {
        var calls = 0
        val local = VoiceRepairResponder(EmptySagePersonalResponder, manager, startRepair = { calls++; true })
        reply("fix my voice", local)
        val text = reply("cancel voice repair", local)
        assertTrue(text.contains("No voice test was started"))
        assertEquals(0, calls)
        assertNull(manager.current())
    }

    @Test fun cancelRoutesThroughTheOrchestratorOnceForALiveSession() {
        manager.startSession("hello")
        var cancels = 0
        val local = VoiceRepairResponder(EmptySagePersonalResponder, manager, cancelRepair = { cancels++ })
        val text = reply("cancel voice repair", local)
        assertTrue(text.contains("Cancelled the voice test"))
        assertEquals(1, cancels)
    }

    @Test fun cancelWithoutActiveSessionIsHonest() {
        assertTrue(reply("cancel voice repair").contains("No active"))
    }

    @Test fun cancelledSessionIsNotReportedActiveAgain() {
        manager.startSession("hello")
        val local = VoiceRepairResponder(EmptySagePersonalResponder, manager, cancelRepair = { manager.cancel() })
        assertTrue(reply("cancel voice repair", local).contains("Cancelled the voice test"))
        assertTrue(reply("cancel voice repair", local).contains("No active"))
    }

    @Test fun statusSurfacesTheLatestVerifiedReport() {
        val local = VoiceRepairResponder(EmptySagePersonalResponder, manager, latestReport = { "REPORT: repaired" })
        assertTrue(reply("did the repair work", local).contains("REPORT: repaired"))
    }

    @Test fun statusSurfacesInterruptedNoticeBeforeTheOlderReport() {
        val local = VoiceRepairResponder(
            EmptySagePersonalResponder, manager,
            latestReport = { "REPORT: repaired" },
            interruptedNotice = { "INTERRUPTED notice" }
        )
        val text = reply("voice repair status", local)
        assertTrue(text.startsWith("INTERRUPTED notice"))
        assertTrue(text.contains("REPORT: repaired"))
    }

    @Test fun statusWithNothingYetIsHonest() {
        assertTrue(reply("did the repair work").contains("don't have a completed voice test"))
    }

    @Test fun statusWhileALiveSessionRunsReportsThePhrase() {
        manager.startSession("ping")
        val local = VoiceRepairResponder(EmptySagePersonalResponder, manager, latestReport = { "REPORT" })
        val text = reply("voice repair status", local)
        assertTrue(text.contains("running"))
        assertTrue(text.contains("ping"))
    }

    @Test fun busyStartIsHonestAndDoesNotClaimRepair() {
        val local = VoiceRepairResponder(EmptySagePersonalResponder, manager, startRepair = { phrase ->
            manager.startSession(phrase)
            val cur = manager.current()!!
            manager.update(cur.copy(state = VoiceRepairState.FAILED, cause = VoiceRepairCause.BUSY_RUNTIME))
            false
        })
        reply("fix my voice", local)
        val text = reply("hello", local)
        assertTrue(text.contains("couldn't open the microphone"))
    }

    @Test fun startRepairExceptionIsHandledHonestly() {
        val local = VoiceRepairResponder(EmptySagePersonalResponder, manager, startRepair = { throw IllegalStateException("boom") })
        reply("fix my voice", local)
        val text = reply("hello", local)
        assertTrue(text.contains("couldn't start the voice test"))
    }

    @Test fun successfulStartDiagnosticNeverContainsThePhrase() {
        val diagnostics = mutableListOf<String>()
        val local = VoiceRepairResponder(
            EmptySagePersonalResponder, manager,
            startRepair = { true },
            onDiagnostic = { diagnostics += it }
        )
        reply("fix my voice", local)
        reply("pineapple tornado 42", local)
        assertTrue(diagnostics.isNotEmpty())
        assertTrue(diagnostics.none { it.contains("pineapple tornado 42") })
        assertTrue(diagnostics.none { it.contains("pineapple") })
        assertTrue(diagnostics.any { it.contains("started") })
    }

    @Test fun refusedStartDiagnosticNeverContainsThePhrase() {
        val diagnostics = mutableListOf<String>()
        val local = VoiceRepairResponder(
            EmptySagePersonalResponder, manager,
            startRepair = { false },
            onDiagnostic = { diagnostics += it }
        )
        reply("fix my voice", local)
        reply("pineapple tornado 42", local)
        assertTrue(diagnostics.isNotEmpty())
        assertTrue(diagnostics.none { it.contains("pineapple tornado 42") })
        assertTrue(diagnostics.any { it.contains("refused") })
    }

    @Test fun overlongPhraseReasksAndRemainsAwaitingAPhrase() {
        var calls = 0
        val local = VoiceRepairResponder(EmptySagePersonalResponder, manager, startRepair = { calls++; true })
        reply("fix my voice", local)
        val tooLong = "x".repeat(VoiceRepairResponder.MAX_PHRASE + 1)
        val text = reply(tooLong, local)
        assertTrue(text.contains("not a usable test phrase"))
        assertEquals(0, calls)
        // Still awaiting, so a valid phrase is accepted next.
        reply("hello", local)
        assertEquals(1, calls)
    }

    @Test fun exportCommandSurfacesTheExportOrAnHonestFallback() {
        val withExport = VoiceRepairResponder(EmptySagePersonalResponder, manager, exportText = { "EXPORT BODY" })
        assertTrue(reply("export my voice test", withExport).contains("EXPORT BODY"))
        assertTrue(reply("export my voice test").contains("no completed voice test"))
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
        assertTrue(decision.localReply!!.contains("Type one short phrase"))
    }
}