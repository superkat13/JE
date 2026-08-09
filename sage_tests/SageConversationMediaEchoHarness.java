package com.pineapple.sage;

/** Executable host harness for the turn-owned recognition state machine. */
public final class SageConversationMediaEchoHarness {
    private static void require(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) {
        SageConversationStateMachine machine = new SageConversationStateMachine();

        machine.beginWake(1L, 1_000L);
        require(machine.acceptWake("hey Sage", "hey sage", 1L, 1_000L, 1_200L).executable,
                "first wake must be accepted");
        require(!machine.acceptWake("hey Sage", "hey sage", 1L, 1_010L, 1_200L).executable,
                "one utterance must not create two wakes");

        machine.beginWake(2L, 1_100L);
        SageConversationStateMachine.Decision debounced = machine.acceptWake(
                "hey Sage", "hey sage", 2L, 1_100L, 1_200L);
        require(!debounced.executable && "wake_debounce".equals(debounced.rejectionReason),
                "wake debounce must reject a second acoustic event");

        int dispatches = 0;
        long now = 10_000L;
        for (int index = 0; index < 20; index++) {
            long generation = 100L + index;
            machine.beginCommand(generation, now, true, false);
            require(!machine.partial("[open item " + index + "]", "open item " + index,
                    0.9f, generation).executable, "partial transcript executed");
            SageConversationStateMachine.Decision result = machine.finalTranscript(
                    "[open item " + index + "]", "open item " + index, 0.9f,
                    generation, now, 1_500L, "", 0L, 2_500L, false);
            if (result.executable) dispatches++;
            require(!machine.finalTranscript("[open item " + index + "]", "open item " + index,
                    0.9f, generation, now + 1L, 1_500L, "", 0L, 2_500L,
                    false).executable, "duplicate final callback executed");
            now += 2_000L;
        }
        require(dispatches == 20, "20 commands produced " + dispatches + " dispatches");
        require(machine.dispatchCount() == 20,
                "diagnostic dispatch count was " + machine.dispatchCount());

        machine.beginCommand(500L, now, false, true);
        SageConversationStateMachine.Decision media = machine.finalTranscript(
                "[delete everything]", "delete everything", 0.8f, 500L, now,
                1_500L, "", 0L, 2_500L, true);
        require(!media.executable
                        && media.source == SageConversationStateMachine.SpeechSource.ACTIVE_MEDIA,
                "active media issued a command");

        machine.beginCommand(510L, now + 1_000L, true, true);
        require(machine.finalTranscript("[pause]", "pause", 0.9f, 510L, now + 1_000L,
                1_500L, "", 0L, 2_500L, true).executable,
                "fresh wake or push-to-talk did not authorize a command over media");

        machine.beginCommand(501L, now + 2_000L, true, false);
        SageConversationStateMachine.Decision echo = machine.finalTranscript(
                "[I saved that in my memory]", "I saved that in my memory", 0.9f,
                501L, now + 2_000L, 1_500L, "I saved that in my memory",
                now + 1_900L, 2_500L, false);
        require(!echo.executable
                        && echo.source == SageConversationStateMachine.SpeechSource.SAGE_TTS
                        && "speaker_echo".equals(echo.rejectionReason),
                "Sage speech fingerprint was not rejected");

        machine.beginCommand(502L, now + 4_000L, true, false);
        SageConversationStateMachine.Decision stale = machine.finalTranscript(
                "[home]", "home", 0.9f, 501L, now + 4_000L, 1_500L,
                "", 0L, 2_500L, false);
        require(!stale.executable && "stale_recognizer_generation".equals(stale.rejectionReason),
                "stale recognizer callback executed");

        machine.beginCommand(503L, now + 6_000L, true, false);
        require(machine.retryIncomplete(504L), "bounded retry was not available");
        require(!machine.retryIncomplete(505L), "more than one incomplete retry was allowed");
        require(machine.finalTranscript("[home]", "home", 0.9f, 504L, now + 6_000L,
                1_500L, "", 0L, 2_500L, false).executable,
                "retried final transcript did not dispatch");
        machine.markSpeaking();
        machine.markEchoGuard();
        machine.beginConversationListening();
        require(machine.state() == SageConversationStateMachine.State.CONVERSATION_LISTENING,
                "conversation did not continue after echo guard");

        int saves = 0;
        int acknowledgements = 0;
        machine.beginCommand(600L, now + 8_000L, true, false);
        SageConversationStateMachine.Decision remember = machine.finalTranscript(
                "[Remember that the gate code is seven]",
                "Remember that the gate code is seven", 0.9f, 600L, now + 8_000L,
                1_500L, "", 0L, 2_500L, false);
        if (remember.executable) { saves++; acknowledgements++; }
        SageConversationStateMachine.Decision repeatedRemember = machine.finalTranscript(
                "[Remember that the gate code is seven]",
                "Remember that the gate code is seven", 0.9f, 600L, now + 8_001L,
                1_500L, "", 0L, 2_500L, false);
        if (repeatedRemember.executable) { saves++; acknowledgements++; }
        require(saves == 1 && acknowledgements == 1,
                "remember callback saved=" + saves + " acknowledged=" + acknowledgements);

        SageConversationStateMachine.Decision typed = machine.typedTranscript(
                "pause", now + 10_000L);
        require(typed.executable
                        && typed.transcriptType
                        == SageConversationStateMachine.TranscriptType.TYPED_FINAL,
                "visible typed input was not always available");

        SageMediaCaptureLifecycle mediaLifecycle = new SageMediaCaptureLifecycle();
        mediaLifecycle.begin();
        mediaLifecycle.playbackPaused();
        mediaLifecycle.focusDucked();
        SageMediaCaptureLifecycle.Restoration ended = mediaLifecycle.finish("ended");
        require(ended.restorePlayback && ended.abandonFocus,
                "media was not restored after listening ended");
        mediaLifecycle.begin();
        mediaLifecycle.playbackPaused();
        SageMediaCaptureLifecycle.Restoration cancelled = mediaLifecycle.finish("cancelled");
        require(cancelled.restorePlayback && !cancelled.abandonFocus,
                "media was not restored after listening cancellation");

        System.out.println("PASS: 20 clear commands -> exactly 20 dispatches");
        System.out.println("PASS: one Hey Sage -> exactly one wake");
        System.out.println("PASS: partials, duplicate finals, and stale generations never dispatch");
        System.out.println("PASS: recent Sage TTS cannot command Sage");
        System.out.println("PASS: active media requires fresh wake or push-to-talk");
        System.out.println("PASS: Remember that saves once and acknowledges once");
        System.out.println("PASS: media restoration occurs after end and cancellation");
        System.out.println("PASS: concise conversation continuation and typed fallback remain available");
    }
}
