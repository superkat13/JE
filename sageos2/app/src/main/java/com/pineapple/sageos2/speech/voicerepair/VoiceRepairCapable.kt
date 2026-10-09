package com.pineapple.sageos2.speech.voicerepair

interface VoiceRepairCapable {
    fun resetRecognizer(reason: String)
    fun resetRecognizer(reason: String, completed: (Boolean) -> Unit) {
        // A fire-and-forget implementation cannot attest that teardown finished.
        // Implement the completion overload explicitly before participating in repair.
        completed(false)
    }
}
