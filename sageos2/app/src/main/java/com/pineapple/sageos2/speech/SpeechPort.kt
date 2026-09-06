package com.pineapple.sageos2.speech

import com.pineapple.sageos2.core.SageListeningMode

interface SpeechPort {
    fun setListening(mode: SageListeningMode, generation: Long, turnId: Long)
    fun speak(turnId: Long, text: String, onComplete: () -> Unit)
    fun speakTransient(text: String)
}
