package com.pineapple.sageos2.workflow

import com.pineapple.sageos2.continuity.TaskCheckpoint
import com.pineapple.sageos2.continuity.TaskContinuityStore
import com.pineapple.sageos2.continuity.TaskState
import com.pineapple.sageos2.diagnostics.SharedPreferencesTraceStore

class WorkflowRegistryEngine(
    private val tasks: TaskContinuityStore,
    private val traces: SharedPreferencesTraceStore,
    private val chickenTonightScope: ChickenTonightScopeProvider = EmptyChickenTonightScopeProvider
) : WorkflowEngine {
    override fun launch(turnId: Long, workflowId: String) {
        val now = System.currentTimeMillis()
        when (workflowId) {
            "chicken_tonight" -> launchChickenTonight(turnId, now)
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

    private fun launchChickenTonight(turnId: Long, now: Long) {
        val scope = chickenTonightScope.current()
        val task = when {
            scope == null -> TaskCheckpoint(
                taskId = "workflow:chicken_tonight",
                title = "Chicken Tonight",
                state = TaskState.WAITING,
                summary = "Owner trigger received silently, but no assessment scope is configured.",
                nextStep = "Configure an authorization reference, target summary, expiry, and approved read-only evidence modules in the owner cockpit.",
                updatedAtMs = now,
                metadata = mapOf("turnId" to turnId.toString(), "scopeStatus" to "missing")
            )
            !scope.isUsable(now) -> TaskCheckpoint(
                taskId = "workflow:chicken_tonight",
                title = "Chicken Tonight",
                state = TaskState.WAITING,
                summary = if (scope.isExpired(now)) {
                    "Owner trigger received silently, but the configured assessment scope has expired."
                } else {
                    "Owner trigger received silently, but the configured assessment scope is incomplete."
                },
                nextStep = "Review and renew the stored Chicken Tonight scope before any assessment module can begin.",
                updatedAtMs = now,
                metadata = mapOf("turnId" to turnId.toString(), "scopeId" to scope.scopeId, "scopeStatus" to "invalid")
            )
            else -> TaskCheckpoint(
                taskId = "workflow:chicken_tonight",
                title = "Chicken Tonight",
                state = TaskState.ACTIVE,
                summary = "Scoped read-only assessment initialized for '${scope.targetSummary}'. Only explicitly enabled evidence modules are eligible to run.",
                nextStep = "Execute only the configured read-only evidence modules, record findings, and stop if scope expires or a requested action falls outside the stored authorization.",
                updatedAtMs = now,
                metadata = mapOf(
                    "turnId" to turnId.toString(),
                    "scopeId" to scope.scopeId,
                    "authorizationReference" to scope.authorizationReference,
                    "targetSummary" to scope.targetSummary,
                    "scopeStatus" to "active",
                    "allowedModules" to scope.allowedModules.sortedBy { it.name }.joinToString(",") { it.name }
                )
            )
        }
        tasks.upsert(task)
        traces.record(
            "workflow",
            "chicken_tonight trigger received scope=${task.metadata["scopeStatus"]} modules=${task.metadata["allowedModules"].orEmpty()}",
            turnId
        )
    }
}
