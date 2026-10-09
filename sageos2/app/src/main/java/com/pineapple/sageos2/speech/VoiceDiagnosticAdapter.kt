package com.pineapple.sageos2.speech

import android.os.Handler
import android.os.Looper
import com.pineapple.sageos2.speech.voicerepair.VoiceDiagnosticPort
import com.pineapple.sageos2.speech.voicerepair.VoiceRepairTestResult

class VoiceDiagnosticAdapter(private val port: SpeechPort) : VoiceDiagnosticPort {
    private val main = Handler(Looper.getMainLooper())
    private var currentOwner: String? = null
    private var operation = 0L

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == main.looper) action() else main.post { action() }
    }

    override fun acquire(owner: String): Boolean {
        if (Looper.myLooper() != main.looper || owner.isBlank() || currentOwner != null) return false
        val android = port as? AndroidSpeechPort ?: return false
        if (!android.acquireDiagnosticWindow(owner)) return false
        currentOwner = owner
        ++operation
        return true
    }

    override fun capture(owner: String, expected: String, result: (VoiceRepairTestResult) -> Unit) {
        onMain {
            if (currentOwner != owner || owner.isBlank()) return@onMain
            val token = ++operation
            (port as AndroidSpeechPort).captureDiagnosticPhrase(owner, expected) { testResult ->
                if (currentOwner == owner && operation == token) {
                    ++operation
                    result(testResult)
                }
            }
        }
    }

    override fun reset(owner: String, completed: (Boolean) -> Unit) {
        onMain {
            if (currentOwner != owner || owner.isBlank()) { completed(false); return@onMain }
            val token = ++operation
            (port as AndroidSpeechPort).resetDiagnosticRecognizer(owner) { success ->
                if (currentOwner == owner && operation == token) {
                    ++operation
                    completed(success)
                }
            }
        }
    }

    override fun release(owner: String) {
        onMain {
            if (currentOwner != owner) return@onMain
            currentOwner = null
            ++operation
            (port as AndroidSpeechPort).releaseDiagnosticWindow(owner)
        }
    }
}
