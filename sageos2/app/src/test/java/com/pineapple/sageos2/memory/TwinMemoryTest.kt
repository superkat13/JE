package com.pineapple.sageos2.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TwinMemoryTest {
    @Test fun memoryKeepsSubjectSourceAndConfidence() {
        val record = TwinMemoryRecord(
            id = "1",
            subject = TwinMemorySubject.OWNER,
            key = "instruction style",
            value = "prefers complete steps",
            source = TwinMemorySource.EXPLICIT_OWNER,
            confidence = 1.0,
            createdAtEpochMs = 1,
            updatedAtEpochMs = 1
        )
        val snapshot = TwinMemorySnapshot(1, listOf(record))
        assertEquals(TwinMemorySource.EXPLICIT_OWNER, snapshot.activeFor(TwinMemorySubject.OWNER).single().source)
        assertEquals(1.0, snapshot.records.single().confidence, 0.0)
    }

    @Test fun inactiveMemoryIsNotReturnedAsActiveTwinContext() {
        val record = TwinMemoryRecord("1", TwinMemorySubject.SHARED, "project", "old", TwinMemorySource.SHARED_DECISION, 1.0, 1, 2, active = false)
        assertTrue(TwinMemorySnapshot(2, listOf(record)).activeFor(TwinMemorySubject.SHARED).isEmpty())
    }
}
