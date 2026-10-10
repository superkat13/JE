package com.pineapple.sageos2.speech.voicerepair

import com.pineapple.sageos2.speech.CommandRecognizerBackend
import org.junit.Assert.*
import org.junit.Test

class VoiceRepairReporterTest {
    private fun session(
        state: VoiceRepairState,
        cause: VoiceRepairCause = VoiceRepairCause.NONE,
        phrase: String = "good morning",
        first: VoiceRepairTestResult? = null,
        second: VoiceRepairTestResult? = null,
        repairedAtMs: Long? = null
    ) = VoiceRepairSession(
        id = "session-secret-42",
        state = state,
        cause = cause,
        attemptCount = if (repairedAtMs != null) 1 else 0,
        startedAtMs = 1_000L,
        endedAtMs = 2_000L,
        deadlineMs = 30_000L,
        testPhrase = phrase,
        firstTest = first,
        repairAction = if (repairedAtMs != null) VoiceRepairAction.RECREATE_RECOGNIZER else VoiceRepairAction.NONE,
        repairAppliedAtMs = repairedAtMs,
        secondTest = second
    )

    private fun recognized(phrase: String, transcript: String?) =
        VoiceRepairTestResult(expected = phrase, recognized = transcript, backend = CommandRecognizerBackend.ANDROID_ON_DEVICE, elapsedMs = 1_200L)

    @Test fun successReportIsHumanAndVerified() {
        val text = VoiceRepairReporter.terminalReport(
            session(
                VoiceRepairState.SUCCESS, phrase = "good morning",
                first = recognized("good morning", "good mourning"),
                second = recognized("good morning", "good morning"),
                repairedAtMs = 1_500L
            )
        )
        assertTrue(text.contains("passed"))
        assertTrue(text.contains("good morning"))
        assertTrue(text.contains("verified"))
        assertFalse(text.contains("session-secret-42"))
    }

    @Test fun healthyReportDoesNotClaimARepair() {
        val text = VoiceRepairReporter.terminalReport(
            session(VoiceRepairState.HEALTHY, first = recognized("good morning", "good morning"))
        )
        assertTrue(text.contains("without any change"))
        assertTrue(text.contains("no repair was needed"))
    }

    @Test fun wrongTranscriptFailureExplainsAndDoesNotClaimRepair() {
        val text = VoiceRepairReporter.terminalReport(
            session(
                VoiceRepairState.FAILED, cause = VoiceRepairCause.WRONG_TRANSCRIPT, phrase = "good morning",
                first = recognized("good morning", "good evening")
            )
        )
        assertTrue(text.contains("did not pass"))
        assertTrue(text.contains("good evening"))
        assertTrue(text.contains("good morning"))
        assertTrue(text.contains("not verified"))
    }

    @Test fun noSpeechFailureDoesNotJustifyAReset() {
        val text = VoiceRepairReporter.terminalReport(
            session(VoiceRepairState.FAILED, cause = VoiceRepairCause.NO_SPEECH, first = recognized("good morning", null))
        )
        assertTrue(text.contains("did not hear any speech"))
        assertTrue(text.contains("not a signal to reset"))
    }

    @Test fun interruptedReportIsOwnerFacing() {
        val text = VoiceRepairReporter.terminalReport(session(VoiceRepairState.INTERRUPTED, cause = VoiceRepairCause.CANCELLED_BY_OWNER))
        assertTrue(text.contains("interrupted"))
        assertTrue(text.contains("has not been verified"))
    }

    @Test fun cancelledReportIsHonest() {
        val text = VoiceRepairReporter.terminalReport(session(VoiceRepairState.CANCELLED, cause = VoiceRepairCause.CANCELLED_BY_OWNER))
        assertTrue(text.contains("cancelled"))
        assertTrue(text.contains("Nothing has been verified"))
    }

    @Test fun traceStepRedactsPhraseAndIdentifier() {
        val trace = VoiceRepairReporter.traceStep(
            session(
                VoiceRepairState.SUCCESS, phrase = "good morning",
                first = recognized("good morning", "good morning"),
                second = recognized("good morning", "good morning"),
                repairedAtMs = 1_500L
            )
        )
        assertFalse(trace.contains("good morning"))
        assertFalse(trace.contains("session-secret-42"))
        assertTrue(trace.contains("SUCCESS"))
        assertTrue(trace.contains("verified=true"))
    }

    @Test fun traceStepMarksInterruptedAndCancelled() {
        assertTrue(VoiceRepairReporter.traceStep(
            session(VoiceRepairState.INTERRUPTED, cause = VoiceRepairCause.DEADLINE_EXCEEDED)
                .copy(interrupted = true)
        ).contains("interrupted=true"))
        assertTrue(VoiceRepairReporter.traceStep(
            session(VoiceRepairState.CANCELLED, cause = VoiceRepairCause.CANCELLED_BY_OWNER)
                .copy(cancelled = true)
        ).contains("cancelled=true"))
    }

    @Test fun exportFromSessionCarriesHonestVerifiedFlag() {
        val success = VoiceRepairExport.from(
            session(
                VoiceRepairState.SUCCESS, phrase = "good morning",
                first = recognized("good morning", "good mourning"),
                second = recognized("good morning", "good morning"),
                repairedAtMs = 1_500L
            )
        )
        assertTrue(success.verified)
        assertTrue(success.repairApplied)
        assertEquals("good morning", success.expected)

        val healthy = VoiceRepairExport.from(session(VoiceRepairState.HEALTHY, first = recognized("good morning", "good morning")))
        assertFalse(healthy.verified)
        assertFalse(healthy.repairApplied)
    }
}