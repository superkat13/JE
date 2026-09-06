package com.pineapple.sageos2.brain

data class BrainRequest(val turnId: Long, val prompt: String)
data class BrainResponse(val turnId: Long, val text: String, val engine: String)

interface BrainJob {
    val turnId: Long
    fun cancel()
}

interface BrainEngine {
    val name: String
    fun start(request: BrainRequest, callback: (Result<BrainResponse>) -> Unit): BrainJob
    fun health(): BrainHealth
}

data class BrainHealth(
    val ready: Boolean,
    val detail: String,
    val lastLatencyMs: Long? = null
)
