package com.pineapple.sageos2.speech

/** Recognizer-intent extras shared between the diagnostic port and the local sherpa service. */
object SageSpeechIntents {
    /** Marks a recognition turn as a voice-repair diagnostic capture rather than a command turn.
     * A diagnostic capture starts on an already-idle microphone (the wake engine was stopped and
     * acknowledged), so it must not discard the wake tail — that tail is now the owner's phrase. */
    const val EXTRA_DIAGNOSTIC_CAPTURE = "sage_diagnostic_capture"

    /** Whether a normal command was triggered by a wake-word handoff that needs its audio tail
     * discarded. Old callers lacking this marker preserve the original discard behaviour. */
    const val EXTRA_WAKE_TAIL_PRESENT = "sage_wake_tail_present"
}
