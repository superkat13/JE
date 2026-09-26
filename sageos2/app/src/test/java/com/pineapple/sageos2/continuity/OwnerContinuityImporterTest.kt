package com.pineapple.sageos2.continuity

import com.pineapple.sageos2.identity.SharedPreferencesSageCoreStore
import com.pineapple.sageos2.memory.SharedPreferencesTwinMemoryStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class OwnerContinuityImporterTest {
    @Test fun importSurvivesStoreRecreationAndDuplicateImportDoesNotRewriteData() {
        val context = RuntimeEnvironment.getApplication()
        val core = SharedPreferencesSageCoreStore(context)
        val memory = SharedPreferencesTwinMemoryStore(context)
        val original = core.replace(core.current().copy(notes = "existing owner note"))
        val raw = """{"schema":"sage-owner-continuity-v1","source":"owner-reviewed test fixture",
            "core":{"notes":"recovered note"},
            "memories":[{"subject":"PROJECT","key":"test project","value":"in progress"}]}"""
        val first = OwnerContinuityImporter(context, core, memory).import(raw, 10L)
        assertFalse(first.alreadyImported)
        assertTrue(core.current().notes.contains("existing owner note"))
        assertEquals(original, core.revision(original.revision))
        val savedCore = core.current()
        val savedMemory = memory.snapshot()
        val reopenedCore = SharedPreferencesSageCoreStore(context)
        val reopenedMemory = SharedPreferencesTwinMemoryStore(context)
        val second = OwnerContinuityImporter(context, reopenedCore, reopenedMemory).import(raw, 20L)
        assertTrue(second.alreadyImported)
        assertEquals(savedCore, reopenedCore.current())
        assertEquals(savedMemory, reopenedMemory.snapshot())
    }

    @Test fun invalidMemoryCannotPartiallyOverwriteCore() {
        val context = RuntimeEnvironment.getApplication()
        val core = SharedPreferencesSageCoreStore(context)
        val memory = SharedPreferencesTwinMemoryStore(context)
        val original = core.replace(core.current().copy(notes = "keep me"))
        val raw = """{"schema":"sage-owner-continuity-v1","source":"invalid fixture",
            "core":{"notes":"must not apply"},
            "memories":[{"key":"test","value":"test","confidence":"NaN"}]}"""
        assertThrows(IllegalArgumentException::class.java) {
            OwnerContinuityImporter(context, core, memory).import(raw)
        }
        assertEquals(original, core.current())
        assertTrue(memory.snapshot().records.isEmpty())
    }
}
