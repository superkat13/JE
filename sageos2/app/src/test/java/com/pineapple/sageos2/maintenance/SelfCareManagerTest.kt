package com.pineapple.sageos2.maintenance

import com.pineapple.sageos2.continuity.TaskCheckpoint
import com.pineapple.sageos2.continuity.TaskContinuityStore
import com.pineapple.sageos2.continuity.TaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfCareManagerTest {
    @Test fun unresolvedHealthCreatesDurableFindingsWithoutInventingCore() {
        val store = FakeStore()
        val findings = SelfCareManager(store).reconcile(
            SelfCareSnapshot(
                brainReady = true,
                brainDetail = "ready",
                wakeReady = false,
                wakeDetail = "remote wake process disconnected",
                coreRevision = 0,
                legacyCorePresent = false
            ),
            nowMs = 100L
        )

        assertEquals(setOf("core_empty", "wake_not_ready"), findings.map { it.code }.toSet())
        assertTrue(store.get("selfcare:core_empty")!!.nextStep.contains("Restore owner continuity"))
        assertTrue(store.get("selfcare:core_empty")!!.nextStep.contains("never fabricate"))
        assertEquals(TaskState.WAITING, store.get("selfcare:wake_not_ready")!!.state)
    }

    @Test fun clearedConditionCompletesExistingSelfCareTask() {
        val store = FakeStore()
        val manager = SelfCareManager(store)
        manager.reconcile(
            SelfCareSnapshot(true, "ready", false, "wake failed", 2, true),
            nowMs = 100L
        )
        manager.reconcile(
            SelfCareSnapshot(true, "ready", true, "wake ready", 2, true),
            nowMs = 200L
        )

        val task = store.get("selfcare:wake_not_ready")!!
        assertEquals(TaskState.COMPLETED, task.state)
        assertEquals("true", task.metadata["cleared"])
    }

    private class FakeStore : TaskContinuityStore {
        private val values = linkedMapOf<String, TaskCheckpoint>()
        override fun upsert(checkpoint: TaskCheckpoint) { values[checkpoint.taskId] = checkpoint }
        override fun get(taskId: String): TaskCheckpoint? = values[taskId]
        override fun active(): List<TaskCheckpoint> = values.values.filter { it.state == TaskState.ACTIVE || it.state == TaskState.WAITING }
        override fun recent(limit: Int): List<TaskCheckpoint> = values.values.sortedByDescending { it.updatedAtMs }.take(limit)
        override fun remove(taskId: String) { values.remove(taskId) }
    }
}
