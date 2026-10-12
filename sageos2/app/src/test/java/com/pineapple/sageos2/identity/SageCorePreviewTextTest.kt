package com.pineapple.sageos2.identity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM validation of the private before/after preview text the owner reads before approving a
 * restoration. The renderer must show the real owner-authored values (never a hash or field list)
 * while the redacted [SageCoreRestoreCandidate.toString] must never leak them.
 */
class SageCorePreviewTextTest {
    private fun core(identity: String, notes: String = "", principles: List<String> = emptyList()) =
        EmptySageCoreProvider.current().copy(
            revision = 1,
            twinIdentity = identity,
            notes = notes,
            principles = principles
        )

    @Test fun showsActualOwnerAuthoredBeforeAndAfterValues() {
        val before = core("current identity", notes = "current note")
        val after = core("owner restored identity", notes = "legacy owner note", principles = listOf("truth over comfort"))
        val candidate = SageCoreRestoreCandidate(
            sourceLabel = "legacy Sage 1.33.3 Core (owner_instructions)",
            sourceRevision = 1,
            targetRevision = 2,
            sourceDigest = "abc123",
            before = before,
            after = after,
            changedFields = listOf("twinIdentity", "notes", "principles")
        )

        val text = SageCorePreviewText.render(candidate)

        assertTrue("must show the exact source", text.contains("legacy Sage 1.33.3 Core (owner_instructions)"))
        assertTrue("must name the new version", text.contains("version 2"))
        assertTrue("must show the previous identity", text.contains("current identity"))
        assertTrue("must show the restored identity", text.contains("owner restored identity"))
        assertTrue("must show the previous note", text.contains("current note"))
        assertTrue("must show the restored note", text.contains("legacy owner note"))
        assertTrue("must show restored principles", text.contains("truth over comfort"))
        assertTrue(text.contains("Before:"))
        assertTrue(text.contains("After:"))
    }

    @Test fun unchangedFieldsAreNotShownAsChanges() {
        val same = core("same identity", notes = "same note")
        val candidate = SageCoreRestoreCandidate(
            sourceLabel = "saved snapshot version 1",
            sourceRevision = 1,
            targetRevision = 2,
            sourceDigest = "abc",
            before = same,
            after = same,
            changedFields = emptyList()
        )

        val text = SageCorePreviewText.render(candidate)

        assertFalse("unchanged identity must not be duplicated", text.contains("same identity"))
        assertFalse(text.contains("Before:"))
    }

    @Test fun redactedToStringNeverLeaksPrivateIdentity() {
        val secret = "private-secret-identity-text"
        val candidate = SageCoreRestoreCandidate(
            sourceLabel = "legacy:owner_instructions",
            sourceRevision = 1,
            targetRevision = 2,
            sourceDigest = "abcdef1234567890",
            before = core("before"),
            after = core(secret),
            changedFields = listOf("twinIdentity")
        )

        val rendered = candidate.toString()
        assertFalse("toString must not contain private text", rendered.contains(secret))
        assertTrue(rendered.contains("SageCoreRestoreCandidate"))
        assertTrue(rendered.contains("abcdef123456"))
    }

    @Test fun matchesRequiresBothDigestAndTargetRevision() {
        val candidate = SageCoreRestoreCandidate(
            sourceLabel = "s", sourceRevision = 1, targetRevision = 5,
            sourceDigest = "digest", before = core("b"), after = core("a"), changedFields = emptyList()
        )
        assertTrue(candidate.matches("digest", 5))
        assertFalse(candidate.matches("digest", 6))
        assertFalse(candidate.matches("other", 5))
    }
}
