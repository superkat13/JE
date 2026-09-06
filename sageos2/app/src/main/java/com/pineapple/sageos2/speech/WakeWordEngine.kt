package com.pineapple.sageos2.speech

data class WakeWordHealth(
    val ready: Boolean,
    val engine: String,
    val detail: String
)

/**
 * Wake detection is deliberately separate from full command recognition.
 * It should remain small enough to live beside the selected Android
 * VoiceInteractionService without loading the Brain or a general ASR model.
 */
interface WakeWordEngine {
    fun start(generation: Long, onWake: (generation: Long) -> Unit)
    fun stop()
    fun health(): WakeWordHealth
    fun close() = Unit
}
