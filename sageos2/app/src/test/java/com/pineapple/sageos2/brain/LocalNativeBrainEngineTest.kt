package com.pineapple.sageos2.brain

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
        assertEquals(24, bridge.maxTokens)
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

    private class FakeBridge : NativeBrainBridge {
        var systemPrompt = ""
        var userPrompt = ""
        var answer = "ok"
        var maxTokens = -1
        var deterministic = false
        var loadSucceeds = true
        var loadCalls = 0
        var generatedTokens = 3
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
        override fun cancel(requestId: Long) = Unit
        override fun generatedTokenCount() = generatedTokens
        override fun lastError() = if (loadSucceeds) "" else "llama.cpp could not load that GGUF model"
    }
}
