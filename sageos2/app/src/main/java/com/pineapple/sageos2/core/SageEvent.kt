package com.pineapple.sageos2.core

sealed interface SageEvent {
    data object Start : SageEvent
    data object Stop : SageEvent
    /** Internal handoff: retire the old wake generation before taking the diagnostic mic. */
    data object BeginDiagnosticWindow : SageEvent
    /** Internal handoff: issue a fresh wake generation after releasing diagnostic ownership. */
    data object EndDiagnosticWindow : SageEvent
    /** Resume previously accepted typed requests only after the diagnostic gate closes. */
    data object ResumeQueuedText : SageEvent
    data object PushToTalkRequested : SageEvent
    data class WakeDetected(
        val recognizerGeneration: Long,
        val profileId: String = "sage",
        val modeId: String? = null,
        val acknowledgement: String = "Yes",
        val command: String? = null
    ) : SageEvent
    data class WakeAcknowledgementSpoken(val turnId: Long) : SageEvent
    /** A genuine recognizer ready callback, tied to its owning turn and generation. */
    data class CommandRecognizerReady(val turnId: Long, val recognizerGeneration: Long) : SageEvent
    data class TranscriptFinal(val turnId: Long, val recognizerGeneration: Long, val text: String) : SageEvent
    data class RecognitionFailed(val turnId: Long, val recognizerGeneration: Long, val code: Int) : SageEvent
    data class TextSubmitted(val text: String) : SageEvent
    data class RecoverTask(
        val recoveredTaskId: String,
        val ownerPrompt: String,
        val priorPhase: String?,
        val lastAction: String?,
        val lastActionSignature: String?,
        val lastActionSuccess: String?,
        val completedToolCalls: Int,
        val recoveryDepth: Int,
        val replayGuardMetadata: Map<String, String> = emptyMap()
    ) : SageEvent
    data class ResponseReady(val turnId: Long, val text: String, val allowFollowUp: Boolean = true) : SageEvent
    data class BrainFailed(val turnId: Long, val reason: String) : SageEvent
    data class SpeechFinished(val turnId: Long) : SageEvent
    /** Internal flag allows a diagnostic request's spoken reply to finish without opening follow-up ASR. */
    data class EchoGuardElapsed(
        val turnId: Long,
        val suppressFollowUpForDiagnostic: Boolean = false
    ) : SageEvent
    data class FollowUpExpired(val turnId: Long) : SageEvent
}
