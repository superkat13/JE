package com.pineapple.sageos2.continuity

enum class TaskState { ACTIVE, WAITING, COMPLETED, FAILED, CANCELLED }

data class TaskCheckpoint(
    val taskId: String,
    val title: String,
    val state: TaskState,
    val summary: String,
    val nextStep: String = "",
    val updatedAtMs: Long,
    val metadata: Map<String, String> = emptyMap()
)

interface TaskContinuityStore {
    fun upsert(checkpoint: TaskCheckpoint)
    fun get(taskId: String): TaskCheckpoint?
    fun active(): List<TaskCheckpoint>
    fun recent(limit: Int = 50): List<TaskCheckpoint>
    fun remove(taskId: String)
}
