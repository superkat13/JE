package com.pineapple.sageos2.continuity

import com.pineapple.sageos2.personal.SagePersonalResolution
import com.pineapple.sageos2.personal.SagePersonalResponder
import java.util.Locale

/**
 * Ordinary owner questions about unfinished work, answered from persisted checkpoints instead of the
 * language model. The owner never has to speak or type a task id.
 *
 * Resume stays behind the runtime's existing replay guards. This responder adds an owner-visible
 * layer in front of them: it will not begin a second attempt while the outcome of the previous one
 * is still unknown. Re-running an action that may already have taken effect is worse than saying
 * plainly that the block is real, so the unknown outcome is reported rather than retried.
 */
class SageTaskFollowThroughResponder(
    private val inner: SagePersonalResponder,
    private val store: TaskContinuityStore,
    private val resume: (TaskCheckpoint) -> String?,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val onEvent: (String) -> Unit = {}
) : SagePersonalResponder {

    private enum class Intent(val verb: String) { RESUME("continue"), CANCEL("cancel") }

    private data class Pending(val intent: Intent, val candidates: List<TaskCheckpoint>)

    private var pending: Pending? = null

    override fun resolve(rawText: String): SagePersonalResolution? {
        // Teaching, memory capture and owner-learned phrases keep their existing precedence.
        inner.resolve(rawText)?.let { return it }
        val command = normalize(rawText)
        val open = pending
        pending = null
        return try {
            if (open != null && command !in COMMANDS) {
                val picked = matchCandidate(open.candidates, command)
                if (picked != null) return act(open.intent, picked)
            }
            if (command !in COMMANDS) return null
            handle(command)
        } catch (error: Exception) {
            onEvent("follow_through failed: ${error.message ?: error::class.java.simpleName}")
            SagePersonalResolution.Reply(
                "I couldn't finish that task-management request. Check my diagnostics before retrying; " +
                    "I can't confirm whether the saved state changed."
            )
        }
    }

    private fun handle(command: String): SagePersonalResolution.Reply = when (command) {
        in LIST_COMMANDS -> SagePersonalResolution.Reply(listReply())
        in RESUME_COMMANDS -> choose(Intent.RESUME)
        in CANCEL_COMMANDS -> choose(Intent.CANCEL)
        else -> error("unsupported follow-through command")
    }

    /** Plain-language reading of what is actually saved, with no model call and no invented work. */
    private fun listReply(): String {
        val tasks = recoverable()
        if (tasks.isEmpty()) {
            return "I have no unfinished tasks saved, so there's nothing waiting on either of us. " +
                "I haven't started anything new."
        }
        return buildString {
            append("You have ").append(tasks.size)
            append(if (tasks.size == 1) " unfinished task" else " unfinished tasks")
            append(", read from my own saved notes rather than the language model:\n")
            tasks.forEachIndexed { index, task ->
                append('\n').append(index + 1).append(". ").append(title(task)).append('\n')
                append("   Status: ").append(
                    if (task.metadata["lastActionSuccess"] == "unknown" || task.metadata[IN_FLIGHT] == "true") "previous attempt outcome not confirmed"
                    else if (task.state == TaskState.WAITING) "waiting to be picked back up" else "in progress"
                )
                if (task.summary.isNotBlank()) append("\n   Where I got to: ").append(oneLine(task.summary))
                if (task.nextStep.isNotBlank()) append("\n   Next: ").append(oneLine(task.nextStep))
            }
            append(if (tasks.size == 1) "\n\nSay \"continue that task\" or \"cancel that task\" and I'll act on it."
            else "\n\nSay \"continue that task\" or \"cancel that task\" and I'll ask which one you mean.")
        }
    }

    private fun choose(intent: Intent): SagePersonalResolution.Reply {
        val tasks = recoverable()
        if (tasks.isEmpty()) {
            return SagePersonalResolution.Reply(
                "I have no unfinished tasks saved, so there's nothing to ${intent.verb}. " +
                    "I haven't changed anything."
            )
        }
        if (tasks.size == 1) return act(intent, tasks.single())
        pending = Pending(intent, tasks)
        return SagePersonalResolution.Reply(
            "You have ${tasks.size} unfinished tasks, so I don't want to guess which one you mean:\n" +
                tasks.mapIndexed { index, task -> "${index + 1}. ${title(task)}" }.joinToString("\n") +
                "\nTell me the number or the name and I'll ${intent.verb} that one."
        )
    }

    private fun act(intent: Intent, task: TaskCheckpoint): SagePersonalResolution.Reply {
        // Re-read at the moment of action. Selection and execution are separated by an owner
        // turn, so a checkpoint chosen earlier may since have been cancelled or completed.
        val current = store.get(task.taskId) ?: return settled(intent, task)
        if (current.state != TaskState.ACTIVE && current.state != TaskState.WAITING) {
            return settled(intent, current)
        }
        return when (intent) {
            Intent.RESUME -> resume(current)
            Intent.CANCEL -> cancel(current)
        }
    }

    private fun resume(task: TaskCheckpoint): SagePersonalResolution.Reply {
        if (task.metadata[IN_FLIGHT] == "true" || task.metadata["lastActionSuccess"] == "unknown") {
            return SagePersonalResolution.Reply(
                "I already started \"${title(task)}\" and I still don't know how that attempt ended, " +
                    "so I'm not running it again. Repeating an action that may already have taken " +
                    "effect is how you end up with two of something. Say \"cancel that task\" to drop " +
                    "it, or ask \"what's unfinished\" to see what else is waiting."
            )
        }
        if (task.metadata["ownerPrompt"].isNullOrBlank()) {
            return SagePersonalResolution.Reply(
                "I saved \"${title(task)}\" without the request that started it, so I can't pick it " +
                    "back up safely. Continuing now would mean guessing what you asked for. Please give me a new request describing what it should do."
            )
        }
        val at = nowMs()
        val marked = task.copy(
            updatedAtMs = at,
            metadata = task.metadata + mapOf(
                IN_FLIGHT to "true",
                IN_FLIGHT_AT to at.toString(),
                OWNER_RESUME_AT to at.toString()
            )
        )
        store.upsert(marked)
        onEvent("owner resume requested task=${marked.taskId}")
        return try {
            // A returned reason means the runtime rejected the queue request before execution.
            // A thrown exception has an uncertain outcome and must retain the duplicate guard.
            val rejection = resume.invoke(marked)
            if (rejection != null) {
                store.upsert(task.copy(updatedAtMs = nowMs()))
                SagePersonalResolution.Reply("I couldn't continue \"${title(task)}\": $rejection")
            } else {
                SagePersonalResolution.Reply(
                    "Queued \"${title(task)}\" to continue after this reply" + progressSuffix(task) +
                        ". Previous actions remain protected against replay."
                )
            }
        } catch (error: Exception) {
            onEvent("owner resume uncertain task=${task.taskId}: ${error.message ?: error::class.java.simpleName}")
            SagePersonalResolution.Reply(
                "I couldn't confirm whether \"${title(task)}\" started. I kept the duplicate-attempt " +
                    "guard and haven't retried it. Check my diagnostics."
            )
        }
    }

    private fun cancel(task: TaskCheckpoint): SagePersonalResolution.Reply {
        val at = nowMs()
        store.upsert(
            task.copy(
                state = TaskState.CANCELLED,
                nextStep = "",
                updatedAtMs = at,
                metadata = task.metadata + mapOf(
                    "cancelledByOwner" to "true",
                    "cancelledAtMs" to at.toString()
                ) - IN_FLIGHT - IN_FLIGHT_AT - OWNER_RESUME_AT
            )
        )
        onEvent("owner cancelled task=${task.taskId}")
        return SagePersonalResolution.Reply(
            "Cancelled \"${title(task)}\" and saved that, so I won't pick it up again. " +
                "This does not undo an action that already happened or stop an external action already running."
        )
    }

    private fun settled(intent: Intent, task: TaskCheckpoint): SagePersonalResolution.Reply =
        SagePersonalResolution.Reply(
            "\"${title(task)}\" is ${task.state.name.lowercase(Locale.ROOT)} now, so there's nothing " +
                "to ${intent.verb}. I haven't changed anything."
        )

    private fun recoverable(): List<TaskCheckpoint> = store.active().sortedByDescending { it.updatedAtMs }

    private fun matchCandidate(candidates: List<TaskCheckpoint>, command: String): TaskCheckpoint? {
        val said = command.trim()
        if (said.isEmpty()) return null
        said.toIntOrNull()?.let { number ->
            val index = number - 1
            return candidates.getOrNull(index)
        }
        val lowered = said.lowercase(Locale.ROOT)
        return candidates.singleOrNull { candidate ->
            val title = title(candidate).lowercase(Locale.ROOT)
            title.isNotEmpty() && normalize(title) == lowered
        }
    }

    private fun progressSuffix(task: TaskCheckpoint): String =
        if (task.nextStep.isBlank()) "" else ": ${oneLine(task.nextStep)}"

    private fun title(task: TaskCheckpoint): String = task.title.ifBlank { "Untitled saved task" }

    private fun oneLine(value: String): String = value.replace(Regex("\\s+"), " ").trim()

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT).trim()
        .replace(Regex("[.!?,;]+"), " ")
        // Apostrophes are dropped rather than spaced so "what's" and "whats" are one command.
        .replace(Regex("['\u2019]"), "")
        .replace(Regex("\\s+"), " ").trim()
        .removePrefix("sage ").removePrefix("please ").removeSuffix(" please").trim()

    private companion object {
        const val IN_FLIGHT = "followThroughInFlight"
        const val IN_FLIGHT_AT = "followThroughInFlightAtMs"
        const val OWNER_RESUME_AT = "ownerResumeRequestedAtMs"

        val LIST_COMMANDS = setOf(
            "what is unfinished", "what is still unfinished", "what is left unfinished",
            "whats unfinished", "what did we leave", "what have we left", "what did we leave unfinished"
        )
        val RESUME_COMMANDS = setOf(
            "continue that task", "continue the task", "continue that work",
            "resume that task", "resume the task", "resume that work", "keep going"
        )
        val CANCEL_COMMANDS = setOf(
            "cancel that task", "cancel the task", "cancel that work", "stop that task"
        )
        val COMMANDS = LIST_COMMANDS + RESUME_COMMANDS + CANCEL_COMMANDS
    }
}
