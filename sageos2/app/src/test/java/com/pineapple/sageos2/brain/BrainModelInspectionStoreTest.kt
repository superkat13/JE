package com.pineapple.sageos2.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class BrainModelInspectionStoreTest {
    @Test fun ownerInspectionPersistsNonConversationModelIdentity() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("sage_brain_model_v2", 0).edit().clear().commit()
        val store = BrainModelStore(context)
        val file = store.modelFile()
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte(), 1, 2, 3, 4))
        try {
            assertNull(store.inspectionMetadata())
            val result = BrainIdentityInspector.Result(
                sizeBytes = file.length(),
                sha256 = "deadbeef",
                ggufVersion = 3,
                tensorCount = 1,
                parameterCount = 123456,
                tensorTypes = mapOf("Q4_K" to 1),
                headerBytes = 64,
                tensorDataOffset = 64,
                metadata = mapOf(
                    "general.architecture" to "\"qwen2\"",
                    "general.name" to "\"Sage Brain\"",
                    "general.file_type" to "15",
                    "general.quantization_version" to "2"
                )
            )
            store.saveInspection(result, nowMs = 99L)
            val saved = requireNotNull(store.inspectionMetadata())
            assertEquals("deadbeef", saved.sha256)
            assertEquals(file.length(), saved.sizeBytes)
            assertEquals("\"qwen2\"", saved.architecture)
            assertEquals("\"Sage Brain\"", saved.embeddedName)
            assertEquals("15", saved.fileType)
            assertEquals("2", saved.quantizationVersion)
            assertEquals(123456L, saved.parameterCount)
            assertEquals(99L, saved.inspectedAtMs)
            assertTrue(store.isProvisioned())
        } finally {
            file.delete()
            context.getSharedPreferences("sage_brain_model_v2", 0).edit().clear().commit()
        }
    }
}
