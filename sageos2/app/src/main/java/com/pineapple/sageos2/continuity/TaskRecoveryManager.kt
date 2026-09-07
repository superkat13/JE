package com.pineapple.sageos2.continuity

class TaskRecoveryManager(private val store: TaskContinuityStore) {
    fun recoverInterruptedRuntimeTasks(nowMs: Long = System.currentTimeMillis()): List<TaskCheckpoint> {
        val recovered = mutableListOf<TaskCheckpoint>()
        store.active().forEach { task ->
            if (task.state != TaskState.ACTIVE || task.metadata["kind"] != RUNTIME_TURN_KIND) return@forEach
            val updated = task.copy(
                state = TaskState.WAITING,
                summary = "Interrupted runtime work recovered. ${task.summary}".trim(),
                nextStep = "Continue this owner turn from its stored prompt and latest safe checkpoint. Do not automatically replay the previous side effect.",
                updatedAtMs = nowMs,
                metadata = task.metadata + mapOf(
                    "recovered" to "true",
                    "recoveredAtMs" to nowMs.toString()
                )
            )
            store.upsert(updated)
            recovered += updated
        }
        return recovered
    }

    companion object {
        const val RUNTIME_TURN_KIND = "runtime_turn"
    }
}

object TaskContinuityContextRenderer {
    fun render(tasks: List<TaskCheckpoint>): String = buildString {
        appendLine("# ACTIVE / RECOVERABLE TASKS")
        if (tasks.isEmpty()) {
            append("(none)")
            return@buildString
        }
        tasks.sortedByDescending { it.updatedAtMs }.take(12).forEach { task ->
            appendLine("- id=${task.taskId}")
            appendLine("  title=${oneLine(task.title)}")
            appendLine("  state=${task.state}")
            appendLine("  summary=${oneLine(task.summary)}")
            if (task.nextStep.isNotBlank()) appendLine("  next=${oneLine(task.nextStep)}")
            task.metadata["ownerPrompt"]?.takeIf { it.isNotBlank() }?.let {
                appendLine("  ownerPrompt=${oneLine(it).take(2_000)}")
            }
        }
        append("Treat WAITING recovered tasks as continuity context. Resume them only when the owner's current request indicates continuation; never replay a prior side effect merely because it appears here.")
    }.trim()

    private fun oneLine(value: String): String = value.replace(Regex("\\s+"), " ").trim()
}
