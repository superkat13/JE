package com.pineapple.sageos2.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest

class BrainIdentityInspectorTest {
    @Test fun readsIdentityAndHashesTheSameFile() {
        val bytes = fixture()
        val file = Files.createTempFile("sage-brain-identity", ".gguf").toFile()
        try {
            file.writeBytes(bytes)
            val result = BrainIdentityInspector.inspect(file)
            assertEquals(3L, result.ggufVersion)
            assertEquals("\"test-architecture\"", result.metadata["general.architecture"])
            assertEquals("\"Owner's Brain\"", result.metadata["general.name"])
            assertEquals("\"<start>user</start>\"", result.metadata["tokenizer.chat_template"])
            assertEquals("32768", result.metadata["test-architecture.context_length"])
            assertEquals(512L, result.parameterCount)
            assertEquals(1, result.tensorTypes["Q4_K"])
            assertEquals(bytes.size.toLong(), result.sizeBytes)
            assertEquals(sha(bytes), result.sha256)
            assertTrue(result.report().contains("SHA-256: ${sha(bytes)}"))
        } finally {
            file.delete()
        }
    }

    @Test fun rejectsOversizedMetadataBeforeAllocatingIt() {
        val body = ByteArrayOutputStream().apply {
            write("GGUF".toByteArray())
            u32(3)
            u64(0)
            u64(1)
            u64(16L * 1024L * 1024L + 1L)
        }.toByteArray()
        val file = Files.createTempFile("sage-brain-oversize", ".gguf").toFile()
        try {
            file.writeBytes(body)
            val error = runCatching { BrainIdentityInspector.inspect(file) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertTrue(error?.message.orEmpty().contains("string exceeds safe bound"))
        } finally {
            file.delete()
        }
    }

    @Test fun parsesPinnedLlamaCppVocabularyFixture() {
        val file = File("../third_party/llama.cpp/models/ggml-vocab-qwen2.gguf")
        assertTrue(file.isFile)
        val result = BrainIdentityInspector.inspect(file)
        assertEquals(3L, result.ggufVersion)
        assertEquals("\"qwen2\"", result.metadata["general.architecture"])
        assertEquals(0L, result.tensorCount)
        assertEquals(file.length(), result.sizeBytes)
    }

    private fun fixture(): ByteArray {
        val output = ByteArrayOutputStream()
        output.write("GGUF".toByteArray())
        output.u32(3)
        output.u64(1) // tensor
        output.u64(5) // metadata
        output.entryString("general.architecture", "test-architecture")
        output.entryString("general.name", "Owner's Brain")
        output.entryString("tokenizer.chat_template", "<start>user</start>")
        output.entryU32("general.file_type", 15)
        output.entryU32("test-architecture.context_length", 32768)
        output.string("token_embd.weight")
        output.u32(2)
        output.u64(16)
        output.u64(32)
        output.u32(12) // Q4_K
        output.u64(0)
        while (output.size() % 32 != 0) output.write(0)
        output.write(ByteArray(32) { it.toByte() })
        return output.toByteArray()
    }

    private fun ByteArrayOutputStream.entryString(key: String, value: String) {
        string(key)
        u32(8)
        string(value)
    }

    private fun ByteArrayOutputStream.entryU32(key: String, value: Int) {
        string(key)
        u32(4)
        u32(value)
    }

    private fun ByteArrayOutputStream.string(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        u64(bytes.size.toLong())
        write(bytes)
    }

    private fun ByteArrayOutputStream.u32(value: Int) {
        write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
    }

    private fun ByteArrayOutputStream.u64(value: Long) {
        write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array())
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
