package com.pineapple.sageos2.continuity

import com.pineapple.sageos2.core.SageCommandRouter
import com.pineapple.sageos2.core.SageRoute
import com.pineapple.sageos2.personal.EmptySagePersonalResponder
import com.pineapple.sageos2.personal.SagePersonalCommandEngine
import com.pineapple.sageos2.personal.SagePersonalResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SageTaskFollowThroughResponderTest {

    private val store = MemoryTaskStore()
    private val resumed = mutableListOf<TaskCheckpoint>()
    private var clock = 1_000L
    private val events = mutableListOf<String>()

    private fun responder(inner: com.pineapple.sageos2.personal.SagePersonalResponder = EmptySagePersonalResponder) =
        SageTaskFollowThroughResponder(
            inner = inner,
            store = store,
            resume = { resumed += it },
            nowMs = { clock += 10; clock },
            onEvent = { events += it }
        )

    private fun task(
        id: String,
        title: String = "Task $id",
        state: TaskState = TaskState.ACTIVE,
        at: Long = 100L,
        prompt: String? = "do $id",
        metadata: Map<String, String> = emptyMap()
    ) = TaskCheckpoint(
        taskId = id,
        title = title,
        state = state,
        summary = "partly done",
        nextStep = "finish $id",
        updatedAtMs = at,
        metadata = if (prompt == null) metadata else metadata + ("ownerPrompt" to prompt)
    )

    private fun reply(responder: SageTaskFollowThroughResponder, text: String) =
        (responder.resolve(text) as SagePersonalResolution.Reply).text

    @Test fun listsSavedWorkInPlainLanguageWithoutTheModel() {
        store.upsert(task("1", "Finish the garage inventory", at = 100L))
        store.upsert(task("2", "Email the plumber", state = TaskState.WAITING, at = 200L))
        val text = reply(responder(), "what's unfinished")
        assertTrue(text.contains("You have 2 unfinished tasks"))
        assertTrue(text.contains("1. Finish the garage inventory"))
        assertTrue(text.contains("2. Email the plumber"))
        assertTrue(text.contains("Where I got to: partly done"))
        assertTrue(text.contains("Next: finish"))
        assertTrue(text.contains("waiting to be picked back up"))
        assertTrue(text.contains("rather than the language model"))
    }

    @Test fun emptyStoreSaysNothingIsWaiting() {
        assertTrue(reply(responder(), "what's unfinished").contains("no unfinished tasks saved"))
    }

    @Test fun tasksSurviveRestartBecauseTheyAreReadBackFromTheStore() {
        store.upsert(task("1", "Survive a restart", at = 100L))
        // A new responder over the same store stands in for a process restart: selection state is
        // in-memory, but the work itself must still be there.
        val afterRestart = reply(responder(), "what is unfinished")
        assertTrue(afterRestart.contains("Survive a restart"))
    }

    @Test fun resumesTheOnlyUnfinishedTaskAndKeepsReplayGuardPromise() {
        store.upsert(task("1", "Finish the garage inventory"))
        val text = reply(responder(), "continue that task")
        assertEquals(1, resumed.size)
        assertEquals("1", resumed.single().taskId)
        assertTrue(text.contains("Continuing \"Finish the garage inventory\""))
        assertTrue(text.contains("won't repeat an action that already took effect"))
    }

    @Test fun ambiguousChoiceAsksInsteadOfGuessing() {
        store.upsert(task("1", "Finish the garage inventory", at = 100L))
        store.upsert(task("2", "Email the plumber", at = 200L))
        val text = reply(responder(), "continue that task")
        assertTrue(text.contains("don't want to guess which one you mean"))
        assertTrue(text.contains("1. Finish the garage inventory"))
        assertTrue(text.contains("2. Email the plumber"))
        assertTrue("must not act before the owner picks", resumed.isEmpty())
    }

    @Test fun numberPicksExactlyOneTask() {
        store.upsert(task("1", "Finish the garage inventory", at = 100L))
        store.upsert(task("2", "Email the plumber", at = 200L))
        reply(responder(), "continue that task")
        reply(responder(), "2")
        assertEquals(listOf("2"), resumed.map { it.taskId })
    }

    @Test fun titlePicksExactlyOneTask() {
        store.upsert(task("1", "Finish the garage inventory", at = 100L))
        store.upsert(task("2", "Email the plumber", at = 200L))
        reply(responder(), "continue that task")
        reply(responder(), "email the plumber")
        assertEquals(listOf("2"), resumed.map { it.taskId })
    }

    @Test fun misunderstoodFollowUpStaysAnOrdinaryRequestRatherThanConsent() {
        store.upsert(task("1", "Finish the garage inventory", at = 100L))
        store.upsert(task("2", "Email the plumber", at = 200L))
        reply(responder(), "continue that task")
        assertNull(responder().resolve("what's the weather like"))
        assertTrue("must not act on an unmatched follow-up", resumed.isEmpty())
    }

    @Test fun cancelPersistsCancelledStateAndStaysCancelled() {
        store.upsert(task("1", "Finish the garage inventory"))
        val text = reply(responder(), "cancel that task")
        assertEquals(TaskState.CANCELLED, store.get("1")?.state)
        assertEquals("", store.get("1")?.nextStep)
        assertEquals("true", store.get("1")?.metadata?.get("cancelledByOwner"))
        assertTrue(text.contains("Cancelled \"Finish the garage inventory\""))
        // A restart must not resurrect cancelled work as unfinished.
        assertTrue(reply(responder(), "what's unfinished").contains("no unfinished tasks saved"))
    }

    @Test fun ambiguousCancelAlsoAsksFirst() {
        store.upsert(task("1", "Finish the garage inventory", at = 100L))
        store.upsert(task("2", "Email the plumber", at = 200L))
        reply(responder(), "cancel that task")
        assertEquals(TaskState.ACTIVE, store.get("1")?.state)
        reply(responder(), "1")
        assertEquals(TaskState.CANCELLED, store.get("1")?.state)
        assertEquals(TaskState.ACTIVE, store.get("2")?.state)
    }

    @Test fun duplicateResumeIsRefusedWhileTheOutcomeIsUnknown() {
        store.upsert(task("1", "Send the invoice"))
        val first = reply(responder(), "continue that task")
        assertTrue(first.contains("Continuing \"Send the invoice\""))
        val second = reply(responder(), "continue that task")
        assertTrue(second.contains("don't know how that attempt ended"))
        assertTrue(second.contains("not running it again"))
        assertTrue(second.contains("cancel that task"))
        assertEquals("must attempt exactly once", 1, resumed.size)
    }

    @Test fun aSettledTaskIsResumedAgainOnceItsOutcomeIsKnown() {
        // The runtime records the attempt's real outcome on the checkpoint. ACTIVE with the
        // in-flight marker cleared means the turn finished, so a later explicit request is fine.
        store.upsert(task("1", "Send the invoice", metadata = mapOf("followThroughInFlight" to "false")))
        reply(responder(), "continue that task")
        assertEquals(1, resumed.size)
        reply(responder(), "continue that task")
        assertEquals("settled work may be retried on a fresh explicit request", 2, resumed.size)
    }

    @Test fun taskWithNoStoredPromptIsNotResumedFromGuesswork() {
        store.upsert(task("1", "Mystery task", prompt = null))
        val text = reply(responder(), "continue that task")
        assertTrue(text.contains("without the request that started it"))
        assertTrue(text.contains("guessing what you asked for"))
        assertTrue(resumed.isEmpty())
    }

    @Test fun failedStartLiftsTheBlockBecauseNothingIsOutstanding() {
        val failing = SageTaskFollowThroughResponder(
            inner = EmptySagePersonalResponder,
            store = store,
            resume = { error("runtime refused") },
            nowMs = { clock += 10; clock }
        )
        store.upsert(task("1", "Send the invoice"))
        val text = reply(failing, "continue that task")
        assertTrue(text.contains("couldn't start \"Send the invoice\""))
        assertTrue(text.contains("haven't retried it"))
        assertEquals("failed attempt must not strand the task", "true", store.get("1")?.metadata?.get("followThroughInFlight"))
    }

    @Test fun brokenStoreProducesHonestLocalFailure() {
        val broken = SageTaskFollowThroughResponder(
            inner = EmptySagePersonalResponder,
            store = object : TaskContinuityStore {
                override fun upsert(checkpoint: TaskCheckpoint) = error("broken store")
                override fun get(taskId: String): TaskCheckpoint? = error("broken store")
                override fun active(): List<TaskCheckpoint> = error("broken store")
                override fun recent(limit: Int): List<TaskCheckpoint> = error("broken store")
                override fun remove(taskId: String) = error("broken store")
            },
            resume = { error("must not resume") }
        )
        val text = reply(broken, "what's unfinished")
        assertTrue(text.contains("couldn't read my unfinished tasks"))
        assertTrue(text.contains("haven't resumed or cancelled anything"))
    }

    @Test fun alreadySettledTaskIsReportedRatherThanChanged() {
        store.upsert(task("1", "Old task", state = TaskState.COMPLETED))
        val text = reply(responder(), "continue that task")
        assertTrue(text.contains("is completed now"))
        assertTrue(resumed.isEmpty())
    }

    @Test fun arbitraryRequestsNeverTriggerFollowThroughBySubstring() {
        val subject = responder()
        listOf(
            "tell me what's unfinished later", "what is unfinished in the contract",
            "explain how to continue that task", "cancel that task and delete memories",
            "remember continue that task", "don't cancel that task", ""
        ).forEach { assertNull(it, subject.resolve(it)) }
        assertTrue(resumed.isEmpty())
    }

    @Test fun politeAndSpokenVariantsAreRecognised() {
        store.upsert(task("1", "Finish the garage inventory"))
        val subject = responder()
        listOf("what is still unfinished", "Sage, what's unfinished!", "what did we leave",
            "what is unfinished please").forEach {
            assertTrue(it, reply(subject, it).contains("unfinished"))
        }
        listOf("continue the task", "resume that task", "Sage, keep going!").forEach {
            assertTrue(it, reply(subject, it).contains("Continuing"))
        }
    }

    @Test fun everyCommandAnswersLocallyAndNeverReachesTheBrain() {
        store.upsert(task("1", "Finish the garage inventory"))
        val router = SageCommandRouter(personal = responder())
        val local = listOf("what's unfinished", "continue that task", "cancel that task")
        local.forEach { command ->
            val decision = router.route(command)
            assertEquals(command, SageRoute.LOCAL_SAGE, decision.route)
            assertNotNull(command, decision.localReply)
        }
    }

    @Test fun personalTeachingKeepsPrecedenceOverFollowThrough() {
        val personal = SagePersonalCommandEngine()
        val subject = SageTaskFollowThroughResponder(personal, store, resume = { resumed += it })
        assertNotNull("teaching must not be read as a follow-through command", subject.resolve("Teach me something"))
        // Once teaching is in progress the engine owns the next turn.
        val text = (subject.resolve("what's unfinished") as SagePersonalResolution.Reply).text
        assertTrue(text.contains("mean", ignoreCase = true))
    }

    @Test fun ownerLearnedPhrasesStillWinOverFollowThrough() {
        val personal = SagePersonalCommandEngine()
        val subject = SageTaskFollowThroughResponder(personal, store, resume = { resumed += it })
        assertNull("an untaught ordinary request is not a follow-through command", subject.resolve("book me a flight"))
        assertTrue(resumed.isEmpty())
    }

    @Test fun selfCheckAnswerIsStillReachableThroughTheChain() {
        val store2 = MemoryTaskStore()
        store2.upsert(task("1", "Finish the garage inventory"))
        val chain = SageTaskFollowThroughResponder(
            inner = com.pineapple.sageos2.maintenance.SageSelfCheckResponder(EmptySagePersonalResponder) {
                com.pineapple.sageos2.maintenance.SelfCheckReport(
                    com.pineapple.sageos2.maintenance.SelfCareSnapshot(
                        brainReady = false, brainDetail = "model missing", wakeReady = true,
                        wakeDetail = "listening", coreRevision = 218L, legacyCorePresent = false,
                        commandSpeechReady = null, commandSpeechDetail = ""
                    ),
                    emptyList(),
                    1
                )
            },
            store = store2,
            resume = { error("must not resume") }
        )
        assertTrue((chain.resolve("check yourself") as SagePersonalResolution.Reply).text.contains("brain reported status: not available"))
        assertFalse((chain.resolve("what's unfinished") as SagePersonalResolution.Reply).text.contains("brain reported"))
    }

    @Test fun inFlightMarkerIsRecordedForDiagnostics() {
        store.upsert(task("1", "Send the invoice"))
        reply(responder(), "continue that task")
        assertEquals("true", store.get("1")?.metadata?.get("followThroughInFlight"))
        assertTrue(events.any { it.startsWith("owner resume requested task=1") })
    }

    private class MemoryTaskStore : TaskContinuityStore {
        private val tasks = linkedMapOf<String, TaskCheckpoint>()
        override fun upsert(checkpoint: TaskCheckpoint) { tasks[checkpoint.taskId] = checkpoint }
        override fun get(taskId: String) = tasks[taskId]
        override fun active() = tasks.values.filter {
            it.state == TaskState.ACTIVE || it.state == TaskState.WAITING
        }.sortedByDescending { it.updatedAtMs }
        override fun recent(limit: Int) = tasks.values.sortedByDescending { it.updatedAtMs }.take(limit)
        override fun remove(taskId: String) { tasks.remove(taskId) }
    }
}