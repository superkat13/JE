package com.pineapple.sageos2.speech

import com.pineapple.sageos2.speech.voicerepair.VoiceDiagnosticPort
import com.pineapple.sageos2.speech.voicerepair.VoiceRepairCapable
import com.pineapple.sageos2.speech.voicerepair.VoiceRepairTestResult

class VoiceDiagnosticAdapter(
    private val port: SpeechPort
) : VoiceDiagnosticPort {
    private var currentOwner: String? = null
    private var resultCallback: ((VoiceRepairTestResult) -> Unit)? = null

    override fun acquire(owner: String): Boolean {
        if (owner.isBlank() || currentOwner != null) return false
        if (port is AndroidSpeechPort) {
            val acquired = port.acquireDiagnosticWindow(owner)
            if (!acquired) return false
        }
        currentOwner = owner
        return true
    }

    override fun capture(owner: String, expected: String, result: (VoiceRepairTestResult) -> Unit) {
        if (currentOwner != owner || owner.isBlank()) return
        resultCallback = result
        if (port is AndroidSpeechPort) {
            port.captureDiagnosticPhrase(owner, expected) { testResult ->
                if (currentOwner == owner) {
                    result(testResult)
                }
            }
        }
    }

    override fun reset(owner: String, completed: (Boolean) -> Unit) {
        if (currentOwner != owner || owner.isBlank()) {
            completed(false)
            return
        }
        if (port is VoiceRepairCapable) {
            try {
                port.resetRecognizer("repair reset", completed)
            } catch (_: Exception) {
                completed(false)
            }
        } else {
            completed(false)
        }
    }

    override fun release(owner: String) {
        if (currentOwner != owner) return
        val previousOwner = currentOwner
        currentOwner = null
        resultCallback = null
        if (previousOwner != null && port is AndroidSpeechPort) {
            port.releaseDiagnosticWindow(previousOwner)
        }
    }
}
