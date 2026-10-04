package com.pineapple.sageos2.continuity

class TaskRecoveryManager(private val store: TaskContinuityStore) {
    fun supersedeOlderRuntimeTasks(
        keepTaskId: String,
        nowMs: Long = System.currentTimeMillis()
    ): List<TaskCheckpoint> {
        val superseded = mutableListOf<TaskCheckpoint>()
        store.active().forEach { task ->
            if (task.taskId == keepTaskId) return@forEach
            if (task.metadata["kind"] != RUNTIME_TURN_KIND) return@forEach
            if (task.state !in setOf(TaskState.ACTIVE, TaskState.WAITING)) return@forEach
            val updated = task.copy(
                state = TaskState.COMPLETED,
                summary = "Superseded by a newer owner turn. ${task.summary}".trim(),
                nextStep = "",
                updatedAtMs = nowMs,
                metadata = task.metadata + mapOf(
                    "superseded" to "true",
                    "supersededAtMs" to nowMs.toString(),
                    "supersededBy" to keepTaskId
                )
            )
            store.upsert(updated)
            superseded += updated
        }
        return superseded
    }

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

    fun autoResumeCandidate(): TaskCheckpoint? = store.active()
        .asSequence()
        .filter { it.state == TaskState.WAITING }
        .filter { it.metadata["kind"] == RUNTIME_TURN_KIND }
        .filter { it.metadata["recovered"] == "true" }
        .filter { it.metadata["autoResumeAttempted"] != "true" }
        .filter { (it.metadata["recoveryDepth"]?.toIntOrNull() ?: 0) < MAX_AUTO_RESUME_DEPTH }
        .maxByOrNull { it.updatedAtMs }

    fun markAutoResumeAttempted(
        taskId: String,
        nowMs: Long = System.currentTimeMillis()
    ): TaskCheckpoint? {
        val task = store.get(taskId) ?: return null
        val depth = (task.metadata["recoveryDepth"]?.toIntOrNull() ?: 0) + 1
        val updated = task.copy(
            updatedAtMs = nowMs,
            metadata = task.metadata + mapOf(
                "autoResumeAttempted" to "true",
                "autoResumeAttemptedAtMs" to nowMs.toString(),
                "recoveryDepth" to depth.toString()
            )
        )
        store.upsert(updated)
        return updated
    }

    companion object {
        const val RUNTIME_TURN_KIND = "runtime_turn"
        const val MAX_AUTO_RESUME_DEPTH = 3
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
