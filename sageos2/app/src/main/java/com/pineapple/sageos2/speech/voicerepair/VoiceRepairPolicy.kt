package com.pineapple.sageos2.speech.voicerepair

import android.speech.SpeechRecognizer

object VoiceRepairPolicy {
    const val DEFAULT_TIMEOUT_MS = 30000L
    const val MAX_ATTEMPTS = 1

    val LOCAL_BACKEND_FAILURES = setOf(
        SpeechRecognizer.ERROR_SERVER,
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_AUDIO,
        SpeechRecognizer.ERROR_CLIENT,
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY
    )

    fun canApplyRepair(cause: VoiceRepairCause): Boolean = when (cause) {
        VoiceRepairCause.RECOGNIZER_LIFECYCLE_FAILURE -> true
        VoiceRepairCause.NO_SPEECH -> false
        else -> false
    }

    fun isCommandLike(text: String): Boolean {
        val lower = text.lowercase().trim()
        if (lower.isEmpty()) return false
        return lower.startsWith("delete") ||
            lower.startsWith("send") ||
            lower.startsWith("open") ||
            lower.startsWith("install") ||
            lower.startsWith("uninstall") ||
            lower.startsWith("rm ") ||
            lower.startsWith("remove ") ||
            lower.startsWith("format ") ||
            lower.contains(" factory reset")
    }
}
