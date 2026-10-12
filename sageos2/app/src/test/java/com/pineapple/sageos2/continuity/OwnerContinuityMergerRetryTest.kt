package com.pineapple.sageos2.continuity

import com.pineapple.sageos2.identity.EmptySageCoreProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the block-level deduplication in OwnerContinuityMerger prevents a retried,
 * partially-applied owner continuity import from appending a second copy of the same note.
 *
 * This is a pure-JVM unit test: no Android/SharedPreferences state is created or mutated.
 */
class OwnerContinuityMergerRetryTest {
    @Test fun mergeNotesTwiceProducesExactlyOneCopy() {
        val base = EmptySageCoreProvider.current().copy(
            notes = "existing owner's earlier note"
        )
        val incoming = OwnerContinuityPackage(
            source = "owner-approved fixture",
            notes = "recovered owner note"
        )

        // First application
        val afterFirst = OwnerContinuityMerger.merge(base, incoming)
        assertTrue(afterFirst.notes.contains("existing owner's earlier note"))
        assertTrue(afterFirst.notes.contains("recovered owner note"))
        assertEquals(
            "first merge should produce exactly one copy of the incoming note",
            1,
            Regex("recovered owner note").findAll(afterFirst.notes).count()
        )
        val expectedAfterFirst = listOf(
            "existing owner's earlier note",
            "recovered owner note"
        ).joinToString("\n\n")
        assertEquals(expectedAfterFirst, afterFirst.notes)

        // Second application (retry after partial completion) — must be idempotent
        val afterSecond = OwnerContinuityMerger.merge(afterFirst, incoming)
        assertEquals(
            "retry must not append a second copy of recovered note",
            1,
            Regex("recovered owner note").findAll(afterSecond.notes).count()
        )
        // Semantic equivalence and no spurious reordering
        assertEquals(afterFirst.notes, afterSecond.notes)
        // The merged snapshot itself must be unchanged by the second merge
        assertEquals(afterFirst, afterSecond)
    }

    @Test fun blankIncomingDoesNotAlterNotes() {
        val base = EmptySageCoreProvider.current().copy(notes = "stable note")
        val incoming = OwnerContinuityPackage(source = "test", notes = "   ")
        val merged = OwnerContinuityMerger.merge(base, incoming)
        assertEquals(base.notes, merged.notes)
        assertEquals(base, merged)
    }

    @Test fun emptyBaseAdoptsIncomingNotes() {
        val base = EmptySageCoreProvider.current().copy(notes = "")
        val incoming = OwnerContinuityPackage(source = "test", notes = "new note")
        val merged = OwnerContinuityMerger.merge(base, incoming)
        assertEquals("new note", merged.notes)
    }
}
