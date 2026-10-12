package com.pineapple.sageos2.continuity

import android.content.Context
import com.pineapple.sageos2.identity.SharedPreferencesSageCoreStore
import com.pineapple.sageos2.memory.SharedPreferencesTwinMemoryStore
import com.pineapple.sageos2.memory.TwinMemorySource
import com.pineapple.sageos2.memory.TwinMemorySubject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Synthetic data only. These are release-blocking acceptance tests for PR #75.
 * A previewed package must not be applied against a different Core or memory state.
 * Keep deliberately red until source code enforces the reviewed baseline.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
class OwnerContinuityApprovalGateTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun clearTestState() {
        listOf("sage_core_v2", "sage_twin_memory_v2", "sageos2_owner_continuity_imports").forEach {
            assertTrue(context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit())
        }
    }

    private fun core() = SharedPreferencesSageCoreStore(context)
    private fun memory() = SharedPreferencesTwinMemoryStore(context)
    private fun importer(core: SharedPreferencesSageCoreStore, memory: SharedPreferencesTwinMemoryStore) =
        OwnerContinuityImporter(context, core, memory)

    private fun identityPackage() = """{"schema":"sage-owner-continuity-v1","source":"fixture",
        "core":{"twinIdentity":"imported identity","activeProjects":{"project":"imported project"}}}"""

    private fun memoryPackage() = """{"schema":"sage-owner-continuity-v1","source":"memory fixture",
        "memories":[{"subject":"PROJECT","key":"current work","value":"imported value"}]}"""

    @Test fun interveningCoreChangeMustRejectWithoutAnyWrite() {
        val core = core()
        val memory = memory()
        core.replace(core.current().copy(twinIdentity = "original owner identity"))
        val importer = importer(core, memory)
        val raw = identityPackage()
        val preview = importer.preview(raw)

        val ownerUpdate = core.replace(core.current().copy(
            twinIdentity = "newer owner identity",
            sharedContinuity = core.current().sharedContinuity.copy(
                activeProjects = mapOf("project" to "newer owner project")
            )
        ))

        assertThrows(IllegalStateException::class.java) {
            importer.importBound(raw, preview.digest, preview.before, preview.reviewedMemoryRevision)
        }
        assertEquals("stale confirmation must not change Core", ownerUpdate, core.current())
        assertEquals("stale confirmation must not change memory", 0L, memory.snapshot().revision)
        assertFalse("stale confirmation must not write pending marker", importer.hasPending(preview.digest))
    }

    @Test fun interveningMemoryChangeMustRejectBeforeImporting() {
        val core = core()
        val memory = memory()
        memory.remember(
            TwinMemorySubject.PROJECT, "current work", "earlier owner value",
            TwinMemorySource.EXPLICIT_OWNER, 1.0, 100L
        )
        val importer = importer(core, memory)
        val raw = memoryPackage()
        val preview = importer.preview(raw)
        val ownerUpdate = memory.remember(
            TwinMemorySubject.PROJECT, "current work", "newer owner value",
            TwinMemorySource.EXPLICIT_OWNER, 1.0, 200L
        )
        val beforeCore = core.current()
        val beforeMemory = memory.snapshot()
        assertTrue("preview must retain earlier memory revision", preview.reviewedMemoryRevision < beforeMemory.revision)

        assertThrows(IllegalStateException::class.java) {
            importer.importBound(raw, preview.digest, preview.before, preview.reviewedMemoryRevision)
        }
        assertEquals(beforeCore, core.current())
        assertEquals(beforeMemory, memory.snapshot())
        assertEquals("newer owner value", ownerUpdate.value)
        assertFalse(importer.hasPending(preview.digest))
    }

    @Test fun ordinaryPreviewWithoutConfirmationCannotMutate() {
        val core = core()
        val memory = memory()
        val importer = importer(core, memory)
        val beforeCore = core.current()
        val beforeMemory = memory.snapshot()
        val preview = importer.preview(identityPackage())
        assertEquals(beforeCore, core.current())
        assertEquals(beforeMemory, memory.snapshot())
        assertFalse(importer.hasPending(preview.digest))
    }

    @Test fun matchingApprovedPackageCanImportOnceWithoutDuplicating() {
        val core = core()
        val memory = memory()
        val importer = importer(core, memory)
        val raw = identityPackage()
        val preview = importer.preview(raw)
        val imported = importer.importBound(raw, preview.digest, preview.before, preview.reviewedMemoryRevision)
        assertFalse(imported.alreadyImported)
        assertEquals("imported identity", core.current().twinIdentity)
        val once = core.current()
        val retry = importer.importBound(raw, preview.digest)
        assertTrue(retry.alreadyImported)
        assertEquals(once, core.current())
        assertFalse(importer.hasPending(preview.digest))
    }

    @Test fun oversizedIdentityMustNotBeSilentlyClipped() {
        val raw = """{"schema":"sage-owner-continuity-v1","source":"long fixture",
            "core":{"twinIdentity":"${"X".repeat(2001)}"}}"""
        assertThrows(IllegalArgumentException::class.java) {
            OwnerContinuityCodec.decode(raw)
        }
    }

    @Test fun oversizedMemoryValueMustNotBeSilentlyClipped() {
        val raw = """{"schema":"sage-owner-continuity-v1","source":"long memory fixture",
            "memories":[{"subject":"PROJECT","key":"test","value":"${"Y".repeat(2001)}"}]}"""
        assertThrows(IllegalArgumentException::class.java) {
            OwnerContinuityCodec.decode(raw)
        }
    }
}
