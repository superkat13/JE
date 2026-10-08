package com.pineapple.sageos2.speech.voicerepair

interface VoiceRepairCapable {
    fun resetRecognizer(reason: String)
    fun resetRecognizer(reason: String, completed: (Boolean) -> Unit) {
        resetRecognizer(reason)
        completed(true)
    }
}
