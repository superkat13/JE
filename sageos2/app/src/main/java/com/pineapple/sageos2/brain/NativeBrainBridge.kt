package com.pineapple.sageos2.brain

import com.pineapple.sage.SageBrainManager

object BrainFinishReason {
    const val UNKNOWN = "unknown"
    const val NOT_STARTED = "not_started"
    const val END_OF_GENERATION = "eog"
    const val TOKEN_LIMIT = "max_tokens"
    const val OUTPUT_LIMIT = "output_limit"
    const val CONTEXT_LIMIT = "context_limit"
    const val CANCELLED = "cancelled"
    const val DECODE_ERROR = "decode_error"
    const val EMPTY = "empty"
    const val INVALID_REQUEST = "invalid_request"
}

interface NativeBrainBridge {
    fun loadModel(path: String): Boolean
    fun generate(requestId: Long, systemPrompt: String, userPrompt: String, maxTokens: Int, deterministic: Boolean): String
    fun continueGeneration(requestId: Long, maxAdditionalTokens: Int, deterministic: Boolean): String = ""
    fun cancel(requestId: Long)
    fun finishReason(): String = BrainFinishReason.UNKNOWN
    fun firstTokenLatencyMs(): Long = -1L
    fun generationDurationMs(): Long = -1L
    fun promptPrefillDurationMs(): Long = -1L
    fun cachedPromptTokenCount(): Int = -1
    fun promptTokenCount(): Int = -1
    fun generatedTokenCount(): Int = -1
    fun promptTokensPerSecond(): Float = -1f
    fun stage(): String = ""
    fun lastError(): String = ""
}

class JniNativeBrainBridge(
    private val manager: SageBrainManager = SageBrainManager()
) : NativeBrainBridge {
    override fun loadModel(path: String) = manager.nativeLoadModel(path)
    override fun generate(requestId: Long, systemPrompt: String, userPrompt: String, maxTokens: Int, deterministic: Boolean) =
        manager.nativeGenerate(requestId, systemPrompt, userPrompt, maxTokens, deterministic)
    override fun continueGeneration(requestId: Long, maxAdditionalTokens: Int, deterministic: Boolean) =
        manager.nativeContinueGeneration(requestId, maxAdditionalTokens, deterministic)
    override fun cancel(requestId: Long) = manager.nativeCancelGeneration(requestId)
    override fun finishReason() = manager.nativeLastFinishReason()
    override fun firstTokenLatencyMs() = manager.nativeLastFirstTokenLatencyMs()
    override fun generationDurationMs() = manager.nativeLastGenerationDurationMs()
    override fun promptPrefillDurationMs() = manager.nativeLastPromptPrefillDurationMs()
    override fun cachedPromptTokenCount() = manager.nativeLastCachedPromptTokenCount()
    override fun promptTokenCount() = manager.nativeLastPromptTokenCount()
    override fun generatedTokenCount() = manager.nativeLastGeneratedTokenCount()
    override fun promptTokensPerSecond() = manager.nativeLastPromptTokensPerSecond()
    override fun stage() = manager.nativeLastStage()
    override fun lastError() = manager.nativeLastError()
}
