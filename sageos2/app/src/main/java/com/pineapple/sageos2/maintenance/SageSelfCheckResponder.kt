package com.pineapple.sageos2.maintenance

import com.pineapple.sageos2.personal.SagePersonalResolution
import com.pineapple.sageos2.personal.SagePersonalResponder
import java.util.Locale

/** Exact local commands use the ordinary coordinator turn, queue and response path. */
class SageSelfCheckResponder(
    private val personal: SagePersonalResponder,
    private val check: () -> SelfCheckReport
) : SagePersonalResponder {
    override fun resolve(rawText: String): SagePersonalResolution? {
        // Teaching, memory capture and owner-learned phrases retain their existing precedence.
        personal.resolve(rawText)?.let { return it }
        val command = rawText.lowercase(Locale.ROOT).trim()
            .replace(Regex("[.!?,]+"), " ")
            .replace(Regex("\\s+"), " ").trim()
            .removePrefix("sage ").removePrefix("please ").removeSuffix(" please")
        if (command !in COMMANDS) return null
        val reply = try {
            check().render()
        } catch (_: Exception) {
            "I couldn't complete my health check. I haven't verified my health or repaired anything. " +
                "Check my diagnostics for the self_care failure."
        }
        return SagePersonalResolution.Reply(reply)
    }

    private companion object {
        val COMMANDS = setOf("check yourself", "check your health", "run a self check", "run self check")
    }
}

data class SelfCheckReport(
    val snapshot: SelfCareSnapshot,
    val findings: List<SelfCareFinding>,
    val unfinishedTaskCount: Int
) {
    fun render(): String = buildString {
        append("I checked my current health without asking the language model.\n")
        append("Local brain reported status: ").append(if (snapshot.brainReady) "available" else "not available")
        append(" — ").append(snapshot.brainDetail.ifBlank { "No additional detail." }).append('\n')
        append("Wake service reported status: ").append(if (snapshot.wakeReady) "ready" else "not ready")
        append(" — ").append(snapshot.wakeDetail.ifBlank { "No additional detail." }).append('\n')
        append("Command speech dependencies: ").append(when (snapshot.commandSpeechReady) {
            true -> "present"
            false -> "missing"
            null -> "not checked"
        })
        if (snapshot.commandSpeechDetail.isNotBlank()) append(" — ").append(snapshot.commandSpeechDetail)
        append("\nSage Core revision: ").append(snapshot.coreRevision)
        append("\nUnfinished tasks (excluding health findings): ").append(unfinishedTaskCount)
        if (findings.isEmpty()) {
            append("\nNo problems were reported by these checks.")
        } else {
            append("\nNeeds attention:")
            findings.forEach {
                append("\n• ").append(it.title).append(": ").append(it.summary)
                append("\nNext step: ").append(it.nextStep)
            }
        }
        append("\nInference, microphone capture, and speech recognition were not tested. This is a status check, not an end-to-end task test. I haven't applied repairs or retried your tasks.")
    }
}
