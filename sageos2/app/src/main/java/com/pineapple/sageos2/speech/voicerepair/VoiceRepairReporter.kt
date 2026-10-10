package com.pineapple.sageos2.speech.voicerepair

/** Owner-facing, human-language rendering of a voice repair session. No session IDs, raw codes, or terminal commands. */
object VoiceRepairReporter {

    /** Redacted one-line diagnostic step. Never includes the test phrase, transcripts, or identifiers. */
    fun traceStep(session: VoiceRepairSession): String = buildString {
        append("state=").append(session.state.name)
        append(" cause=").append(session.cause.name)
        append(" attempts=").append(session.attemptCount).append('/').append(session.maxAttempts)
        append(" repair=").append(session.repairAction.name)
        if (session.repairAppliedAtMs != null) append(" applied=true")
        // Redacted onset latency relative to the current capture: how long the recognizer took to
        // report readiness and (if it did) the first speech onset. Timestamps only, never audio.
        val start = session.steps.lastOrNull { it.name == "test_start" || it.name == "retest_start" }?.timestampMs
        val ready = session.steps.lastOrNull { it.name == "ready" }?.timestampMs
        val speech = session.steps.lastOrNull { it.name == "speech_began" }?.timestampMs
        if (start != null && ready != null && ready >= start) append(" ready_ms=").append(ready - start)
        if (start != null && speech != null && speech >= start) append(" speech_ms=").append(speech - start)
        when (session.state) {
            VoiceRepairState.SUCCESS, VoiceRepairState.HEALTHY -> append(" verified=true")
            else -> if (session.cancelled) append(" cancelled=true") else if (session.interrupted) append(" interrupted=true")
        }
    }

    fun interruptedNotice(session: VoiceRepairSession): String =
        "Your last voice test was interrupted before it finished, so it has not been verified as repaired. " +
            "I did not repeat the repair automatically."

    fun terminalReport(session: VoiceRepairSession): String = when (session.state) {
        VoiceRepairState.SUCCESS -> successReport(session)
        VoiceRepairState.HEALTHY -> healthyReport(session)
        VoiceRepairState.FAILED -> failedReport(session)
        VoiceRepairState.INTERRUPTED -> interruptedNotice(session)
        VoiceRepairState.CANCELLED -> "The voice test was cancelled before a result. Nothing has been verified."
        else -> "Your voice test has not finished yet."
    }

    /** Sparse bilingual-safe rendering used by the explicit developer export path. */
    fun exportText(session: VoiceRepairSession): String =
        VoiceRepairExportRenderer.render(VoiceRepairExport.from(session))

    private fun successReport(session: VoiceRepairSession): String = buildString {
        append("Your voice test passed. You typed “").append(session.testPhrase).append("” and I recognized ")
        append("“").append(session.secondTest?.recognized ?: session.firstTest?.recognized ?: session.testPhrase).append("”")
        append(" after rebuilding the recognizer. The repair is verified.")
    }

    private fun healthyReport(session: VoiceRepairSession): String = buildString {
        append("Your voice test passed without any change. I recognized “")
        append(session.firstTest?.recognized ?: session.testPhrase).append("”")
        append(" exactly as expected, so no repair was needed.")
    }

    private fun failedReport(session: VoiceRepairSession): String = buildString {
        append("Your voice test did not pass. ")
        when (session.cause) {
            VoiceRepairCause.NO_SPEECH -> {
                append("I did not hear any speech during the test window ")
                append(testTiming(session.firstTest)).append(". That is not a signal to reset the recognizer.")
            }
            VoiceRepairCause.WRONG_TRANSCRIPT -> {
                append("I heard “").append(session.firstTest?.recognized ?: "nothing").append("” but you typed “")
                append(session.testPhrase).append("”. A mismatch does not justify rebuilding the recognizer.")
            }
            VoiceRepairCause.MISSING_PERMISSION_MODEL -> {
                append("A microphone permission or speech model is missing, so no repair could be applied. ")
                append("This is a separate blocker I cannot change on my own.")
            }
            VoiceRepairCause.RECOGNIZER_LIFECYCLE_FAILURE -> {
                append("The recognizer reported a lifecycle failure")
                append(testTiming(session.firstTest)).append(". ")
            }
            VoiceRepairCause.DEADLINE_EXCEEDED -> append("The test did not finish before its deadline. ")
            VoiceRepairCause.READY_TIMEOUT -> append("The microphone never actually opened for listening in time, so I did not ask you to speak. ")
            VoiceRepairCause.BUSY_RUNTIME -> append("The microphone was busy, so the test could not start. ")
            VoiceRepairCause.CANCELLED_BY_OWNER -> append("The test was cancelled. ")
            VoiceRepairCause.UNSUPPORTED_REPAIR -> append("The recognizer could not be safely rebuilt, so I stopped after one attempt. ")
            VoiceRepairCause.NONE, VoiceRepairCause.SLOW_DOWNSTREAM_INFERENCE -> append("The test ended without a clear result. ")
        }
        append("What I did: ")
        append(if (session.repairAppliedAtMs != null) {
            "I rebuilt the recognizer once, then ran the same phrase again."
        } else {
            "Nothing was changed because no supported repair applied."
        })
        if (session.secondTest != null) {
            val retest = session.secondTest
            if (retest.errorCode == null && !retest.recognized.isNullOrBlank()) {
                append(" On the retest I heard “").append(retest.recognized).append("”.")
            } else {
                append(" On the retest I did not get a matching phrase.")
            }
        }
        append(" This test is not verified as repaired.")
    }

    private fun testTiming(result: VoiceRepairTestResult?): String = when {
        result == null -> ""
        result.elapsedMs > 0L -> " (it ran for " + (result.elapsedMs / 1000L) + "s)"
        else -> ""
    }
}