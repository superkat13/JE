package com.pineapple.sageos2.brain

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNativeBrainEngineTest {
    @Test fun healthDoesNotLoadNativeLibrary() {
        val model = File.createTempFile("sage-test", ".gguf").apply { deleteOnExit() }
        val loads = AtomicInteger(0)
        val engine = LocalNativeBrainEngine(
            modelPath = model.absolutePath,
            bridge = FakeBridge(),
            libraryLoader = { loads.incrementAndGet() }
        )
        val health = engine.health()
        assertTrue(health.ready)
        assertTrue(health.detail.contains("deferred"))
        assertEquals(0, loads.get())
    }

    @Test fun missingModelIsReportedWithoutTryingNativeLibrary() {
        val loads = AtomicInteger(0)
        val engine = LocalNativeBrainEngine(
            modelPath = "/definitely/missing/model.gguf",
            bridge = FakeBridge(),
            libraryLoader = { loads.incrementAndGet(); error("not packaged") }
        )
        val health = engine.health()
        assertFalse(health.ready)
        assertTrue(health.detail.contains("model file is missing"))
        assertEquals(0, loads.get())
    }

    @Test fun missingLibraryFailsWhenActualTurnNeedsBrain() {
        val model = File.createTempFile("sage-test", ".gguf").apply { deleteOnExit() }
        val engine = LocalNativeBrainEngine(
            modelPath = model.absolutePath,
            bridge = FakeBridge(),
            libraryLoader = { error("not packaged") }
        )
        val latch = CountDownLatch(1)
        var result: Result<BrainResponse>? = null
        engine.start(BrainRequest(turnId = 12L, prompt = "hello", twinContextText = "context")) {
            result = it
            latch.countDown()
        }
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertTrue(result!!.isFailure)
        assertFalse(engine.health().ready)
        assertTrue(engine.health().detail.contains("native library unavailable"))
    }

    @Test fun twinContextIsSystemPromptAndCurrentRequestIsUserPrompt() {
        val model = File.createTempFile("sage-test", ".gguf").apply { deleteOnExit() }
        val bridge = FakeBridge().apply { answer = "done" }
        val engine = LocalNativeBrainEngine(
            modelPath = model.absolutePath,
            bridge = bridge,
            libraryLoader = { }
        )
        assertTrue(engine.health().ready)
        val latch = CountDownLatch(1)
        var result: Result<BrainResponse>? = null
        engine.start(
            BrainRequest(turnId = 77L, prompt = "current user request", twinContextText = "virtual twin system context")
        ) {
            result = it
            latch.countDown()
        }
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertEquals("virtual twin system context", bridge.systemPrompt)
        assertEquals("current user request", bridge.userPrompt)
        assertEquals(48, bridge.maxTokens)
        assertFalse(bridge.deterministic)
        assertEquals("done", result!!.getOrThrow().text)
    }

    @Test fun requestLimitsAndOutputCleanupCrossTheNativeBoundary() {
        val model = File.createTempFile("sage-test", ".gguf").apply { deleteOnExit() }
        val bridge = FakeBridge().apply {
            answer = "<think>private reasoning</think>\nBrain online.<|im_end|>"
        }
        val engine = LocalNativeBrainEngine(model.absolutePath, bridge = bridge, libraryLoader = {})
        val latch = CountDownLatch(1)
        var result: Result<BrainResponse>? = null
        engine.start(
            BrainRequest(
                turnId = 88L,
                prompt = BrainRequestPolicy.SELF_CHECK_PROMPT,
                twinContextText = "Output only the requested literal. /no_think",
                maxOutputTokens = 7,
                deterministic = true,
                expectedLiteral = "Brain online."
            )
        ) {
            result = it
            latch.countDown()
        }
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertEquals(7, bridge.maxTokens)
        assertTrue(bridge.deterministic)
        assertEquals("Brain online.", result!!.getOrThrow().text)
        assertTrue(result!!.getOrThrow().provenance.attempts.contains("requested_tokens=7"))
    }

    /**
     * Failing-first: 48 tokens of output without a native EOS/finish reason
     * are not proof of a complete answer. Never present clipped prose as done.
     */
    @Test fun missingFinishReasonAtTokenCeilingDoesNotBecomeCleanSuccess() {
        val model = File.createTempFile("sage-test", ".gguf").apply { deleteOnExit() }
        val bridge = FakeBridge().apply {
            answer = "First, examine the request. Second, the result is still"
            generatedTokens = 48
        }
        val engine = LocalNativeBrainEngine(model.absolutePath, bridge = bridge, libraryLoader = {})
        val latch = CountDownLatch(1)
        var result: Result<BrainResponse>? = null
        engine.start(BrainRequest(96L, "give a detailed multi-part answer", twinContextText = "context")) {
            result = it
            latch.countDown()
        }
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        val response = result!!.getOrNull()
        val limitation = response?.provenance?.limitation.orEmpty()
        assertTrue("token-limit output without EOS must fail or carry a meaningful incomplete reason",
            result!!.isFailure || (
                limitation.isNotBlank() &&
                    Regex("(?i)(token|budget|limit|truncat|incomplete|unfinished)").containsMatchIn(limitation)
            ))
    }

    @Test fun exactSelfCheckRequiresANativeGeneratedTokenMeasurement() {
        val model = File.createTempFile("sage-test", ".gguf").apply { deleteOnExit() }
        val bridge = FakeBridge().apply {
            answer = "Brain online."
            generatedTokens = 0
        }
        val engine = LocalNativeBrainEngine(model.absolutePath, bridge = bridge, libraryLoader = {})
        val latch = CountDownLatch(1)
        var result: Result<BrainResponse>? = null
        engine.start(
            BrainRequest(
                turnId = 89L,
                prompt = BrainRequestPolicy.SELF_CHECK_PROMPT,
                twinContextText = "Output only the requested literal. /no_think",
                maxOutputTokens = 7,
                deterministic = true,
                expectedLiteral = "Brain online."
            )
        ) {
            result = it
            latch.countDown()
        }
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertTrue(result!!.isFailure)
        assertTrue(result!!.exceptionOrNull()?.message.orEmpty().contains("no measured tokens"))
    }

    @Test fun advancingPrefillTelemetryIsForwardedAsProgress() {
        val model = File.createTempFile("sage-test", ".gguf").apply { deleteOnExit() }
        val bridge = SlowPrefillBridge()
        val engine = LocalNativeBrainEngine(model.absolutePath, bridge = bridge, libraryLoader = {})
        val observed = CopyOnWriteArrayList<BrainProgress>()
        val latch = CountDownLatch(1)
        var result: Result<BrainResponse>? = null

        engine.start(
            BrainRequest(
                turnId = 90L,
                prompt = "context-heavy owner request",
                twinContextText = "virtual twin context",
                onProgress = { observed += it }
            )
        ) {
            result = it
            latch.countDown()
        }

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertEquals("done", result!!.getOrThrow().text)
        val prefillUpdates = observed.filter {
            it.stage == BrainProgressStage.READING_CONTEXT &&
                (it.telemetry?.promptPrefillMs ?: -1L) >= 0L
        }
        assertTrue("expected repeated native prefill progress", prefillUpdates.size >= 2)
    }

    @Test fun failedModelLoadCanBeRetriedWithoutRestartingSage() {
        val model = File.createTempFile("sage-test", ".gguf").apply { deleteOnExit() }
        val bridge = FakeBridge().apply { loadSucceeds = false }
        val engine = LocalNativeBrainEngine(model.absolutePath, bridge = bridge, libraryLoader = {})

        val first = CountDownLatch(1)
        var firstResult: Result<BrainResponse>? = null
        engine.start(BrainRequest(91L, "hello", twinContextText = "context")) {
            firstResult = it
            first.countDown()
        }
        assertTrue(first.await(2, TimeUnit.SECONDS))
        assertTrue(firstResult!!.isFailure)
        assertTrue(engine.health().ready)

        bridge.loadSucceeds = true
        val second = CountDownLatch(1)
        var secondResult: Result<BrainResponse>? = null
        engine.start(BrainRequest(92L, "hello again", twinContextText = "context")) {
            secondResult = it
            second.countDown()
        }
        assertTrue(second.await(2, TimeUnit.SECONDS))
        assertEquals("ok", secondResult!!.getOrThrow().text)
        assertEquals(2, bridge.loadCalls)
    }

    @Test fun tokenLimitedReplyContinuesToEndOfGenerationWithBoundedTelemetry() {
        val model = File.createTempFile("sage-test", ".gguf").apply { deleteOnExit() }
        val bridge = FakeBridge().apply {
            answer = "Sage can finish this thought"
            continuationAnswer = " completely."
            finish = BrainFinishReason.TOKEN_LIMIT
            continuationFinish = BrainFinishReason.END_OF_GENERATION
            generatedTokens = 40
            generationDuration = 40_000L
        }
        val engine = LocalNativeBrainEngine(model.absolutePath, bridge = bridge, libraryLoader = {})
        val latch = CountDownLatch(1)
        var result: Result<BrainResponse>? = null
        engine.start(
            BrainRequest(
                turnId = 101L,
                prompt = "finish that thought",
                twinContextText = "context",
                maxOutputTokens = 40,
                maxCompletionTokens = BrainRequestPolicy.COMPLETION_TOKEN_LIMIT,
                responseTimeoutMs = 120_000L
            )
        ) {
            result = it
            latch.countDown()
        }
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        val response = result!!.getOrThrow()
        assertEquals("Sage can finish this thought completely.", response.text)
        assertEquals(1, bridge.continuationCalls)
        assertTrue(bridge.continuationRequests.single() in 1..56)
        assertTrue(response.provenance.attempts.contains("finish_reason=eog"))
        assertTrue(response.provenance.attempts.contains("natural_completion=true"))
        assertTrue(response.provenance.attempts.contains("continuation_passes=1"))
        assertEquals(null, response.provenance.limitation)
    }

    @Test fun timeGuardStopsContinuationAndReturnsTheLastCompleteSentence() {
        val model = File.createTempFile("sage-test", ".gguf").apply { deleteOnExit() }
        val bridge = FakeBridge().apply {
            answer = "This sentence is complete. This fragment has no ending"
            finish = BrainFinishReason.TOKEN_LIMIT
            generatedTokens = 40
            generationDuration = 40_000L
        }
        val engine = LocalNativeBrainEngine(model.absolutePath, bridge = bridge, libraryLoader = {})
        val latch = CountDownLatch(1)
        var result: Result<BrainResponse>? = null
        engine.start(
            BrainRequest(
                turnId = 102L,
                prompt = "tell me something",
                twinContextText = "context",
                maxOutputTokens = 40,
                maxCompletionTokens = BrainRequestPolicy.COMPLETION_TOKEN_LIMIT,
                responseTimeoutMs = 5_000L
            )
        ) {
            result = it
            latch.countDown()
        }
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        val response = result!!.getOrThrow()
        assertEquals("This sentence is complete.", response.text)
        assertEquals(0, bridge.continuationCalls)
        val limitation = response.provenance.limitation.orEmpty()
        assertTrue(limitation.startsWith("INCOMPLETE_GENERATION:"))
        assertTrue(limitation.contains("complete sentence"))
        assertTrue(response.provenance.attempts.contains("completion_guard=time_guard"))
    }

    @Test fun timeGuardWithoutACompleteSentenceNeverReturnsAPartialReply() {
        val model = File.createTempFile("sage-test", ".gguf").apply { deleteOnExit() }
        val bridge = FakeBridge().apply {
            answer = "This fragment has no ending"
            finish = BrainFinishReason.TOKEN_LIMIT
            generatedTokens = 40
            generationDuration = 40_000L
        }
        val engine = LocalNativeBrainEngine(model.absolutePath, bridge = bridge, libraryLoader = {})
        val latch = CountDownLatch(1)
        var result: Result<BrainResponse>? = null
        engine.start(
            BrainRequest(
                turnId = 106L,
                prompt = "tell me something",
                twinContextText = "context",
                maxOutputTokens = 40,
                maxCompletionTokens = BrainRequestPolicy.COMPLETION_TOKEN_LIMIT,
                responseTimeoutMs = 5_000L
            )
        ) {
            result = it
            latch.countDown()
        }
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertTrue(result!!.isFailure)
        assertTrue(result!!.exceptionOrNull()?.message.orEmpty().contains("no partial reply was shown"))
        assertEquals(0, bridge.continuationCalls)
    }

    @Test fun completeToolResponseContinuesAcrossTheTokenBoundaryAndStillParses() {
        val model = File.createTempFile("sage-test", ".gguf").apply { deleteOnExit() }
        val bridge = FakeBridge().apply {
            answer = "<SAGE_TOOL>\nname=root."
            continuationAnswer = "health\n</SAGE_TOOL>"
            finish = BrainFinishReason.TOKEN_LIMIT
            continuationFinish = BrainFinishReason.END_OF_GENERATION
            generatedTokens = 32
            generationDuration = 32_000L
        }
        val engine = LocalNativeBrainEngine(model.absolutePath, bridge = bridge, libraryLoader = {})
        val latch = CountDownLatch(1)
        var result: Result<BrainResponse>? = null
        engine.start(
            BrainRequest(
                turnId = 103L,
                prompt = "check your root identity",
                twinContextText = "SAGE TOOL CONTRACT",
                maxOutputTokens = 32,
                maxCompletionTokens = BrainRequestPolicy.COMPLETION_TOKEN_LIMIT,
                responseTimeoutMs = 120_000L
            )
        ) {
            result = it
            latch.countDown()
        }
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        val response = result!!.getOrThrow()
        val directive = BrainToolDirectiveParser.parse(response.text)
        assertEquals("root.health", directive!!.name)
        assertEquals(1, bridge.continuationCalls)
    }

    @Test fun incompleteToolResponseIsNeverReturnedAsAnAction() {
        val model = File.createTempFile("sage-test", ".gguf").apply { deleteOnExit() }
        val bridge = FakeBridge().apply {
            answer = "<SAGE_TOOL>\nname=root.health"
            finish = BrainFinishReason.TOKEN_LIMIT
            generatedTokens = 32
            generationDuration = 32_000L
        }
        val engine = LocalNativeBrainEngine(model.absolutePath, bridge = bridge, libraryLoader = {})
        val latch = CountDownLatch(1)
        var result: Result<BrainResponse>? = null
        engine.start(
            BrainRequest(
                turnId = 104L,
                prompt = "check your root identity",
                twinContextText = "SAGE TOOL CONTRACT",
                maxOutputTokens = 32,
                maxCompletionTokens = BrainRequestPolicy.COMPLETION_TOKEN_LIMIT,
                responseTimeoutMs = 5_000L
            )
        ) {
            result = it
            latch.countDown()
        }
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertTrue(result!!.isFailure)
        assertTrue(result!!.exceptionOrNull()?.message.orEmpty().contains("no action was taken"))
        assertEquals(0, bridge.continuationCalls)
    }

    @Test fun exactLiteralNeverSpendsExtraTokensOnContinuation() {
        val model = File.createTempFile("sage-test", ".gguf").apply { deleteOnExit() }
        val bridge = FakeBridge().apply {
            answer = "Brain online."
            finish = BrainFinishReason.TOKEN_LIMIT
            generatedTokens = 4
            generationDuration = 4_000L
        }
        val engine = LocalNativeBrainEngine(model.absolutePath, bridge = bridge, libraryLoader = {})
        val latch = CountDownLatch(1)
        var result: Result<BrainResponse>? = null
        engine.start(
            BrainRequest(
                turnId = 105L,
                prompt = BrainRequestPolicy.SELF_CHECK_PROMPT,
                twinContextText = "Output only the requested literal. /no_think",
                maxOutputTokens = 4,
                maxCompletionTokens = BrainRequestPolicy.COMPLETION_TOKEN_LIMIT,
                responseTimeoutMs = 120_000L,
                deterministic = true,
                expectedLiteral = "Brain online."
            )
        ) {
            result = it
            latch.countDown()
        }
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertEquals("Brain online.", result!!.getOrThrow().text)
        assertEquals(0, bridge.continuationCalls)
    }

    private class SlowPrefillBridge : NativeBrainBridge {
        @Volatile private var startedAtNs = 0L
        override fun loadModel(path: String) = true
        override fun generate(
            requestId: Long,
            systemPrompt: String,
            userPrompt: String,
            maxTokens: Int,
            deterministic: Boolean
        ): String {
            startedAtNs = System.nanoTime()
            Thread.sleep(900L)
            return "done"
        }
        override fun cancel(requestId: Long) = Unit
        override fun stage() = if (startedAtNs == 0L) "prompt_tokenization" else "prompt_prefill"
        override fun promptPrefillDurationMs(): Long {
            val start = startedAtNs
            return if (start == 0L) -1L else (System.nanoTime() - start) / 1_000_000L
        }
        override fun generatedTokenCount() = 3
        override fun lastError() = ""
    }

    private class FakeBridge : NativeBrainBridge {
        var systemPrompt = ""
        var userPrompt = ""
        var answer = "ok"
        var maxTokens = -1
        var deterministic = false
        var loadSucceeds = true
        var loadCalls = 0
        var generatedTokens = 3
        var generationDuration = 1_000L
        var finish = BrainFinishReason.UNKNOWN
        var continuationAnswer = ""
        var continuationFinish = BrainFinishReason.END_OF_GENERATION
        var continuationCalls = 0
        val continuationRequests = CopyOnWriteArrayList<Int>()
        override fun loadModel(path: String): Boolean {
            loadCalls += 1
            return loadSucceeds
        }
        override fun generate(requestId: Long, systemPrompt: String, userPrompt: String, maxTokens: Int, deterministic: Boolean): String {
            this.systemPrompt = systemPrompt
            this.userPrompt = userPrompt
            this.maxTokens = maxTokens
            this.deterministic = deterministic
            return answer
        }
        override fun continueGeneration(requestId: Long, maxAdditionalTokens: Int, deterministic: Boolean): String {
            continuationCalls += 1
            continuationRequests += maxAdditionalTokens
            generatedTokens += 3
            finish = continuationFinish
            return continuationAnswer
        }
        override fun cancel(requestId: Long) = Unit
        override fun finishReason() = finish
        override fun generationDurationMs() = generationDuration
        override fun generatedTokenCount() = generatedTokens
        override fun lastError() = if (loadSucceeds) "" else "llama.cpp could not load that GGUF model"
    }
}
