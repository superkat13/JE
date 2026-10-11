package com.pineapple.sageos2.speech

import com.pineapple.sageos2.core.SageListeningMode

interface SpeechInputListener {
    fun onWakeDetected(hit: WakeHit)
    fun onTranscriptFinal(turnId: Long, generation: Long, text: String)
    fun onRecognitionError(turnId: Long, generation: Long, code: Int)
    /** Called only when the concrete recognizer reports readiness to accept speech. */
    fun onCommandRecognizerReady(turnId: Long, generation: Long) = Unit
    fun onSpeechDiagnostic(message: String) = Unit
}

interface SpeechPort {
    fun attach(listener: SpeechInputListener)
    fun setListening(mode: SageListeningMode, generation: Long, turnId: Long)
    /** Backwards-compatible provenance overload; AndroidSpeechPort overrides this so ordinary
     * Talk and follow-up don't discard the first 400ms of actual speech. */
    fun setListening(mode: SageListeningMode, generation: Long, turnId: Long, wakeTailPresent: Boolean) =
        setListening(mode, generation, turnId)
    fun speak(turnId: Long, text: String, onComplete: () -> Unit)
    fun speakTransient(text: String)
    fun shutdown() = Unit
}
