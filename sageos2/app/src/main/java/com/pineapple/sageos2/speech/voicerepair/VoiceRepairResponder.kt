package com.pineapple.sageos2.speech.voicerepair

import com.pineapple.sageos2.personal.SagePersonalResolution
import com.pineapple.sageos2.personal.SagePersonalResponder
import java.util.Locale

/** Local conversation entry for the bounded voice-repair loop.
 * A repair request first asks the owner to type a short test phrase; only that typed phrase is
 * consumed as a test phrase on the next turn, so it never executes as an ordinary command or tool.
 */
class VoiceRepairResponder(
    private val inner: SagePersonalResponder,
    private val manager: VoiceRepairSessionManager,
    private val startRepair: (String) -> Boolean = { false },
    private val cancelRepair: () -> Unit = {},
    private val latestReport: () -> String = { "" },
    private val interruptedNotice: () -> String = { "" },
    private val exportText: () -> String = { "" },
    private val onDiagnostic: (String) -> Unit = {}
) : SagePersonalResponder {
    private var awaitingPhrase = false

    override fun resolve(rawText: String): SagePersonalResolution? {
        if (rawText.isBlank()) return inner.resolve(rawText)
        val command = normalize(rawText)
        when {
            command in CANCEL_COMMANDS -> return SagePersonalResolution.Reply(cancelReply())
            command in STATUS_COMMANDS -> return SagePersonalResolution.Reply(statusReply())
            command in EXPORT_COMMANDS -> return SagePersonalResolution.Reply(exportReply())
            awaitingPhrase -> return SagePersonalResolution.Reply(startWithTypedPhrase(rawText))
            command in FIX_DIAGNOSE_COMMANDS -> return SagePersonalResolution.Reply(requestPhraseReply())
        }
        return inner.resolve(rawText)
    }

    private fun requestPhraseReply(): String {
        if (liveActive()) return "A voice test is already running. Say “cancel voice repair” to stop it first."
        awaitingPhrase = true
        return "I'm going to test my microphone before changing anything. Type one short phrase " +
            "(1–$MAX_PHRASE characters) that you'll say out loud, then speak it when I start listening. " +
            "Say “cancel voice repair” to stop."
    }

    private fun startWithTypedPhrase(rawText: String): String {
        val phrase = rawText.trim()
        awaitingPhrase = false
        if (phrase.isEmpty() || phrase.length > MAX_PHRASE) {
            awaitingPhrase = true
            return "That is not a usable test phrase. Type a short phrase (1–$MAX_PHRASE characters), " +
                "or say “cancel voice repair”."
        }
        if (liveActive()) return "A voice test is already running. Say “cancel voice repair” to stop it first."
        val started = runCatching { startRepair(phrase) }.getOrDefault(false)
        // Diagnostics are redacted: the owner's typed phrase never enters ordinary traces/exports.
        onDiagnostic(if (started) "voice repair session started; awaiting phrase capture"
            else "voice repair start refused; nothing changed or verified")
        if (started) {
            return "Microphone test starting. Wait for me to say the microphone is actually listening, " +
                "then say “$phrase”. I'll tell you what I actually heard."
        }
        return when (manager.current()?.cause) {
            VoiceRepairCause.BUSY_RUNTIME -> "I couldn't open the microphone right now because another part of Sage is using it. Try again in a moment."
            VoiceRepairCause.DEADLINE_EXCEEDED -> "The previous test timed out, so I stopped it. Type a phrase when you want to try again."
            else -> "I couldn't start the voice test just now. Nothing has been changed or verified."
        }
    }

    private fun cancelReply(): String {
        if (awaitingPhrase) {
            awaitingPhrase = false
            return "Cancelled. No voice test was started and nothing changed."
        }
        if (liveActive()) {
            runCatching { cancelRepair() }
            return "Cancelled the voice test. It is not verified, and nothing was changed."
        }
        return "No active voice repair session to cancel."
    }

    private fun statusReply(): String {
        val active = manager.current()
        if (active != null && active.state in ACTIVE_STATES) {
            if (active.state == VoiceRepairState.REPAIRING) {
                return "The recognizer repair is underway. My microphone is not listening for the retest yet. " +
                    "Wait for a new listening-ready message before speaking."
            }
            return if (readyForCurrentCapture(active)) {
                "Your voice test is running and my microphone is listening now; please say “${active.testPhrase}”. " +
                    "Say “cancel voice repair” to stop it."
            } else {
                "Your voice test is running, but my microphone is not listening yet. Wait for me to say it is " +
                    "listening, then say “${active.testPhrase}”. Say “cancel voice repair” to stop it."
            }
        }
        val interrupted = interruptedNotice()
        val report = latestReport()
        return when {
            interrupted.isNotBlank() && report.isNotBlank() -> "$interrupted\n\n$report"
            interrupted.isNotBlank() -> interrupted
            report.isNotBlank() -> report
            else -> "I don't have a completed voice test to report yet. Say “fix my hearing” to start one."
        }
    }

    private fun exportReply(): String {
        val exported = exportText()
        return exported.ifBlank { "There is no completed voice test to export. Run one with “fix my hearing” first." }
    }

    /** Readiness belongs to the current capture, never an earlier test before a repair. */
    private fun readyForCurrentCapture(session: VoiceRepairSession): Boolean {
        if (session.state != VoiceRepairState.TESTING_EXPECTED && session.state != VoiceRepairState.RETESTING) return false
        val start = session.steps.indexOfLast { it.name == "test_start" || it.name == "retest_start" }
        return start >= 0 && session.steps.drop(start + 1).any { it.name == "ready" }
    }

    private fun liveActive(): Boolean = manager.current()?.state?.let { it in ACTIVE_STATES } == true

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
        const val MAX_PHRASE = 200
        val DIAGNOSE_COMMANDS = setOf("diagnose my voice", "diagnose voice", "check my voice", "check your hearing", "diagnose your hearing")
        val FIX_COMMANDS = setOf("fix my hearing", "fix your hearing", "repair your hearing", "fix my voice", "repair my voice", "fix your voice", "repair your voice")
        val CANCEL_COMMANDS = setOf("cancel voice repair", "stop voice repair")
        val STATUS_COMMANDS = setOf(
            "did the voice repair work", "did the repair work", "voice repair status",
            "what happened with my voice test", "how did my voice test go", "what did the voice test find",
            "did my hearing get fixed"
        )
        val EXPORT_COMMANDS = setOf("export my voice test", "export the voice test evidence", "prepare the voice test export")
        val FIX_DIAGNOSE_COMMANDS = DIAGNOSE_COMMANDS + FIX_COMMANDS
        val COMMANDS = FIX_DIAGNOSE_COMMANDS + CANCEL_COMMANDS + STATUS_COMMANDS + EXPORT_COMMANDS

        private val ACTIVE_STATES = setOf(
            VoiceRepairState.DIAGNOSING, VoiceRepairState.TESTING_EXPECTED,
            VoiceRepairState.REPAIRING, VoiceRepairState.RETESTING
        )
    }
}