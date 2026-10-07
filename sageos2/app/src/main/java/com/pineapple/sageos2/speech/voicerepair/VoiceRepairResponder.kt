package com.pineapple.sageos2.speech.voicerepair

import com.pineapple.sageos2.personal.SagePersonalResolution
import com.pineapple.sageos2.personal.SagePersonalResponder
import java.util.Locale

/** Local entry must remain honest until a tested capture/repair/retest controller is installed. */
class VoiceRepairResponder(
    private val inner: SagePersonalResponder,
    private val manager: VoiceRepairSessionManager,
    private val startRepair: (() -> String)? = null
) : SagePersonalResponder {
    override fun resolve(rawText: String): SagePersonalResolution? {
        val command = normalize(rawText)
        if (command !in COMMANDS) return inner.resolve(rawText)
        // Repair requests take precedence over personality or learned conversational replies.
        val reply = when (command) {
            in CANCEL_COMMANDS -> if (manager.cancel() == null) {
                "No active voice repair session to cancel."
            } else {
                "Cancelled voice repair. No repair has been verified."
            }
            else -> startRepair?.invoke()
                ?: "I understand that you want me to test and repair my hearing. " +
                    "The microphone test and repair workflow is not connected in this build. " +
                    "I have not started a microphone test or changed anything."
        }
        return SagePersonalResolution.Reply(reply)
    }

    private fun normalize(value: String): String {
        var text = value.lowercase(Locale.ROOT).trim()
            .replace(Regex("[.!?,;]+"), " ")
            .replace(Regex("['\u2019]"), "")
            .replace(Regex("\\s+"), " ").trim()
        // Accept either order: "please Sage ..." and "Sage please ...".
        while (text.startsWith("sage ") || text.startsWith("please ")) {
            text = text.substringAfter(' ').trim()
        }
        return text.removeSuffix(" please").trim()
    }

    companion object {
        val DIAGNOSE_COMMANDS = setOf("diagnose my voice", "diagnose voice", "check my voice", "check your hearing", "diagnose your hearing")
        val FIX_COMMANDS = setOf("fix my hearing", "fix your hearing", "repair your hearing", "fix my voice", "repair my voice", "fix your voice", "repair your voice")
        val CANCEL_COMMANDS = setOf("cancel voice repair", "stop voice repair")
        val COMMANDS = DIAGNOSE_COMMANDS + FIX_COMMANDS + CANCEL_COMMANDS
    }
}
