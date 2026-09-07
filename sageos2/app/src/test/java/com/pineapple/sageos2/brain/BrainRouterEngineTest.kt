package com.pineapple.sageos2.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainRouterEngineTest {
    @Test fun providerLimitationFallsThroughToNextConfiguredBrain() {
        val first = FakeEngine("provider-a") { request, callback ->
            callback(Result.failure(BrainFailureException(
                BrainFailureKind.PROVIDER_LIMITATION,
                engineName = "provider-a",
                provider = "A",
                message = "provider refused this request"
            )))
        }
        val second = FakeEngine("local") { request, callback ->
            callback(Result.success(BrainResponse(request.turnId, "handled locally", "local", BrainProvenance("local", provider = "local", model = "test"))))
        }
        val router = BrainRouterEngine(listOf(first, second))
        var result: Result<BrainResponse>? = null
        router.start(BrainRequest(7, "test")) { result = it }
        assertEquals("handled locally", result!!.getOrThrow().text)
        assertTrue(result!!.getOrThrow().provenance.attempts.any { it.contains("PROVIDER_LIMITATION") })
        assertTrue(result!!.getOrThrow().provenance.attempts.any { it.contains("local: attempted") })
    }

    @Test fun unavailableEngineIsSkippedWithoutBecomingSageRefusal() {
        val down = FakeEngine("down", ready = false) { _, _ -> error("should not start") }
        val good = FakeEngine("good") { request, callback -> callback(Result.success(BrainResponse(request.turnId, "ok", "good"))) }
        var result: Result<BrainResponse>? = null
        BrainRouterEngine(listOf(down, good)).start(BrainRequest(1, "hello")) { result = it }
        assertEquals("ok", result!!.getOrThrow().text)
    }

    @Test fun successfulEngineEvidenceIsPreservedAlongsideRouterAttempts() {
        val local = FakeEngine("local") { request, callback ->
            callback(Result.success(BrainResponse(
                request.turnId,
                "ok",
                "local",
                BrainProvenance("local", attempts = listOf("generated_tokens=8", "first_token_ms=2400"))
            )))
        }
        var result: Result<BrainResponse>? = null
        BrainRouterEngine(listOf(local)).start(BrainRequest(4, "hello")) { result = it }
        val attempts = result!!.getOrThrow().provenance.attempts
        assertTrue(attempts.contains("local: attempted"))
        assertTrue(attempts.contains("generated_tokens=8"))
        assertTrue(attempts.contains("first_token_ms=2400"))
    }

    private class FakeEngine(
        override val name: String,
        private val ready: Boolean = true,
        private val behavior: (BrainRequest, (Result<BrainResponse>) -> Unit) -> Unit
    ) : BrainEngine {
        override fun health() = BrainHealth(ready, if (ready) "ready" else "offline")
        override fun start(request: BrainRequest, callback: (Result<BrainResponse>) -> Unit): BrainJob {
            behavior(request, callback)
            return object : BrainJob {
                override val turnId = request.turnId
                override fun cancel() = Unit
            }
        }
    }
}
