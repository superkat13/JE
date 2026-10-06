package com.pineapple.sageos2.speech.voicerepair

import com.pineapple.sageos2.personal.SagePersonalResolution
import com.pineapple.sageos2.personal.SagePersonalResponder
import java.util.Locale

class VoiceRepairResponder(
    private val inner: SagePersonalResponder,
    private val manager: VoiceRepairSessionManager
) : SagePersonalResponder {
    override fun resolve(rawText: String): SagePersonalResolution? {
        inner.resolve(rawText)?.let { return it }
        val normalized = normalize(rawText)
        if (normalized !in COMMANDS) return null
        return handle(normalized)
    }

    private fun handle(command: String): SagePersonalResolution {
        val current = manager.current()
        return when {
            command in DIAGNOSE_COMMANDS -> {
                val session = manager.startSession("test phrase")
                SagePersonalResolution.Reply(
                    "Starting a bounded voice repair session (session ${session.id.take(8)}). " +
                        "I'll diagnose your voice. " +
                        "Please type the expected short phrase and speak it when ready. " +
                        "You can say 'cancel' or I'll auto-timeout in ${VoiceRepairPolicy.DEFAULT_TIMEOUT_MS / 1000}s."
                )
            }
            command in FIX_COMMANDS -> {
                val session = manager.startSession("test phrase")
                SagePersonalResolution.Reply(
                    "Starting voice repair (session ${session.id.take(8)}). " +
                        "I need you to type the expected short phrase once and speak it. " +
                        "I'll test, attempt one supported repair only if needed, then retest. " +
                        "Cancel with 'cancel voice repair' or wait for timeout."
                )
            }
            command in CANCEL_COMMANDS -> {
                val cancelled = manager.cancel()
                if (cancelled == null) {
                    SagePersonalResolution.Reply("No active voice repair session to cancel.")
                } else {
                    SagePersonalResolution.Reply("Cancelled voice repair session ${cancelled.id.take(8)}.")
                }
            }
            else -> SagePersonalResolution.Reply("Voice repair command not recognized.")
        }
    }

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT).trim()
        .replace(Regex("[.!?,;]+"), " ")
        .replace(Regex("['\u2019]"), "")
        .replace(Regex("\\s+"), " ").trim()
        .removePrefix("sage ").removePrefix("please ").removeSuffix(" please").trim()

    companion object {
        val DIAGNOSE_COMMANDS = setOf("diagnose my voice", "diagnose voice", "check my voice")
        val FIX_COMMANDS = setOf("fix my hearing", "fix my voice", "repair my voice")
        val CANCEL_COMMANDS = setOf("cancel voice repair", "stop voice repair")
        val COMMANDS = DIAGNOSE_COMMANDS + FIX_COMMANDS + CANCEL_COMMANDS
    }
}