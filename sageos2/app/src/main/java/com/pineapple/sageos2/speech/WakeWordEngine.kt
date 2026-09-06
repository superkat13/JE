package com.pineapple.sageos2.speech

data class WakeWordHealth(
    val ready: Boolean,
    val engine: String,
    val detail: String
)

/**
 * Wake detection is deliberately separate from full command recognition.
 * It receives profiles as data, so changing a phrase or mode does not create
 * another speech state machine or another Sage identity.
 */
interface WakeWordEngine {
    fun configure(profiles: List<WakeProfile>)
    fun start(generation: Long, onWake: (WakeHit) -> Unit)
    fun stop()
    fun health(): WakeWordHealth
    fun close() = Unit
}
