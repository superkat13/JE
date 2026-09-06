package com.pineapple.sageos2.core

sealed interface SageEffect {
    data class StartWakeListening(val generation: Long) : SageEffect
    data class StartCommandListening(val turnId: Long, val generation: Long) : SageEffect
    data class StartFollowUpListening(val turnId: Long, val generation: Long) : SageEffect
    data object StopListening : SageEffect
    data class Speak(val turnId: Long, val text: String) : SageEffect
    data class SpeakTransient(val text: String) : SageEffect
    data class ExecuteFast(val turnId: Long, val command: String) : SageEffect
    data class QueryDeepBrain(val turnId: Long, val prompt: String) : SageEffect
    data class LaunchOwnerWorkflow(val turnId: Long, val workflowId: String) : SageEffect
    data class StartEchoGuard(val turnId: Long) : SageEffect
    data class RecordDiagnostic(val message: String) : SageEffect
    data class IgnoreStaleCallback(val reason: String) : SageEffect
    data class CancelTurn(val turnId: Long) : SageEffect
}
