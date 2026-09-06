package com.pineapple.sageos2.runtime

import com.pineapple.sageos2.action.FastActionEngine
import com.pineapple.sageos2.action.FastActionJob
import com.pineapple.sageos2.action.FastActionRequest
import com.pineapple.sageos2.action.FastActionResponse
import com.pineapple.sageos2.brain.BrainEngine
import com.pineapple.sageos2.brain.BrainHealth
import com.pineapple.sageos2.brain.BrainJob
import com.pineapple.sageos2.brain.BrainRequest
import com.pineapple.sageos2.brain.BrainResponse
import com.pineapple.sageos2.core.SageEvent
import com.pineapple.sageos2.core.SageListeningMode
import com.pineapple.sageos2.core.SageRuntimeState
import com.pineapple.sageos2.core.SageTurnCoordinator
import com.pineapple.sageos2.speech.SpeechInputListener
import com.pineapple.sageos2.speech.SpeechPort
import com.pineapple.sageos2.workflow.WorkflowEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SageRuntimeTest {
    @Test
    fun speechInputIsAttachedDirectlyToSingleRuntimeCoordinator() {
        val fixture = Fixture()
        fixture.runtime.start()
        val listener = fixture.speech.listener
        assertNotNull(listener)

        val generation = fixture.runtime.snapshot().recognizerGeneration
        listener!!.onWakeDetected(generation)
        assertEquals(SageRuntimeState.ACKNOWLEDGING_WAKE, fixture.runtime.snapshot().state)
    }

    @Test
    fun deepThoughtKeepsWakeOnlyListenerAndWakeDoesNotCancelBrain() {
        val fixture = Fixture()
        fixture.runtime.start()
        val wakeGeneration = fixture.runtime.snapshot().recognizerGeneration
        fixture.runtime.submit(SageEvent.WakeDetected(wakeGeneration))
        fixture.speech.completeLastSpeech()

        val turn = fixture.runtime.snapshot().activeTurnId
        val commandGeneration = fixture.runtime.snapshot().recognizerGeneration
        fixture.runtime.submit(SageEvent.TranscriptFinal(turn, commandGeneration, "explain black holes"))

        assertEquals(SageRuntimeState.THINKING_DEEP, fixture.runtime.snapshot().state)
        assertEquals(SageListeningMode.WAKE_ONLY, fixture.runtime.snapshot().listeningMode)
        assertEquals(0, fixture.brain.cancelCount)

        val thinkingGeneration = fixture.runtime.snapshot().recognizerGeneration
        fixture.runtime.submit(SageEvent.WakeDetected(thinkingGeneration))
        assertEquals(listOf("I'm thinking"), fixture.speech.transientSpeech)
        assertEquals(0, fixture.brain.cancelCount)
    }

    @Test
    fun brainResponseFlowsThroughSpeechEchoGuardAndFollowUp() {
        val fixture = Fixture()
        fixture.runtime.start()
        fixture.runtime.submit(SageEvent.TextSubmitted("tell me something useful"))
        val turn = fixture.runtime.snapshot().activeTurnId

        fixture.brain.succeed("Useful answer")
        assertEquals(SageRuntimeState.SPEAKING, fixture.runtime.snapshot().state)
        assertEquals("Useful answer", fixture.speech.spoken.last().second)

        fixture.speech.completeLastSpeech()
        assertEquals(SageRuntimeState.ECHO_GUARD, fixture.runtime.snapshot().state)
        fixture.scheduler.runAll()

        assertEquals(SageRuntimeState.FOLLOW_UP_LISTENING, fixture.runtime.snapshot().state)
        assertEquals(SageListeningMode.FOLLOW_UP, fixture.runtime.snapshot().listeningMode)
        assertEquals(turn, fixture.runtime.snapshot().activeTurnId)
    }

    @Test
    fun fastDeviceCommandBypassesBrain() {
        val fixture = Fixture()
        fixture.runtime.start()
        fixture.runtime.submit(SageEvent.TextSubmitted("open youtube"))

        assertEquals(1, fixture.fast.requests.size)
        assertEquals(0, fixture.brain.requests.size)
        fixture.fast.succeed("Opened YouTube")
        assertEquals("Opened YouTube", fixture.speech.spoken.last().second)
    }

    @Test
    fun ownerWorkflowTriggerIsSilent() {
        val fixture = Fixture()
        fixture.runtime.start()
        fixture.runtime.submit(SageEvent.TextSubmitted("Do you feel like chicken tonight?"))

        assertEquals(listOf("chicken_tonight"), fixture.workflows.launched)
        assertTrue(fixture.speech.spoken.isEmpty())
        assertEquals(SageRuntimeState.IDLE_WAKE, fixture.runtime.snapshot().state)
    }

    @Test
    fun queuedTypedInputRunsAfterCurrentReplyWithoutBeingLost() {
        val fixture = Fixture()
        fixture.runtime.start()
        fixture.runtime.submit(SageEvent.TextSubmitted("explain gravity"))
        fixture.runtime.submit(SageEvent.TextSubmitted("open youtube"))
        assertEquals(1, fixture.runtime.snapshot().queuedTextCount)

        fixture.brain.succeed("Gravity answer")
        fixture.speech.completeLastSpeech()
        fixture.scheduler.runAll()

        assertEquals(0, fixture.runtime.snapshot().queuedTextCount)
        assertEquals(1, fixture.fast.requests.size)
        assertEquals("open youtube", fixture.fast.requests.single().command)
    }

    private class Fixture {
        val speech = FakeSpeech()
        val brain = FakeBrain()
        val fast = FakeFastActions()
        val workflows = FakeWorkflows()
        val scheduler = FakeScheduler()
        val observer = FakeObserver()
        val runtime = SageRuntime(
            coordinator = SageTurnCoordinator(),
            speech = speech,
            brain = brain,
            fastActions = fast,
            workflows = workflows,
            scheduler = scheduler,
            observer = observer
        )
    }

    private class FakeSpeech : SpeechPort {
        var listener: SpeechInputListener? = null
        val listening = mutableListOf<Triple<SageListeningMode, Long, Long>>()
        val spoken = mutableListOf<Pair<Long, String>>()
        val transientSpeech = mutableListOf<String>()
        private var completion: (() -> Unit)? = null

        override fun attach(listener: SpeechInputListener) {
            this.listener = listener
        }

        override fun setListening(mode: SageListeningMode, generation: Long, turnId: Long) {
            listening += Triple(mode, generation, turnId)
        }

        override fun speak(turnId: Long, text: String, onComplete: () -> Unit) {
            spoken += turnId to text
            completion = onComplete
        }

        override fun speakTransient(text: String) {
            transientSpeech += text
        }

        fun completeLastSpeech() {
            val callback = completion ?: error("no pending speech")
            completion = null
            callback()
        }
    }

    private class FakeBrain : BrainEngine {
        override val name = "fake"
        val requests = mutableListOf<BrainRequest>()
        var cancelCount = 0
        private var callback: ((Result<BrainResponse>) -> Unit)? = null
        private var activeTurn = 0L

        override fun start(request: BrainRequest, callback: (Result<BrainResponse>) -> Unit): BrainJob {
            requests += request
            this.callback = callback
            activeTurn = request.turnId
            return object : BrainJob {
                override val turnId = request.turnId
                override fun cancel() { cancelCount += 1 }
            }
        }

        override fun health() = BrainHealth(true, "fake ready")

        fun succeed(text: String) {
            val cb = callback ?: error("no active brain callback")
            callback = null
            cb(Result.success(BrainResponse(activeTurn, text, name)))
        }
    }

    private class FakeFastActions : FastActionEngine {
        val requests = mutableListOf<FastActionRequest>()
        private var callback: ((Result<FastActionResponse>) -> Unit)? = null
        private var activeTurn = 0L

        override fun start(request: FastActionRequest, callback: (Result<FastActionResponse>) -> Unit): FastActionJob {
            requests += request
            this.callback = callback
            activeTurn = request.turnId
            return object : FastActionJob {
                override val turnId = request.turnId
                override fun cancel() = Unit
            }
        }

        fun succeed(text: String) {
            val cb = callback ?: error("no active fast-action callback")
            callback = null
            cb(Result.success(FastActionResponse(activeTurn, text)))
        }
    }

    private class FakeWorkflows : WorkflowEngine {
        val launched = mutableListOf<String>()
        override fun launch(turnId: Long, workflowId: String) {
            launched += workflowId
        }
    }

    private class FakeScheduler : RuntimeScheduler {
        private val tasks = mutableListOf<() -> Unit>()
        override fun schedule(delayMs: Long, task: () -> Unit): ScheduledHandle {
            tasks += task
            return object : ScheduledHandle {
                override fun cancel() { tasks.remove(task) }
            }
        }

        fun runAll() {
            val pending = tasks.toList()
            tasks.clear()
            pending.forEach { it() }
        }
    }

    private class FakeObserver : RuntimeObserver
}
