package com.pineapple.sageos2.runtime

import com.pineapple.sageos2.action.FastActionEngine
import com.pineapple.sageos2.action.FastActionJob
import com.pineapple.sageos2.action.FastActionRequest
import com.pineapple.sageos2.brain.BrainProgress
import com.pineapple.sageos2.brain.BrainProgressStage
import com.pineapple.sageos2.brain.BrainRouterEngine
import com.pineapple.sageos2.brain.LocalNativeBrainEngine
import com.pineapple.sageos2.brain.NativeBrainBridge
import com.pineapple.sageos2.core.SageEvent
import com.pineapple.sageos2.core.SageRuntimeState
import com.pineapple.sageos2.core.SageTurnCoordinator
import com.pineapple.sageos2.memory.ConversationEntry
import com.pineapple.sageos2.memory.ConversationHistorySnapshot
import com.pineapple.sageos2.memory.ConversationHistoryStore
import com.pineapple.sageos2.memory.ConversationSpeaker
import com.pineapple.sageos2.speech.SpeechInputListener
import com.pineapple.sageos2.speech.SpeechPort
import com.pineapple.sageos2.workflow.WorkflowEngine
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextChatLocalBrainPathTest {
    @Test fun typedMessageShowsLoadingThenTraversesLocalGgufBoundaryAndReturnsToChat() {
        val model = File.createTempFile("inherited-sage-brain", ".gguf").apply { deleteOnExit() }
        val bridge = BlockingBridge("<think>private chain</think>\nI'm here. Let's do it.")
        val local = LocalNativeBrainEngine(model.absolutePath, bridge = bridge, libraryLoader = {})
        val history = MemoryHistory()
        val observer = RecordingObserver()
        val runtime = runtime(BrainRouterEngine(listOf(local)), history, observer)

        runtime.start()
        runtime.submit(SageEvent.TextSubmitted("Can you hear me?"))

        assertEquals(SageRuntimeState.THINKING_DEEP, runtime.snapshot().state)
        assertTrue(bridge.loadStarted.await(1, TimeUnit.SECONDS))
        assertTrue(observer.progress.any { it.stage == BrainProgressStage.PREPARING })
        assertTrue(observer.progress.any { it.stage == BrainProgressStage.LOADING_MODEL })
        assertEquals(listOf(ConversationSpeaker.OWNER), history.recent(10).entries.map { it.speaker })

        bridge.allowLoad.countDown()
        waitUntil { observer.responses.isNotEmpty() }

        assertEquals("I'm here. Let's do it.", observer.responses.single().second)
        assertEquals(SageRuntimeState.IDLE_WAKE, runtime.snapshot().state)
        assertEquals(
            listOf(ConversationSpeaker.OWNER, ConversationSpeaker.SAGE),
            history.recent(10).entries.map { it.speaker }
        )
        assertEquals("Can you hear me?", bridge.userPrompt)
        assertTrue(bridge.systemPrompt.contains("virtual twin"))
        assertTrue(bridge.systemPrompt.contains("# WHO I AM"))
        assertFalse(bridge.systemPrompt.contains("SAGE TOOL CONTRACT"))
        assertFalse(bridge.systemPrompt.contains("ACTIVE / RECOVERABLE TASKS"))
        assertFalse(bridge.systemPrompt.contains("OWNER: Can you hear me?"))
        assertTrue(bridge.systemPrompt.length + bridge.userPrompt.length <= 3_600)
        assertEquals(16, bridge.maxTokens)
        assertTrue(bridge.deterministic)
        assertTrue(observer.progress.any { it.stage == BrainProgressStage.READING_CONTEXT })
    }

    @Test fun exactSelfCheckTraversesTheWholeTextPathWithMinimalDeterministicPrompt() {
        val model = File.createTempFile("inherited-sage-brain", ".gguf").apply { deleteOnExit() }
        val bridge = BlockingBridge("Brain online.").apply { allowLoad.countDown() }
        val history = MemoryHistory()
        val observer = RecordingObserver()
        val runtime = runtime(
            BrainRouterEngine(listOf(LocalNativeBrainEngine(model.absolutePath, bridge = bridge, libraryLoader = {}))),
            history,
            observer
        )

        runtime.start()
        runtime.submit(SageEvent.TextSubmitted(com.pineapple.sageos2.brain.BrainRequestPolicy.SELF_CHECK_PROMPT))
        waitUntil { observer.responses.isNotEmpty() }

        assertEquals("Brain online.", observer.responses.single().second)
        assertEquals("Output only the requested literal. No explanation. /no_think", bridge.systemPrompt)
        assertTrue(bridge.maxTokens in 4..12)
        assertTrue(bridge.deterministic)
        assertEquals(SageRuntimeState.IDLE_WAKE, runtime.snapshot().state)
    }

    @Test fun localModelLoadFailureReturnsHumanReadableChatStateAndKeepsRawEvidenceBelow() {
        val model = File.createTempFile("broken-sage-brain", ".gguf").apply { deleteOnExit() }
        val bridge = BlockingBridge("unused", loadSucceeds = false).apply { allowLoad.countDown() }
        val local = LocalNativeBrainEngine(model.absolutePath, bridge = bridge, libraryLoader = {})
        val history = MemoryHistory()
        val observer = RecordingObserver()
        val runtime = runtime(BrainRouterEngine(listOf(local)), history, observer)

        runtime.start()
        runtime.submit(SageEvent.TextSubmitted("Are you there?"))
        waitUntil { observer.responses.isNotEmpty() }

        val visible = observer.responses.single().second
        assertTrue(visible.contains("couldn't finish getting ready"))
        assertFalse(visible.contains("llama.cpp"))
        assertTrue(observer.diagnostics.any { it.contains("llama.cpp could not load that GGUF model") })
        assertEquals(SageRuntimeState.IDLE_WAKE, runtime.snapshot().state)
    }

    private fun runtime(
        brain: BrainRouterEngine,
        history: ConversationHistoryStore,
        observer: RuntimeObserver
    ) = SageRuntime(
        coordinator = SageTurnCoordinator(),
        speech = SilentSpeech(),
        brain = brain,
        fastActions = NoFastActions,
        workflows = NoWorkflows,
        scheduler = NoScheduler,
        observer = observer,
        conversationHistory = history
    )

    private class BlockingBridge(
        private val answer: String,
        private val loadSucceeds: Boolean = true
    ) : NativeBrainBridge {
        val loadStarted = CountDownLatch(1)
        val allowLoad = CountDownLatch(1)
        var systemPrompt = ""
        var userPrompt = ""
        var maxTokens = -1
        var deterministic = false

        override fun loadModel(path: String): Boolean {
            loadStarted.countDown()
            assertTrue(allowLoad.await(2, TimeUnit.SECONDS))
            return loadSucceeds
        }

        override fun generate(
            requestId: Long,
            systemPrompt: String,
            userPrompt: String,
            maxTokens: Int,
            deterministic: Boolean
        ): String {
            this.systemPrompt = systemPrompt
            this.userPrompt = userPrompt
            this.maxTokens = maxTokens
            this.deterministic = deterministic
            return answer
        }

        override fun cancel(requestId: Long) = Unit
        override fun generatedTokenCount() = if (answer.isBlank()) 0 else 3
        override fun lastError() = if (loadSucceeds) "" else "llama.cpp could not load that GGUF model"
    }

    private class MemoryHistory : ConversationHistoryStore {
        private val entries = Collections.synchronizedList(mutableListOf<ConversationEntry>())
        override fun record(entry: ConversationEntry) { entries += entry }
        override fun clear() = entries.clear()
        override fun recent(limit: Int) = ConversationHistorySnapshot(
            revision = entries.size.toLong(),
            entries = entries.toList().takeLast(limit)
        )
    }

    private class RecordingObserver : RuntimeObserver {
        val progress = Collections.synchronizedList(mutableListOf<BrainProgress>())
        val responses = Collections.synchronizedList(mutableListOf<Pair<Long, String>>())
        val diagnostics = Collections.synchronizedList(mutableListOf<String>())
        override fun onBrainProgress(progress: BrainProgress) { this.progress += progress }
        override fun onTextResponse(turnId: Long, text: String) { responses += turnId to text }
        override fun onDiagnostic(message: String) { diagnostics += message }
    }

    private class SilentSpeech : SpeechPort {
        override fun attach(listener: SpeechInputListener) = Unit
        override fun setListening(mode: com.pineapple.sageos2.core.SageListeningMode, generation: Long, turnId: Long) = Unit
        override fun speak(turnId: Long, text: String, onComplete: () -> Unit) = onComplete()
        override fun speakTransient(text: String) = Unit
    }

    private object NoFastActions : FastActionEngine {
        override fun start(request: FastActionRequest, callback: (Result<com.pineapple.sageos2.action.FastActionResponse>) -> Unit) =
            object : FastActionJob {
                override val turnId = request.turnId
                override fun cancel() = Unit
            }
    }

    private object NoWorkflows : WorkflowEngine {
        override fun launch(turnId: Long, workflowId: String) = Unit
    }

    private object NoScheduler : RuntimeScheduler {
        override fun schedule(delayMs: Long, task: () -> Unit) = object : ScheduledHandle {
            override fun cancel() = Unit
        }
    }

    private fun waitUntil(timeoutMs: Long = 2_000L, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (!condition()) {
            if (System.nanoTime() >= deadline) error("condition was not met within ${timeoutMs}ms")
            Thread.sleep(10L)
        }
    }
}
