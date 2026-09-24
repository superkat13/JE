package com.pineapple.sageos2.continuity

import com.pineapple.sageos2.identity.EmptySageCoreProvider
import com.pineapple.sageos2.identity.SharedContinuity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerContinuityMergerTest {
    @Test fun mergeAddsReviewedContinuityWithoutDeletingExistingState() {
        val current = EmptySageCoreProvider.current().copy(
            revision = 4,
            principles = listOf("preserve this"),
            notes = "existing note",
            sharedContinuity = SharedContinuity(
                activeProjects = mapOf("Existing" to "keep"),
                durableDecisions = listOf("old decision")
            )
        )
        val incoming = OwnerContinuityPackage(
            source = "owner reviewed recovery",
            twinIdentity = "Sage continuity restored",
            principles = listOf("preserve this", "repair carefully"),
            notes = "recovered note",
            activeProjects = mapOf("Recovered" to "active"),
            durableDecisions = listOf("new decision")
        )

        val merged = OwnerContinuityMerger.merge(current, incoming)

        assertEquals(4L, merged.revision)
        assertEquals("Sage continuity restored", merged.twinIdentity)
        assertEquals("keep", merged.sharedContinuity.activeProjects["Existing"])
        assertEquals("active", merged.sharedContinuity.activeProjects["Recovered"])
        assertEquals(listOf("preserve this", "repair carefully"), merged.principles)
        assertEquals(listOf("old decision", "new decision"), merged.sharedContinuity.durableDecisions)
        assertTrue(merged.notes.contains("existing note"))
        assertTrue(merged.notes.contains("recovered note"))
    }

    @Test fun blankOptionalFieldsDoNotReplaceExistingIdentity() {
        val current = EmptySageCoreProvider.current().copy(revision = 2)
        val incoming = OwnerContinuityPackage(source = "owner reviewed recovery")

        val merged = OwnerContinuityMerger.merge(current, incoming)

        assertEquals(current.twinIdentity, merged.twinIdentity)
        assertEquals(current.sharedContinuity, merged.sharedContinuity)
        assertEquals(current.notes, merged.notes)
    }
}
