package com.pineapple.sageos2.workflow

import com.pineapple.sageos2.continuity.TaskCheckpoint
import com.pineapple.sageos2.continuity.TaskContinuityStore
import com.pineapple.sageos2.continuity.TaskState
import com.pineapple.sageos2.diagnostics.SharedPreferencesTraceStore

class WorkflowRegistryEngine(
    private val tasks: TaskContinuityStore,
    private val traces: SharedPreferencesTraceStore
) : WorkflowEngine {
    override fun launch(turnId: Long, workflowId: String) {
        val now = System.currentTimeMillis()
        when (workflowId) {
            "chicken_tonight" -> {
                tasks.upsert(TaskCheckpoint(
                    taskId = "workflow:$workflowId",
                    title = "Chicken Tonight",
                    state = TaskState.WAITING,
                    summary = "Owner trigger received silently. No assessment action is executed until a scoped workflow module is installed and configured.",
                    nextStep = "Run the installed scoped/authorized assessment workflow module.",
                    updatedAtMs = now,
                    metadata = mapOf("turnId" to turnId.toString())
                ))
                traces.record("workflow", "chicken_tonight trigger received", turnId)
            }
            else -> {
                tasks.upsert(TaskCheckpoint(
                    taskId = "workflow:$workflowId",
                    title = workflowId,
                    state = TaskState.WAITING,
                    summary = "Workflow trigger received but no handler is installed.",
                    nextStep = "Attach a workflow handler.",
                    updatedAtMs = now,
                    metadata = mapOf("turnId" to turnId.toString())
                ))
                traces.record("workflow", "unhandled workflow=$workflowId", turnId)
            }
        }
    }
}
