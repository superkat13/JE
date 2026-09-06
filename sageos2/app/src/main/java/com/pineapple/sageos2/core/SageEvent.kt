package com.pineapple.sageos2.core

sealed interface SageEvent {
    data object Start : SageEvent
    data object Stop : SageEvent
    data class WakeDetected(val recognizerGeneration: Long) : SageEvent
    data class WakeAcknowledgementSpoken(val turnId: Long) : SageEvent
    data class TranscriptFinal(
        val turnId: Long,
        val recognizerGeneration: Long,
        val text: String
    ) : SageEvent
    data class TextSubmitted(val text: String) : SageEvent
    data class ResponseReady(
        val turnId: Long,
        val text: String,
        val allowFollowUp: Boolean = true
    ) : SageEvent
    data class BrainFailed(val turnId: Long, val reason: String) : SageEvent
    data class SpeechFinished(val turnId: Long) : SageEvent
    data class EchoGuardElapsed(val turnId: Long) : SageEvent
}
