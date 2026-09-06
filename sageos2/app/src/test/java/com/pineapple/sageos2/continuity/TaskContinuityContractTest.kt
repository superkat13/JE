package com.pineapple.sageos2.continuity

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskContinuityContractTest {
    @Test fun checkpointCarriesExplicitResumeStep() {
        val task = TaskCheckpoint("1", "Build Sage", TaskState.ACTIVE, "core wired", "attach Brain", 42L)
        assertEquals("attach Brain", task.nextStep)
    }
}
