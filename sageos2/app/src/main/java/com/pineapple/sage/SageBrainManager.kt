package com.pineapple.sage

/**
 * JNI compatibility boundary for the proven Sage native Brain ABI.
 * SageOS 2 keeps identity, memory, routing, lifecycle, and policy outside JNI.
 */
class SageBrainManager {
    external fun nativeLoadModel(modelPath: String): Boolean
    external fun nativeGenerate(
        requestId: Long,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
        deterministic: Boolean
    ): String
    external fun nativeCancelGeneration(requestId: Long)
    external fun nativeLastFirstTokenLatencyMs(): Long
    external fun nativeLastGenerationDurationMs(): Long
    external fun nativeLastPromptPrefillDurationMs(): Long
    external fun nativeLastPromptTokenCount(): Int
    external fun nativeLastGeneratedTokenCount(): Int
    external fun nativeLastPromptTokensPerSecond(): Float
    external fun nativeLastStage(): String
    external fun nativeUnloadModel()
    external fun nativeLastError(): String
}
