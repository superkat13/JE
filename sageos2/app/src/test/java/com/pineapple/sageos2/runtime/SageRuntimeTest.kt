package com.pineapple.sageos2.runtime

import com.pineapple.sageos2.action.*
import com.pineapple.sageos2.apps.*
import com.pineapple.sageos2.brain.*
import com.pineapple.sageos2.capability.*
import com.pineapple.sageos2.continuity.*
import com.pineapple.sageos2.core.*
import com.pineapple.sageos2.identity.*
import com.pineapple.sageos2.memory.*
import com.pineapple.sageos2.maintenance.*
import com.pineapple.sageos2.personal.EmptySagePersonalResponder
import com.pineapple.sageos2.personal.SagePersonalResolution
import com.pineapple.sageos2.personal.SagePersonalResponder
import com.pineapple.sageos2.speech.*
import com.pineapple.sageos2.workflow.WorkflowEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SageRuntimeTest {
    @Test fun speechInputIsAttachedDirectlyToSingleRuntimeCoordinator() {
        val f = Fixture(); f.runtime.start(); assertNotNull(f.speech.listener)
        f.speech.listener!!.onWakeDetected(WakeHit(f.runtime.snapshot().recognizerGeneration, "sage", null, "Yes"))
        assertEquals(SageRuntimeState.ACKNOWLEDGING_WAKE, f.runtime.snapshot().state)
    }

    @Test fun savedWakeCommandUsesExistingFastActionPath() {
        val f = Fixture()
        f.runtime.start()
        f.speech.listener!!.onWakeDetected(
            WakeHit(
                f.runtime.snapshot().recognizerGeneration,
                "saved",
                null,
                "Got it",
                "open youtube"
            )
        )
        f.speech.completeLastSpeech()

        assertEquals(1, f.fast.requests.size)
        assertEquals("open youtube", f.fast.requests.single().command)
        assertTrue(f.brain.requests.isEmpty())
    }

    @Test fun recognitionErrorReturnsToUsableConversationPath() {
        val f = Fixture(); f.runtime.start()
        f.speech.listener!!.onWakeDetected(WakeHit(f.runtime.snapshot().recognizerGeneration, "sage", null, "Yes"))
        f.speech.completeLastSpeech(); val s = f.runtime.snapshot()
        f.speech.listener!!.onRecognitionError(s.activeTurnId, s.recognizerGeneration, 7)
        assertEquals(SageRuntimeState.SPEAKING, f.runtime.snapshot().state)
        assertEquals("I didn't catch that.", f.speech.spoken.last().second)
    }

    @Test fun customWakeProfileActivatesModeInsideSameRuntime() {
        val f = Fixture(); f.runtime.start()
        f.speech.listener!!.onWakeDetected(WakeHit(f.runtime.snapshot().recognizerGeneration, "sage_glitch", "red_queen", "Yes"))
        assertEquals("red_queen", com.pineapple.sageos2.mode.DefaultSageModeController.current().modeId)
    }

    @Test fun firstDeepTurnUsesColdBudgetThenSuccessfulTurnUnlocksWarmBudget() {
        val f = Fixture()
        f.runtime.start()
        f.runtime.submit(SageEvent.TextSubmitted("tell me something useful"))
        assertTrue(f.observer.diagnostics.any { it.contains("brain request profile: cold=true budget=900") })

        f.brain.respond(0, "First reply")
        f.runtime.submit(SageEvent.TextSubmitted("tell me another useful thing"))

        assertTrue(f.observer.diagnostics.any { it.contains("brain request profile: cold=false budget=1600") })
    }

    @Test fun deepBrainReceivesNaturalTwinContextWithoutEngineeringContracts() {
        val f = Fixture(); f.runtime.start(); f.runtime.submit(SageEvent.TextSubmitted("tell me something useful"))
        assertEquals(1, f.brain.requests.size)
        val request = f.brain.requests.single()
        assertTrue(request.twinContextText?.contains("virtual twin") == true)
        assertTrue(request.twinContextText?.contains("# WHO I AM") == true)
        assertFalse(request.twinContextText?.contains("Self restrictions: (none)") == true)
        assertFalse(request.twinContextText?.contains("SAGE TOOL CONTRACT") == true)
        assertFalse(request.twinContextText?.contains("ACTIVE / RECOVERABLE TASKS") == true)
    }

    @Test fun deepBrainReceivesCoreOwnerMemoryHistoryAndExactCurrentWordingTogether() {
        val coreSnapshot = EmptySageCoreProvider.current().copy(
            ownerModel = OwnerModel(preferredNames = listOf("Kat")),
            notes = "Imported Sage 1.33.3 owner instructions:\nStay warm, persistent, and honest."
        )
        val memorySnapshot = TwinMemorySnapshot(1, listOf(
            TwinMemoryRecord(
                "coffee", TwinMemorySubject.OWNER, "coffee",
                "Kat takes coffee with cinnamon", TwinMemorySource.EXPLICIT_OWNER,
                1.0, 1, 1
            )
        ))
        val historySnapshot = ConversationHistorySnapshot(1, listOf(
            ConversationEntry(
                "prior", 77, ConversationSpeaker.SAGE, ConversationInput.TEXT,
                "We were planning the garden.", 1
            )
        ))
        val f = Fixture(
            sageCore = object : SageCoreProvider { override fun current() = coreSnapshot },
            twinMemory = object : TwinMemoryProvider { override fun snapshot() = memorySnapshot },
            conversationHistory = object : ConversationHistoryProvider {
                override fun recent(limit: Int) = historySnapshot
            }
        )
        val exact = "Can we continue MY coffee plan, Sage? path=/Shared Notes"

        f.runtime.start()
        f.runtime.submit(SageEvent.TextSubmitted(exact))
        val request = f.brain.requests.single()
        val context = request.twinContextText.orEmpty()

        assertEquals(exact, request.prompt)
        assertTrue(context.contains("# OWNER CORE"))
        assertTrue(context.contains("Stay warm, persistent, and honest."))
        assertTrue(context.contains("Preferred name: Kat"))
        assertTrue(context.contains("Kat takes coffee with cinnamon"))
        assertTrue(context.contains("Sage: We were planning the garden."))
    }

    @Test fun actionReasoningStillReceivesTheToolContractUnderneathSage() {
        val f = Fixture(); f.runtime.start(); f.runtime.submit(SageEvent.TextSubmitted("check your root identity"))
        val context = f.brain.requests.single().twinContextText.orEmpty()
        assertTrue(context.contains("SAGE TOOL CONTRACT"))
        assertFalse(context.contains("ACTIVE / RECOVERABLE TASKS"))
    }

    @Test fun exactBrainSelfCheckUsesOnlyItsMinimalDeterministicContract() {
        val f = Fixture()
        f.runtime.start()
        f.runtime.submit(SageEvent.TextSubmitted(BrainRequestPolicy.SELF_CHECK_PROMPT))
        val request = f.brain.requests.single()
        assertEquals("Output only the requested literal. No explanation. /no_think", request.twinContextText)
        assertEquals("Brain online.", request.expectedLiteral)
        assertTrue(request.deterministic)
        assertTrue((request.maxOutputTokens ?: 0) in 4..12)
        assertEquals(null, request.sageCore)
        assertEquals(null, request.twinMemory)
        assertEquals(null, request.conversationHistory)
        assertEquals(null, request.ownerApps)
        assertEquals(null, request.mode)
    }

    @Test fun rememberedOwnerAppStartupProcedureReachesBrainWithDeviceTools() {
        val ownerAppsSnapshot = OwnerAppSnapshot(
            1,
            listOf(
                OwnerAppRecord(
                    "org.mozilla.firefox",
                    "Firefox",
                    aliases = listOf("browser"),
                    purpose = "web",
                    startupProcedure = "Open Firefox\nTap Private browsing"
                )
            )
        )
        val ownerAppsProvider = object : OwnerAppProvider {
            override fun snapshot() = ownerAppsSnapshot
        }
        val coordinator = SageTurnCoordinator(SageCommandRouter(ownerApps = ownerAppsProvider))
        val f = Fixture(coordinator = coordinator, ownerApps = ownerAppsProvider)

        f.runtime.start()
        f.runtime.submit(SageEvent.TextSubmitted("Open Firefox"))

        assertTrue(f.fast.requests.isEmpty())
        val request = f.brain.requests.single()
        assertTrue(request.twinContextText.orEmpty().contains("Firefox"))
        assertTrue(request.twinContextText.orEmpty().contains("Tap Private browsing"))
        assertTrue(request.twinContextText.orEmpty().contains("device.open_app"))
        assertTrue(request.twinContextText.orEmpty().contains("device.tap_label"))
    }

    @Test fun fastDeviceCommandBypassesBrain() {
        val f = Fixture(); f.runtime.start(); f.runtime.submit(SageEvent.TextSubmitted("open youtube"))
        assertEquals(1, f.fast.requests.size); assertEquals(0, f.brain.requests.size)
    }

    @Test fun startupKeepsExactProcedureAndCompletedStepsAcrossToolTurnsWith25Apps() {
        val capability = FakeCapabilityBroker(rootActive = true)
        val f = startupFixture(capability)
        f.runtime.start()
        f.runtime.submit(SageEvent.TextSubmitted("Open Firefox"))
        val first = f.brain.requests.single()
        assertTrue(first.twinContextText.orEmpty().contains("OWNER_RULE_SURVIVES"))
        assertTrue(first.twinContextText.orEmpty().contains("# WHO I AM"))
        assertTrue(first.twinContextText.orEmpty().contains("Open Firefox\nTap Private browsing"))
        assertTrue(first.twinContextText.orEmpty().contains("</SAGE_TOOL>"))
        assertTrue(first.prompt.length + first.twinContextText.orEmpty().length <= 1200)
        assertFalse(first.twinContextText.orEmpty().contains("UnrelatedApp"))
        f.brain.respond(0, "<SAGE_TOOL>\nname=device.open_app\napp=Firefox\n</SAGE_TOOL>")
        waitUntil { f.brain.requests.size == 2 }
        val next = f.brain.requests[1]
        assertTrue(next.twinContextText.orEmpty().contains("Request: Open Firefox"))
        assertTrue(next.twinContextText.orEmpty().contains("device.open_app app=Firefox"))
        assertTrue(next.twinContextText.orEmpty().contains("Tap Private browsing"))
        assertTrue(next.prompt.length + next.twinContextText.orEmpty().length <= 2000)
        f.brain.respond(1, "<SAGE_TOOL>\nname=device.tap_label\nlabel=Private browsing\n</SAGE_TOOL>")
        waitUntil { f.brain.requests.size == 3 }
        assertTrue(f.brain.requests[2].twinContextText.orEmpty().contains("device.tap_label label=Private browsing"))
        f.brain.respond(2, "Firefox is ready.")
        assertEquals(listOf("device.open_app", "device.tap_label"), capability.actions.map { it.name })
        waitUntil { f.brain.requests.size == 4 }
        assertTrue(f.observer.textResponses.isEmpty())
        assertTrue(f.brain.requests[3].prompt.contains(GoalCompletionPolicy.VERIFY_MARKER))
        assertTrue(f.brain.requests[3].twinContextText.orEmpty().contains("device.open_app app=Firefox"))
        assertTrue(f.brain.requests[3].twinContextText.orEmpty().contains("device.tap_label label=Private browsing"))
        f.brain.respond(3, "${GoalCompletionPolicy.VERIFIED_MARKER}\nFirefox is ready.")
        waitUntil { f.observer.textResponses.isNotEmpty() }
        assertEquals("Firefox is ready.", f.observer.textResponses.last().second)
        f.runtime.submit(SageEvent.TextSubmitted("Tell me something"))
        assertFalse(f.brain.requests.last().twinContextText.orEmpty().contains("OWNER APP STARTUP"))
    }

    @Test fun oversizedStartupStopsBeforeInferenceInsteadOfClippingInstructions() {
        val capability = FakeCapabilityBroker(rootActive = false)
        val f = startupFixture(capability, "Open Firefox\n" + "Do a step. ".repeat(500))
        f.runtime.start(); f.runtime.submit(SageEvent.TextSubmitted("Open Firefox"))
        assertTrue(f.brain.requests.isEmpty())
        assertTrue(capability.actions.isEmpty())
        assertTrue(f.observer.textResponses.last().second.contains("too long"))
        assertEquals(SageRuntimeState.IDLE_WAKE, f.runtime.snapshot().state)
    }

    @Test fun startupRejectsUnlistedToolWithoutExecutingIt() {
        val capability = FakeCapabilityBroker(rootActive = true)
        val f = startupFixture(capability)
        f.runtime.start(); f.runtime.submit(SageEvent.TextSubmitted("Open Firefox"))
        f.brain.respond(0, "<SAGE_TOOL>\nname=root.exec\nexecutable=/system/bin/id\n</SAGE_TOOL>")
        assertTrue(capability.actions.isEmpty())
        assertTrue(f.observer.textResponses.last().second.contains("unsupported action"))
    }

    @Test fun failedStartupActionStopsWithoutAutomaticRetry() {
        val actions = java.util.concurrent.CopyOnWriteArrayList<DeviceAction>()
        val capability = object : CapabilityBroker {
            override fun snapshot() = CapabilitySnapshot(emptyMap())
            override fun execute(action: DeviceAction): CapabilityResult {
                actions += action
                return CapabilityResult(false, "Completion unknown; do not retry")
            }
        }
        val f = startupFixture(capability)
        f.runtime.start(); f.runtime.submit(SageEvent.TextSubmitted("Open Firefox"))
        f.brain.respond(0, "<SAGE_TOOL>\nname=device.open_app\napp=Firefox\n</SAGE_TOOL>")
        waitUntil { f.observer.textResponses.isNotEmpty() }
        assertEquals(1, actions.size)
        assertEquals(1, f.brain.requests.size)
        assertTrue(f.observer.textResponses.last().second.contains("Completion unknown"))
    }

    private fun startupFixture(capability: CapabilityBroker, steps: String = "Open Firefox\nTap Private browsing"): Fixture {
        val app = OwnerAppRecord("org.mozilla.firefox", "Firefox", startupProcedure = steps)
        val apps = object : OwnerAppProvider {
            override fun snapshot() = OwnerAppSnapshot(25, (1..24).map {
                OwnerAppRecord("test.app$it", "UnrelatedApp$it", startupProcedure = "Unrelated ".repeat(40))
            } + app)
        }
        val core = object : SageCoreProvider {
            override fun current() = EmptySageCoreProvider.current().copy(
                notes = "Imported Sage 1.33.3 owner instructions:\nOWNER_RULE_SURVIVES " + "keep continuity ".repeat(100)
            )
        }
        return Fixture(capability = capability, ownerApps = apps, sageCore = core,
            coordinator = SageTurnCoordinator(SageCommandRouter(ownerApps = apps)))
    }

    @Test fun fastActionFailureReturnsExactDeviceReasonWithoutStartingBrain() {
        val f = Fixture()
        f.runtime.start()
        f.runtime.submit(SageEvent.TextSubmitted("take a screenshot"))
        f.fast.failLast("Accessibility control is not active")

        assertEquals("Accessibility control is not active", f.observer.textResponses.single().second)
        assertTrue(f.brain.requests.isEmpty())
        assertEquals(SageRuntimeState.IDLE_WAKE, f.runtime.snapshot().state)
    }

    @Test fun familiarSageHelpRepliesImmediatelyWithoutStartingGguf() {
        val responder = object : SagePersonalResponder {
            override fun resolve(rawText: String) = SagePersonalResolution.Reply("Just talk to me normally.")
        }
        val f = Fixture(coordinator = SageTurnCoordinator(SageCommandRouter(responder)))
        f.runtime.start()
        f.runtime.submit(SageEvent.TextSubmitted("How do I use Sage?"))
        assertEquals(0, f.brain.requests.size)
        assertEquals("Just talk to me normally.", f.observer.textResponses.single().second)
        assertEquals(SageRuntimeState.IDLE_WAKE, f.runtime.snapshot().state)
    }

    @Test fun ownerWorkflowTriggerIsSilent() {
        val f = Fixture(); f.runtime.start(); f.runtime.submit(SageEvent.TextSubmitted("Do you feel like chicken tonight?"))
        assertEquals(listOf("chicken_tonight"), f.workflows.launched); assertTrue(f.speech.spoken.isEmpty())
    }

    @Test fun exactBrainToolDirectiveExecutesCapabilityAndVerifiesBeforeReplying() {
        val capability = FakeCapabilityBroker(rootActive = true)
        val f = Fixture(capability)
        f.runtime.start()
        f.runtime.submit(SageEvent.TextSubmitted("check your root identity"))
        val turn = f.runtime.snapshot().activeTurnId

        f.brain.respond(
            0,
            """
            <SAGE_TOOL>
            name=root.exec
            executable=/system/bin/id
            arg.0=-u
            timeout_ms=5000
            </SAGE_TOOL>
            """.trimIndent()
        )

        waitUntil { capability.actions.size == 1 && f.brain.requests.size == 2 }
        assertEquals("root.exec", capability.actions.single().name)
        assertEquals("/system/bin/id", capability.actions.single().arguments["executable"])
        assertEquals(turn, f.brain.requests[1].turnId)
        assertTrue(f.brain.requests[1].prompt.contains("SAGE_TOOL_RESULT"))
        assertTrue(f.brain.requests[1].prompt.contains("success=true"))
        assertTrue(f.brain.requests[1].twinContextText.orEmpty().contains("SAGE TOOL CONTRACT"))
        assertTrue(f.brain.requests[1].twinContextText.orEmpty().contains("ACTIVE / RECOVERABLE TASKS"))

        f.brain.respond(1, "Root is alive and answering through the broker.")

        waitUntil { f.brain.requests.size == 3 }
        assertTrue(f.observer.textResponses.isEmpty())
        assertTrue(f.brain.requests[2].prompt.contains(GoalCompletionPolicy.VERIFY_MARKER))
        assertTrue(f.brain.requests[2].prompt.contains("owner_goal=check your root identity"))
        assertTrue(f.brain.requests[2].twinContextText.orEmpty().contains("SAGE TOOL CONTRACT"))
        assertTrue(f.brain.requests[2].twinContextText.orEmpty().contains("ACTIVE / RECOVERABLE TASKS"))

        f.brain.respond(2, "${GoalCompletionPolicy.VERIFIED_MARKER}\nRoot is alive and answering through the broker.")
        waitUntil { f.observer.textResponses.isNotEmpty() }
        assertEquals(
            "Root is alive and answering through the broker.",
            f.observer.textResponses.last().second
        )
        assertTrue(f.speech.spoken.isEmpty())
    }

    @Test fun verificationCanTakeCorrectiveActionAndThenReverify() {
        val capability = FakeCapabilityBroker(rootActive = true)
        val f = Fixture(capability)
        f.runtime.start()
        f.runtime.submit(SageEvent.TextSubmitted("check root health and make sure it really worked"))

        f.brain.respond(0, "<SAGE_TOOL>\nname=root.health\n</SAGE_TOOL>")
        waitUntil { capability.actions.size == 1 && f.brain.requests.size == 2 }

        f.brain.respond(1, "The first health request completed.")
        waitUntil { f.brain.requests.size == 3 }
        assertTrue(f.brain.requests[2].prompt.contains(GoalCompletionPolicy.VERIFY_MARKER))

        f.brain.respond(2, "<SAGE_TOOL>\nname=root.health\n</SAGE_TOOL>")
        waitUntil { capability.actions.size == 2 && f.brain.requests.size == 4 }

        f.brain.respond(3, "The verification health request also completed.")
        waitUntil { f.brain.requests.size == 5 }
        assertTrue(f.brain.requests[4].prompt.contains(GoalCompletionPolicy.VERIFY_MARKER))
        assertTrue(f.observer.textResponses.isEmpty())

        f.brain.respond(4, "${GoalCompletionPolicy.VERIFIED_MARKER}\nRoot health is responding.")
        waitUntil { f.observer.textResponses.isNotEmpty() }
        assertEquals("Root health is responding.", f.observer.textResponses.last().second)
    }

    @Test fun unverifiedGoalDoesNotPretendSuccess() {
        val capability = FakeCapabilityBroker(rootActive = true)
        val f = Fixture(capability)
        f.runtime.start()
        f.runtime.submit(SageEvent.TextSubmitted("check root health and confirm the final state"))

        f.brain.respond(0, "<SAGE_TOOL>\nname=root.health\n</SAGE_TOOL>")
        waitUntil { capability.actions.size == 1 && f.brain.requests.size == 2 }

        f.brain.respond(1, "The health command returned.")
        waitUntil { f.brain.requests.size == 3 }

        f.brain.respond(
            2,
            "${GoalCompletionPolicy.UNVERIFIED_MARKER}\nThe available evidence does not prove the final state."
        )
        waitUntil { f.observer.textResponses.isNotEmpty() }

        val text = f.observer.textResponses.last().second
        assertTrue(text.contains("does not prove the final state"))
        assertTrue(text.contains("couldn't verify the final state yet"))
        assertEquals(SageRuntimeState.IDLE_WAKE, f.runtime.snapshot().state)
    }

    @Test fun recoveredMutatingActionIsNotBlindlyReplayed() {
        val store = MemoryTaskStore()
        val action = DeviceAction("root.restart_service", mapOf("service" to "print"))
        val task = TaskCheckpoint(
            taskId = "runtime:turn:90",
            title = "Fix printing",
            state = TaskState.WAITING,
            summary = "Interrupted while restarting print service.",
            nextStep = "Recover safely.",
            updatedAtMs = 100,
            metadata = mapOf(
                "kind" to TaskRecoveryManager.RUNTIME_TURN_KIND,
                "ownerPrompt" to "restart printing and make sure it works",
                "phase" to "capability",
                "lastAction" to action.name,
                "lastActionSignature" to RecoveryCompletionPolicy.actionSignature(action),
                "toolCount" to "1",
                "recovered" to "true",
                "recoveryDepth" to "1"
            )
        )
        store.upsert(task)
        val capability = FakeCapabilityBroker(rootActive = true)
        val f = Fixture(capability = capability, taskContinuity = store)

        f.runtime.start()
        f.runtime.resumeRecoveredTask(task)

        assertEquals(TurnOrigin.RECOVERY, f.runtime.snapshot().activeTurnOrigin)
        assertTrue(f.brain.requests.single().prompt.contains(RecoveryCompletionPolicy.RECOVERY_MARKER))

        f.brain.respond(
            0,
            "<SAGE_TOOL>\nname=root.restart_service\nservice=print\n</SAGE_TOOL>"
        )

        waitUntil { f.brain.requests.size == 2 }
        assertTrue(capability.actions.isEmpty())
        assertTrue(f.brain.requests[1].prompt.contains(GoalCompletionPolicy.VERIFY_MARKER))
        assertTrue(f.brain.requests[1].prompt.contains("SAGE_RECOVERY_REPLAY_BLOCKED"))

        f.brain.respond(
            1,
            "${GoalCompletionPolicy.UNVERIFIED_MARKER}\nI cannot prove whether the restart already completed."
        )
        waitUntil { f.observer.textResponses.isNotEmpty() }

        assertTrue(f.observer.textResponses.last().second.contains("cannot prove"))
        assertTrue(f.observer.textResponses.last().second.contains("couldn't verify"))
        assertEquals(SageRuntimeState.IDLE_WAKE, f.runtime.snapshot().state)
        assertTrue(store.active().any { it.metadata["phase"] == "verification_waiting" })
    }

    @Test fun successfulHealthCheckDoesNotAuthorizeInterruptedMutationReplay() {
        val store = MemoryTaskStore()
        val interrupted = DeviceAction("root.restart_service", mapOf("service" to "print"))
        val task = TaskCheckpoint(
            taskId = "runtime:turn:91",
            title = "Fix printing",
            state = TaskState.WAITING,
            summary = "Interrupted while restarting print service.",
            nextStep = "Recover safely.",
            updatedAtMs = 100,
            metadata = mapOf(
                "kind" to TaskRecoveryManager.RUNTIME_TURN_KIND,
                "ownerPrompt" to "restart printing and make sure it works",
                "phase" to "capability",
                "lastAction" to interrupted.name,
                "lastActionSignature" to RecoveryCompletionPolicy.actionSignature(interrupted),
                "toolCount" to "1",
                "recovered" to "true",
                "recoveryDepth" to "1"
            )
        )
        store.upsert(task)
        val capability = FakeCapabilityBroker(rootActive = true)
        val f = Fixture(capability = capability, taskContinuity = store)

        f.runtime.start()
        f.runtime.resumeRecoveredTask(task)
        f.brain.respond(0, "<SAGE_TOOL>\nname=root.health\n</SAGE_TOOL>")
        waitUntil { capability.actions.size == 1 && f.brain.requests.size == 2 }
        assertEquals("root.health", capability.actions[0].name)

        f.brain.respond(
            1,
            "<SAGE_TOOL>\nname=root.restart_service\nservice=print\n</SAGE_TOOL>"
        )
        waitUntil { f.brain.requests.size == 3 }
        assertEquals(listOf("root.health"), capability.actions.map { it.name })
        assertTrue(f.brain.requests[2].prompt.contains("SAGE_RECOVERY_REPLAY_BLOCKED"))

        f.brain.respond(
            2,
            "${GoalCompletionPolicy.UNVERIFIED_MARKER}\nThe broker is healthy, but I cannot prove whether printing restarted."
        )
        waitUntil { f.observer.textResponses.isNotEmpty() }
        assertTrue(f.observer.textResponses.last().second.contains("cannot prove"))
        assertTrue(store.active().any { it.metadata["phase"] == "verification_waiting" })
    }

    @Test fun readOnlyChecksCannotClearReplayGuardOrResetReplayLimit() {
        for (readOnly in listOf("root.health", "forge.health", "forge.tools", "forge.job", "device.screenshot", "device.read_notifications")) {
            val store = MemoryTaskStore()
            val task = interruptedPrintTask()
            store.upsert(task)
            val capability = FakeCapabilityBroker(rootActive = true)
            val f = Fixture(capability = capability, taskContinuity = store)
            f.runtime.start()
            f.runtime.resumeRecoveredTask(task)
            var request = 0
            repeat(RecoveryCompletionPolicy.MAX_REPLAY_BLOCKS + 1) { attempt ->
                f.brain.respond(request++, "<SAGE_TOOL>\nname=$readOnly\n</SAGE_TOOL>")
                waitUntil { f.brain.requests.size == request + 1 }
                f.brain.respond(request++, "<SAGE_TOOL>\nname=root.restart_service\nservice=print\n</SAGE_TOOL>")
                if (attempt < RecoveryCompletionPolicy.MAX_REPLAY_BLOCKS) {
                    waitUntil { f.brain.requests.size == request + 1 }
                }
            }
            waitUntil { f.observer.textResponses.isNotEmpty() }
            assertEquals(readOnly, listOf(readOnly, readOnly, readOnly), capability.actions.map { it.name })
            assertTrue(f.observer.textResponses.last().second.contains("did not repeat"))
            assertTrue(store.active().any { it.metadata["phase"] == "recovery_waiting" })
            f.runtime.stop()
        }
    }

    @Test fun replayGuardSurvivesSecondRestartAfterHealthCheck() {
        val store = MemoryTaskStore()
        val task = interruptedPrintTask()
        store.upsert(task)
        val first = Fixture(capability = FakeCapabilityBroker(rootActive = true), taskContinuity = store)
        first.runtime.start()
        first.runtime.resumeRecoveredTask(task)
        first.brain.respond(0, "<SAGE_TOOL>\nname=root.health\n</SAGE_TOOL>")
        waitUntil { first.brain.requests.size == 2 }
        val checkpoint = store.active().single { it.metadata["phase"] == "brain_after_capability" }
        assertEquals("root.health", checkpoint.metadata["lastAction"])
        first.runtime.stop()

        val capability = FakeCapabilityBroker(rootActive = true)
        val second = Fixture(capability = capability, taskContinuity = store)
        second.runtime.start()
        second.runtime.resumeRecoveredTask(checkpoint)
        second.brain.respond(0, "<SAGE_TOOL>\nname=root.restart_service\nservice=print\n</SAGE_TOOL>")
        waitUntil { second.brain.requests.size == 2 }
        assertTrue(capability.actions.isEmpty())
        assertTrue(second.brain.requests[1].prompt.contains("SAGE_RECOVERY_REPLAY_BLOCKED"))
        second.runtime.stop()
    }

    @Test fun differentRecoveryActionIsAllowedAndBothMutationsStayProtected() {
        val store = MemoryTaskStore()
        val task = interruptedPrintTask()
        store.upsert(task)
        val capability = FakeCapabilityBroker(rootActive = true)
        val f = Fixture(capability = capability, taskContinuity = store)
        f.runtime.start()
        f.runtime.resumeRecoveredTask(task)
        f.brain.respond(0, "<SAGE_TOOL>\nname=root.restart_service\nservice=other\n</SAGE_TOOL>")
        waitUntil { f.brain.requests.size == 2 }
        assertEquals("other", capability.actions.single().arguments["service"])
        val checkpoint = store.active().single { it.metadata["phase"] == "brain_after_capability" }
        assertEquals(2, RecoveryCompletionPolicy.replayGuards(checkpoint.metadata).size)
        f.brain.respond(1, "<SAGE_TOOL>\nname=root.restart_service\nservice=print\n</SAGE_TOOL>")
        waitUntil { f.brain.requests.size == 3 }
        assertEquals(1, capability.actions.size)
        f.runtime.stop()
    }

    @Test fun normalTurnRecordsMutationHistoryWithoutApplyingRecoveryReplayRules() {
        val store = MemoryTaskStore()
        val capability = FakeCapabilityBroker(rootActive = true)
        val f = Fixture(capability = capability, taskContinuity = store)
        f.runtime.start()
        f.runtime.submit(SageEvent.TextSubmitted("restart printing twice then check health"))
        repeat(2) { index ->
            f.brain.respond(index, "<SAGE_TOOL>\nname=root.restart_service\nservice=print\n</SAGE_TOOL>")
            waitUntil { f.brain.requests.size == index + 2 }
        }
        f.brain.respond(2, "<SAGE_TOOL>\nname=root.health\n</SAGE_TOOL>")
        waitUntil { f.brain.requests.size == 4 }
        assertEquals(listOf("root.restart_service", "root.restart_service", "root.health"), capability.actions.map { it.name })
        val checkpoint = store.active().single()
        assertEquals("root.health", checkpoint.metadata["lastAction"])
        assertEquals("root.restart_service", RecoveryCompletionPolicy.replayGuards(checkpoint.metadata).single().actionName)
        f.runtime.stop()
    }

    private fun interruptedPrintTask() = TaskCheckpoint(
        taskId = "runtime:turn:92",
        title = "Fix printing",
        state = TaskState.WAITING,
        summary = "Interrupted while restarting print service.",
        nextStep = "Recover safely.",
        updatedAtMs = 100,
        metadata = mapOf(
            "kind" to TaskRecoveryManager.RUNTIME_TURN_KIND,
            "ownerPrompt" to "restart printing and make sure it works",
            "phase" to "capability",
            "lastAction" to "root.restart_service",
            "lastActionSignature" to RecoveryCompletionPolicy.actionSignature(
                DeviceAction("root.restart_service", mapOf("service" to "print"))
            ),
            "toolCount" to "1",
            "recovered" to "true",
            "recoveryDepth" to "1"
        )
    )

    @Test fun normalBrainProseMentioningToolNameNeverExecutesCapability() {
        val capability = FakeCapabilityBroker(rootActive = true)
        val f = Fixture(capability)
        f.runtime.start(); f.runtime.submit(SageEvent.TextSubmitted("explain the root path"))
        f.brain.respond(0, "The root.exec tool uses executable and argv fields.")
        waitUntil { f.observer.textResponses.isNotEmpty() }
        assertTrue(capability.actions.isEmpty())
    }

    @Test fun brainWatchdogReleasesTextChatWithHumanReadableReply() {
        val scheduler = ManualScheduler()
        val f = Fixture(scheduler = scheduler, brainResponseTimeoutMs = 50L)
        f.runtime.start()
        f.runtime.submit(SageEvent.TextSubmitted("don't leave chat hanging"))
        assertEquals(SageRuntimeState.THINKING_DEEP, f.runtime.snapshot().state)

        scheduler.runNext()

        assertEquals(SageRuntimeState.IDLE_WAKE, f.runtime.snapshot().state)
        assertTrue(f.observer.textResponses.single().second.contains("taking too long"))
        assertTrue(f.observer.textResponses.single().second.contains("message is saved"))
    }

    @Test fun brainWatchdogTracksLoadFirstTokenAndGenerationProgress() {
        val scheduler = ManualScheduler()
        val f = Fixture(scheduler = scheduler)
        f.runtime.start()
        f.runtime.submit(SageEvent.TextSubmitted("stay responsive"))
        assertTrue(scheduler.activeDelays().contains(120_000L))

        f.brain.progress(0, BrainProgressStage.LOADING_MODEL)
        assertTrue(scheduler.activeDelays().contains(30_000L))
        f.brain.progress(0, BrainProgressStage.READING_CONTEXT)
        assertTrue(scheduler.activeDelays().contains(60_000L))
        f.brain.progress(0, BrainProgressStage.GENERATING, generatedTokens = 1)
        assertTrue(scheduler.activeDelays().contains(30_000L))

        scheduler.runLast(30_000L)
        assertEquals(SageRuntimeState.IDLE_WAKE, f.runtime.snapshot().state)
        assertTrue(f.observer.textResponses.single().second.contains("taking too long"))
    }

    @Test fun fifthToolCallIsBlockedWhenConfiguredPerTurnLimitIsFour() {
        val capability = FakeCapabilityBroker(rootActive = true)
        val f = Fixture(capability, maxToolCallsPerTurn = 4)
        f.runtime.start(); f.runtime.submit(SageEvent.TextSubmitted("run a bounded diagnostic chain"))

        repeat(4) { index ->
            f.brain.respond(index, "<SAGE_TOOL>\nname=root.health\n</SAGE_TOOL>")
            waitUntil { capability.actions.size == index + 1 && f.brain.requests.size == index + 2 }
        }
        f.brain.respond(4, "<SAGE_TOOL>\nname=root.health\n</SAGE_TOOL>")
        Thread.sleep(80)
        assertEquals(4, capability.actions.size)
        assertEquals(5, f.brain.requests.size)
    }

    @Test fun selfCheckReportsCurrentFailureWithoutBrainAndNextRequestStillWorks() {
        val snapshot = SelfCareSnapshot(false, "model unavailable", false, "remote wake disconnected", 2, true)
        var checks = 0
        val personal = SageSelfCheckResponder(EmptySagePersonalResponder) {
            checks++
            SelfCheckReport(snapshot, SelfCarePolicy.evaluate(snapshot), 1)
        }
        val f = Fixture(coordinator = SageTurnCoordinator(SageCommandRouter(personal = personal)))
        f.runtime.start()
        f.runtime.submit(SageEvent.TextSubmitted("check yourself"))
        assertEquals(1, checks)
        assertTrue(f.brain.requests.isEmpty())
        assertTrue(f.fast.requests.isEmpty())
        val response = f.observer.textResponses.single()
        assertTrue(response.second.contains("model unavailable"))
        assertTrue(response.second.contains("remote wake disconnected"))
        assertEquals(SageRuntimeState.IDLE_WAKE, f.runtime.snapshot().state)
        f.runtime.submit(SageEvent.TextSubmitted("explain gravity"))
        assertEquals(1, f.brain.requests.size)
        assertTrue(f.brain.requests.single().turnId != response.first)
        f.runtime.stop()
    }

    @Test fun newRuntimeDoesNotOverwriteCompletedTaskWithReusedTurnNumber() {
        val store = MemoryTaskStore()
        val first = Fixture(taskContinuity = store)
        first.runtime.start()
        first.runtime.submit(SageEvent.TextSubmitted("explain gravity"))
        first.brain.respond(0, "Gravity attracts masses.")
        val completed = store.recent(10).single()
        assertEquals(TaskState.COMPLETED, completed.state)
        first.runtime.stop()

        val second = Fixture(taskContinuity = store)
        second.runtime.start()
        second.runtime.submit(SageEvent.TextSubmitted("explain sunlight"))
        assertEquals("turn counters restart; persisted IDs must not", first.brain.requests[0].turnId,
            second.brain.requests[0].turnId)
        assertEquals("previous outcome must survive a runtime restart", completed, store.get(completed.taskId))
        assertEquals(2, store.recent(10).size)
        second.runtime.stop()
    }

    @Test fun recoveryAcrossRuntimeRestartPreservesItsSourceCheckpoint() {
        val store = MemoryTaskStore()
        val first = Fixture(taskContinuity = store)
        first.runtime.start()
        first.runtime.submit(SageEvent.TextSubmitted("research the interrupted goal"))
        val original = store.active().single()
        // Simulate abrupt loss of the runtime: its active checkpoint survives unchanged.
        val recovery = TaskRecoveryManager(store)
        recovery.recoverInterruptedRuntimeTasks()
        val candidate = recovery.markAutoResumeAttempted(original.taskId)!!
        val second = Fixture(taskContinuity = store)
        second.runtime.start()
        second.runtime.resumeRecoveredTask(candidate)

        val resumed = store.active().single()
        assertTrue("recovery must create a distinct checkpoint", resumed.taskId != original.taskId)
        assertEquals(original.taskId, resumed.metadata["recoveredFrom"])
        val source = store.get(original.taskId)!!
        assertEquals("true", source.metadata["autoResumeAttempted"])
        assertEquals(resumed.taskId, source.metadata["supersededBy"])
        assertEquals(original.metadata["ownerPrompt"], source.metadata["ownerPrompt"])
        first.runtime.stop()
        second.runtime.stop()
    }

    @Test fun lateBrainResponseCannotRemoveNextTurnsWatchdog() {
        val scheduler = ManualScheduler()
        val f = Fixture(scheduler = scheduler)
        f.runtime.start()
        f.runtime.submit(SageEvent.TextSubmitted("explain gravity"))
        f.brain.respond(0, "Gravity attracts masses.")
        f.runtime.submit(SageEvent.TextSubmitted("explain sunlight"))
        f.brain.respond(0, "Late duplicate")
        assertTrue(scheduler.activeDelays().contains(120_000L))
        scheduler.runLast(120_000L)
        assertEquals(SageRuntimeState.IDLE_WAKE, f.runtime.snapshot().state)
    }

    @Test fun duplicateBrainResultCannotCompleteANewerAttemptInSameTurn() {
        val store = MemoryTaskStore()
        val f = Fixture(taskContinuity = store)
        f.runtime.start()
        f.runtime.resumeRecoveredTask(interruptedPrintTask())
        f.brain.respond(0, "Checking the recovered result.")
        assertEquals(2, f.brain.requests.size)
        f.brain.respond(0, "${GoalCompletionPolicy.VERIFIED_MARKER}\nStale completion")
        assertTrue(f.observer.textResponses.isEmpty())
        assertEquals(SageRuntimeState.THINKING_DEEP, f.runtime.snapshot().state)
        f.brain.respond(1, "${GoalCompletionPolicy.UNVERIFIED_MARKER}\nNeed current evidence.")
        assertEquals(1, f.observer.textResponses.size)
        assertTrue(store.active().any { it.state == TaskState.WAITING })
    }

    @Test fun timedOutBrainResponseCannotOverwriteFailedCheckpoint() {
        val store = MemoryTaskStore()
        val scheduler = ManualScheduler()
        val f = Fixture(scheduler = scheduler, taskContinuity = store)
        f.runtime.start()
        f.runtime.submit(SageEvent.TextSubmitted("explain gravity"))
        scheduler.runLast(120_000L)
        val failed = store.recent(10).single()
        assertEquals(TaskState.FAILED, failed.state)
        f.brain.respond(0, "Late success")
        assertEquals(failed, store.get(failed.taskId))
        assertEquals(1, f.observer.textResponses.size)
    }

    @Test fun stuckCapabilityReleasesConversationWithoutClaimingOrReplayingItsOutcome() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val returned = java.util.concurrent.CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val broker = object : CapabilityBroker {
            override fun snapshot() = CapabilitySnapshot(mapOf(Capability.SAGEOS_ROOT_BROKER to CapabilityStatus.ACTIVE))
            override fun execute(action: DeviceAction): CapabilityResult {
                calls.incrementAndGet()
                entered.countDown()
                // Model a broker that cannot be stopped by Thread.interrupt().
                while (release.count > 0) {
                    try { release.await() } catch (_: InterruptedException) { }
                }
                returned.countDown()
                return CapabilityResult(true, "late completion")
            }
        }
        val store = MemoryTaskStore()
        val scheduler = ManualScheduler()
        val f = Fixture(capability = broker, scheduler = scheduler, taskContinuity = store)
        try {
            f.runtime.start()
            f.runtime.submit(SageEvent.TextSubmitted("restart the print service"))
            f.brain.respond(0, "<SAGE_TOOL>\nname=root.restart_service\nservice=print\n</SAGE_TOOL>")
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("tool execution must retain a runtime deadline", scheduler.activeDelays().contains(310_000L))
            scheduler.runLast(310_000L)
            assertEquals(SageRuntimeState.IDLE_WAKE, f.runtime.snapshot().state)
            val waiting = store.active().single()
            assertEquals(TaskState.WAITING, waiting.state)
            assertEquals("unknown", waiting.metadata["lastActionSuccess"])
            assertTrue(RecoveryCompletionPolicy.replayGuards(waiting.metadata).isNotEmpty())
            assertTrue(f.observer.textResponses.single().second.contains("could not confirm"))
            f.runtime.submit(SageEvent.TextSubmitted("explain gravity"))
            release.countDown()
            assertTrue(returned.await(2, java.util.concurrent.TimeUnit.SECONDS))
            waitUntil { f.observer.diagnostics.any { it.contains("stale capability result ignored") } }
            assertEquals(2, f.brain.requests.size)
            assertTrue(scheduler.activeDelays().contains(120_000L))
            assertEquals(1, calls.get())
            f.brain.respond(1, "Gravity attracts masses.")
            assertEquals(SageRuntimeState.IDLE_WAKE, f.runtime.snapshot().state)
        } finally {
            release.countDown()
            f.runtime.stop()
        }
    }

    private class Fixture(
        capability: CapabilityBroker = EmptyCapabilityBroker,
        maxToolCallsPerTurn: Int = GoalCompletionPolicy.DEFAULT_MAX_TOOL_CALLS,
        scheduler: RuntimeScheduler = FakeScheduler(),
        brainResponseTimeoutMs: Long = 120_000L,
        coordinator: SageTurnCoordinator = SageTurnCoordinator(),
        sageCore: SageCoreProvider = EmptySageCoreProvider,
        twinMemory: TwinMemoryProvider = EmptyTwinMemoryProvider,
        conversationHistory: ConversationHistoryProvider = EmptyConversationHistoryProvider,
        ownerApps: OwnerAppProvider = EmptyOwnerAppProvider,
        taskContinuity: TaskContinuityStore? = null
    ) {
        val speech = FakeSpeech(); val brain = FakeBrain(); val fast = FakeFastActions(); val workflows = FakeWorkflows(); val observer = FakeObserver()
        val runtime = SageRuntime(
            coordinator,
            speech,
            brain,
            fast,
            workflows,
            scheduler,
            observer = observer,
            sageCore = sageCore,
            twinMemory = twinMemory,
            conversationHistory = conversationHistory,
            ownerApps = ownerApps,
            capabilities = capability,
            taskContinuity = taskContinuity,
            maxToolCallsPerTurn = maxToolCallsPerTurn,
            brainResponseTimeoutMs = brainResponseTimeoutMs
        )
    }

    private class FakeSpeech : SpeechPort {
        var listener: SpeechInputListener? = null; val spoken = mutableListOf<Pair<Long,String>>(); private var completion:(()->Unit)?=null
        override fun attach(listener: SpeechInputListener){this.listener=listener}
        override fun setListening(mode:SageListeningMode,generation:Long,turnId:Long)=Unit
        override fun speak(turnId:Long,text:String,onComplete:()->Unit){spoken+=turnId to text; completion=onComplete}
        override fun speakTransient(text:String)=Unit
        fun completeLastSpeech(){val c=completion?:error("no speech"); completion=null; c()}
    }

    private class FakeBrain:BrainEngine {
        override val name="fake"
        val requests=mutableListOf<BrainRequest>()
        private val callbacks=mutableListOf<(Result<BrainResponse>)->Unit>()
        private var successfulLatencyMs:Long?=null
        override fun health()=BrainHealth(true,"ready",lastLatencyMs=successfulLatencyMs)
        override fun start(request:BrainRequest,callback:(Result<BrainResponse>)->Unit):BrainJob {
            requests += request; callbacks += callback
            return object:BrainJob{override val turnId=request.turnId;override fun cancel()=Unit}
        }
        fun respond(index:Int,text:String) {
            val request=requests[index]
            successfulLatencyMs=1L
            callbacks[index](Result.success(BrainResponse(request.turnId,text,name)))
        }
        fun progress(index:Int,stage:BrainProgressStage,generatedTokens:Int?=null) {
            val request=requests[index]
            request.onProgress(BrainProgress(
                request.turnId,
                stage,
                generatedTokens?.let { BrainTelemetry(generatedTokens = it) }
            ))
        }
    }

    private class FakeFastActions : FastActionEngine {
        val requests = mutableListOf<FastActionRequest>()
        private var lastCallback: ((Result<FastActionResponse>) -> Unit)? = null
        override fun start(
            request: FastActionRequest,
            callback: (Result<FastActionResponse>) -> Unit
        ): FastActionJob {
            requests += request
            lastCallback = callback
            return object : FastActionJob {
                override val turnId = request.turnId
                override fun cancel() = Unit
            }
        }
        fun failLast(detail: String) {
            val callback = lastCallback ?: error("no fast action callback")
            lastCallback = null
            callback(Result.failure(IllegalStateException(detail)))
        }
    }
    private class FakeWorkflows:WorkflowEngine{val launched=mutableListOf<String>();override fun launch(turnId:Long,workflowId:String){launched+=workflowId}}
    private class FakeScheduler:RuntimeScheduler{override fun schedule(delayMs:Long,task:()->Unit)=object:ScheduledHandle{override fun cancel()=Unit}}
    private class ManualScheduler : RuntimeScheduler {
        private data class Pending(val delayMs: Long, val task: () -> Unit, var cancelled: Boolean = false)
        private val pending = mutableListOf<Pending>()
        override fun schedule(delayMs: Long, task: () -> Unit): ScheduledHandle {
            val item = Pending(delayMs, task)
            pending += item
            return object : ScheduledHandle { override fun cancel() { item.cancelled = true } }
        }
        fun activeDelays() = pending.filterNot { it.cancelled }.map { it.delayMs }
        fun runNext() {
            val item = pending.firstOrNull { !it.cancelled } ?: error("no pending task")
            item.cancelled = true
            item.task()
        }
        fun runLast(delayMs: Long) {
            val item = pending.lastOrNull { !it.cancelled && it.delayMs == delayMs }
                ?: error("no pending task for ${delayMs}ms")
            item.cancelled = true
            item.task()
        }
    }
    private class FakeObserver:RuntimeObserver {
        val textResponses=mutableListOf<Pair<Long,String>>()
        val diagnostics=mutableListOf<String>()
        override fun onTextResponse(turnId:Long,text:String){textResponses += turnId to text}
        override fun onDiagnostic(message:String){diagnostics += message}
    }
    private class MemoryTaskStore : TaskContinuityStore {
        private val tasks = linkedMapOf<String, TaskCheckpoint>()
        override fun upsert(checkpoint: TaskCheckpoint) { tasks[checkpoint.taskId] = checkpoint }
        override fun get(taskId: String) = tasks[taskId]
        override fun active() = tasks.values
            .filter { it.state == TaskState.ACTIVE || it.state == TaskState.WAITING }
            .sortedByDescending { it.updatedAtMs }
        override fun recent(limit: Int) = tasks.values.sortedByDescending { it.updatedAtMs }.take(limit)
        override fun remove(taskId: String) { tasks.remove(taskId) }
    }

    private class FakeCapabilityBroker(private val rootActive:Boolean):CapabilityBroker {
        val actions=java.util.Collections.synchronizedList(mutableListOf<DeviceAction>())
        override fun snapshot()=CapabilitySnapshot(mapOf(Capability.SAGEOS_ROOT_BROKER to if(rootActive) CapabilityStatus.ACTIVE else CapabilityStatus.UNAVAILABLE))
        override fun execute(action:DeviceAction):CapabilityResult { actions += action; return CapabilityResult(true,"uid=0") }
    }

    private fun waitUntil(timeoutMs:Long=1_500, condition:()->Boolean) {
        val deadline=System.nanoTime()+timeoutMs*1_000_000
        while(!condition()) {
            if(System.nanoTime()>=deadline) error("condition was not met within ${timeoutMs}ms")
            Thread.sleep(10)
        }
    }
}
