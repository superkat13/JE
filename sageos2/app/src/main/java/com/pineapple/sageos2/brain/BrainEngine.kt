package com.pineapple.sageos2.brain

import com.pineapple.sageos2.apps.OwnerAppSnapshot
import com.pineapple.sageos2.identity.SageCoreSnapshot
import com.pineapple.sageos2.memory.ConversationHistorySnapshot
import com.pineapple.sageos2.memory.TwinMemorySnapshot
import com.pineapple.sageos2.mode.SageModeSnapshot

data class BrainRequest(
    val turnId: Long,
    val prompt: String,
    val sageCore: SageCoreSnapshot? = null,
    val twinMemory: TwinMemorySnapshot? = null,
    val conversationHistory: ConversationHistorySnapshot? = null,
    val ownerApps: OwnerAppSnapshot? = null,
    val mode: SageModeSnapshot? = null,
    val twinContextText: String? = null,
    val maxOutputTokens: Int? = null,
    val deterministic: Boolean = false,
    val expectedLiteral: String? = null,
    val onProgress: (BrainProgress) -> Unit = {}
)

enum class BrainProgressStage {
    PREPARING,
    LOADING_MODEL,
    READING_CONTEXT,
    GENERATING
}

data class BrainProgress(
    val turnId: Long,
    val stage: BrainProgressStage,
    val telemetry: BrainTelemetry? = null
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
    val limitation: String? = null,
    val attempts: List<String> = emptyList()
)

interface BrainJob { val turnId: Long; fun cancel() }
interface BrainEngine {
    val name: String
    fun start(request: BrainRequest, callback: (Result<BrainResponse>) -> Unit): BrainJob
    fun health(): BrainHealth
}

data class BrainHealth(
    val ready: Boolean,
    val detail: String,
    val lastLatencyMs: Long? = null,
    val progressStage: BrainProgressStage? = null,
    val telemetry: BrainTelemetry? = null
)

data class BrainTelemetry(
    val nativeStage: String? = null,
    val promptTokens: Int? = null,
    val generatedTokens: Int? = null,
    val promptPrefillMs: Long? = null,
    val firstTokenMs: Long? = null,
    val generationMs: Long? = null,
    val promptTokensPerSecond: Float? = null
)
