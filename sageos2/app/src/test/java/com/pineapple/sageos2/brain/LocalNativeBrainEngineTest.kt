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
        assertEquals("done", result!!.getOrThrow().text)
    }

    private class FakeBridge : NativeBrainBridge {
        var systemPrompt = ""
        var userPrompt = ""
        var answer = "ok"
        override fun loadModel(path: String) = true
        override fun generate(requestId: Long, systemPrompt: String, userPrompt: String, maxTokens: Int, deterministic: Boolean): String {
            this.systemPrompt = systemPrompt
            this.userPrompt = userPrompt
            return answer
        }
        override fun cancel(requestId: Long) = Unit
    }
}
