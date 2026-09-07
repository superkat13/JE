package com.pineapple.sageos2.continuity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskRecoveryManagerTest {
    @Test
    fun activeRuntimeTurnBecomesWaitingAndExplicitlyForbidsReplay() {
        val store = MemoryTaskStore()
        store.upsert(
            TaskCheckpoint(
                taskId = "runtime:turn:7",
                title = "Install the update",
                state = TaskState.ACTIVE,
                summary = "Executing structured capability root.install_package.",
                nextStep = "Wait for result.",
                updatedAtMs = 100,
                metadata = mapOf(
                    "kind" to TaskRecoveryManager.RUNTIME_TURN_KIND,
                    "ownerPrompt" to "install the update"
                )
            )
        )

        val recovered = TaskRecoveryManager(store).recoverInterruptedRuntimeTasks(nowMs = 500)
        assertEquals(1, recovered.size)
        val task = recovered.single()
        assertEquals(TaskState.WAITING, task.state)
        assertTrue(task.nextStep.contains("Do not automatically replay"))
        assertEquals("true", task.metadata["recovered"])
        assertEquals("500", task.metadata["recoveredAtMs"])
    }

    @Test
    fun waitingWorkflowAndCompletedTurnAreNotRewritten() {
        val store = MemoryTaskStore()
        store.upsert(TaskCheckpoint("workflow:one", "Workflow", TaskState.WAITING, "waiting", "next", 10))
        store.upsert(TaskCheckpoint("runtime:turn:2", "Done", TaskState.COMPLETED, "done", "", 20, mapOf("kind" to TaskRecoveryManager.RUNTIME_TURN_KIND)))
        assertTrue(TaskRecoveryManager(store).recoverInterruptedRuntimeTasks(100).isEmpty())
        assertEquals(TaskState.WAITING, store.get("workflow:one")!!.state)
        assertEquals(TaskState.COMPLETED, store.get("runtime:turn:2")!!.state)
    }

    @Test
    fun contextMakesRecoveredWorkVisibleButNotAnExecutionTrigger() {
        val text = TaskContinuityContextRenderer.render(
            listOf(
                TaskCheckpoint(
                    "runtime:turn:9",
                    "Continue research",
                    TaskState.WAITING,
                    "Interrupted runtime work recovered.",
                    "Continue safely.",
                    20,
                    mapOf("ownerPrompt" to "continue what we were doing")
                )
            )
        )
        assertTrue(text.contains("ownerPrompt=continue what we were doing"))
        assertTrue(text.contains("never replay a prior side effect"))
        assertFalse(text.contains("<SAGE_TOOL>"))
    }

    private class MemoryTaskStore : TaskContinuityStore {
        private val tasks = linkedMapOf<String, TaskCheckpoint>()
        override fun upsert(checkpoint: TaskCheckpoint) { tasks[checkpoint.taskId] = checkpoint }
        override fun get(taskId: String) = tasks[taskId]
        override fun active() = tasks.values.filter { it.state == TaskState.ACTIVE || it.state == TaskState.WAITING }.sortedByDescending { it.updatedAtMs }
        override fun recent(limit: Int) = tasks.values.sortedByDescending { it.updatedAtMs }.take(limit)
        override fun remove(taskId: String) { tasks.remove(taskId) }
    }
}
