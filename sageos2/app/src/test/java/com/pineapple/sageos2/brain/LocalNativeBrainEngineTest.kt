package com.pineapple.sageos2.brain

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNativeBrainEngineTest {
    @Test fun missingLibraryIsReportedUnavailableWithoutCrashing() {
        val engine = LocalNativeBrainEngine(
            modelPath = "/definitely/missing/model.gguf",
            bridge = FakeBridge(),
            libraryLoader = { error("not packaged") }
        )
        val health = engine.health()
        assertFalse(health.ready)
        assertTrue(health.detail.contains("native library unavailable"))
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
