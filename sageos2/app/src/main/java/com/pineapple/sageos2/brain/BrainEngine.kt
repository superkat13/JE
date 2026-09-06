package com.pineapple.sageos2.brain

import com.pineapple.sageos2.identity.SageCoreSnapshot
import com.pineapple.sageos2.memory.TwinMemorySnapshot

data class BrainRequest(
    val turnId: Long,
    val prompt: String,
    val sageCore: SageCoreSnapshot? = null,
    val twinMemory: TwinMemorySnapshot? = null
)

data class BrainResponse(
    val turnId: Long,
    val text: String,
    val engine: String,
    val provenance: BrainProvenance = BrainProvenance(engine = engine)
)

data class BrainProvenance(
    val engine: String,
    val provider: String? = null,
    val model: String? = null,
    val limitation: String? = null
)

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
