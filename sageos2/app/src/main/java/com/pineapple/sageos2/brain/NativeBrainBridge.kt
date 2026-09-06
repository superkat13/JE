package com.pineapple.sageos2.brain

import com.pineapple.sage.SageBrainManager

interface NativeBrainBridge {
    fun loadModel(path: String): Boolean
    fun generate(requestId: Long, systemPrompt: String, userPrompt: String, maxTokens: Int, deterministic: Boolean): String
    fun cancel(requestId: Long)
    fun firstTokenLatencyMs(): Long = -1L
    fun generationDurationMs(): Long = -1L
    fun promptPrefillDurationMs(): Long = -1L
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
    override fun cancel(requestId: Long) = manager.nativeCancelGeneration(requestId)
    override fun firstTokenLatencyMs() = manager.nativeLastFirstTokenLatencyMs()
    override fun generationDurationMs() = manager.nativeLastGenerationDurationMs()
    override fun promptPrefillDurationMs() = manager.nativeLastPromptPrefillDurationMs()
    override fun promptTokenCount() = manager.nativeLastPromptTokenCount()
    override fun generatedTokenCount() = manager.nativeLastGeneratedTokenCount()
    override fun promptTokensPerSecond() = manager.nativeLastPromptTokensPerSecond()
    override fun stage() = manager.nativeLastStage()
    override fun lastError() = manager.nativeLastError()
}
