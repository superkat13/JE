package com.pineapple.sageos2.speech

import com.pineapple.sageos2.core.SageListeningMode

interface SpeechInputListener {
    fun onWakeDetected(generation: Long)
    fun onTranscriptFinal(turnId: Long, generation: Long, text: String)
    fun onRecognitionError(turnId: Long, generation: Long, code: Int)
    fun onSpeechDiagnostic(message: String) = Unit
}

interface SpeechPort {
    fun attach(listener: SpeechInputListener)
    fun setListening(mode: SageListeningMode, generation: Long, turnId: Long)
    fun speak(turnId: Long, text: String, onComplete: () -> Unit)
    fun speakTransient(text: String)
    fun shutdown() = Unit
}
