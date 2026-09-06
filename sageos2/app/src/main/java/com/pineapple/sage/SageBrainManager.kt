package com.pineapple.sage

/**
 * JNI compatibility boundary for the proven Sage 1.x native Brain ABI.
 * SageOS 2 keeps identity, memory, routing, lifecycle, and policy outside JNI.
 */
class SageBrainManager {
    external fun nativeLoadModel(modelPath: String): Boolean
    external fun nativeGenerate(
        requestId: Long,
        prompt: String,
        context: String,
        maxTokens: Int,
        streamOrReserved: Boolean
    ): String
    external fun nativeCancelGeneration(requestId: Long)
}
