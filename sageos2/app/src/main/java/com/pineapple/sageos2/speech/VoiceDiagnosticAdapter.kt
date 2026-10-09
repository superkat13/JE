package com.pineapple.sageos2.speech

import com.pineapple.sageos2.speech.voicerepair.VoiceDiagnosticPort
import com.pineapple.sageos2.speech.voicerepair.VoiceRepairTestResult

class VoiceDiagnosticAdapter(
    private val port: SpeechPort
) : VoiceDiagnosticPort {
    private var currentOwner: String? = null
    private var resultCallback: ((VoiceRepairTestResult) -> Unit)? = null

    override fun acquire(owner: String): Boolean {
        if (currentOwner != null) return false
        currentOwner = owner
        return true
    }

    override fun capture(owner: String, expected: String, result: (VoiceRepairTestResult) -> Unit) {
        if (currentOwner != owner) return
        resultCallback = result
        // Actual capture would use diagnostic path; simplified for scaffolding
    }

    override fun reset(owner: String, completed: (Boolean) -> Unit) {
        if (currentOwner != owner) {
            completed(false)
            return
        }
        if (port is com.pineapple.sageos2.speech.voicerepair.VoiceRepairCapable) {
            try {
                port.resetRecognizer("repair reset")
                completed(true)
            } catch (e: Exception) {
                completed(false)
            }
        } else {
            completed(false)
        }
    }

    override fun release(owner: String) {
        if (currentOwner != owner) return
        currentOwner = null
        resultCallback = null
    }
}
