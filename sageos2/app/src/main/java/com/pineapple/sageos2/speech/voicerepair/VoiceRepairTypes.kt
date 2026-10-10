package com.pineapple.sageos2.speech.voicerepair

import com.pineapple.sageos2.speech.CommandRecognizerBackend

enum class VoiceRepairState {
    IDLE,
    DIAGNOSING,
    TESTING_EXPECTED,
    REPAIRING,
    RETESTING,
    SUCCESS,
    HEALTHY,
    FAILED,
    CANCELLED,
    INTERRUPTED
}

enum class VoiceRepairCause {
    NONE,
    NO_SPEECH,
    WRONG_TRANSCRIPT,
    RECOGNIZER_LIFECYCLE_FAILURE,
    MISSING_PERMISSION_MODEL,
    SLOW_DOWNSTREAM_INFERENCE,
    UNSUPPORTED_REPAIR,
    BUSY_RUNTIME,
    DEADLINE_EXCEEDED,
    CANCELLED_BY_OWNER
}

enum class VoiceRepairAction {
    NONE,
    RESET_RECOGNIZER,
    RECREATE_RECOGNIZER
}

data class VoiceRepairTestResult(
    val expected: String,
    val recognized: String?,
    val errorCode: Int? = null,
    val backend: CommandRecognizerBackend = CommandRecognizerBackend.UNAVAILABLE,
    val elapsedMs: Long = 0L,
    val nonEmpty: Boolean = false
)

data class VoiceRepairStep(
    val name: String,
    val timestampMs: Long,
    val detail: String = ""
)

data class VoiceRepairSession(
    val id: String,
    val state: VoiceRepairState,
    val cause: VoiceRepairCause,
    val attemptCount: Int = 0,
    val maxAttempts: Int = 1,
    val startedAtMs: Long = 0L,
    val endedAtMs: Long? = null,
    val deadlineMs: Long? = null,
    val testPhrase: String = "",
    val firstTest: VoiceRepairTestResult? = null,
    val repairAction: VoiceRepairAction = VoiceRepairAction.NONE,
    val repairAppliedAtMs: Long? = null,
    val secondTest: VoiceRepairTestResult? = null,
    val steps: List<VoiceRepairStep> = emptyList(),
    val history: List<String> = emptyList(),
    val interrupted: Boolean = false,
    val cancelled: Boolean = false
)

data class VoiceRepairExport(
    val sessionId: String,
    val startedAtMs: Long,
    val endedAtMs: Long?,
    val state: VoiceRepairState,
    val cause: VoiceRepairCause,
    val testPhrase: String,
    val expected: String,
    val recognizedBefore: String?,
    val recognizedAfter: String?,
    val repairAction: VoiceRepairAction,
    val repairApplied: Boolean,
    val verified: Boolean,
    val steps: List<VoiceRepairStep>,
    val notes: String
) {
    companion object {
        /** Terminal-session metadata for the explicit developer export. */
        fun from(session: VoiceRepairSession): VoiceRepairExport = VoiceRepairExport(
            sessionId = session.id,
            startedAtMs = session.startedAtMs,
            endedAtMs = session.endedAtMs,
            state = session.state,
            cause = session.cause,
            testPhrase = session.testPhrase,
            expected = session.testPhrase,
            recognizedBefore = session.firstTest?.recognized,
            recognizedAfter = session.secondTest?.recognized,
            repairAction = session.repairAction,
            repairApplied = session.repairAppliedAtMs != null,
            verified = session.state == VoiceRepairState.SUCCESS,
            steps = session.steps,
            notes = if (session.state == VoiceRepairState.HEALTHY) {
                "Initial phrase matched; no repair was needed or performed."
            } else {
                "Explicit diagnostic test only."
            }
        )
    }
}
