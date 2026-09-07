package com.pineapple.sageos2.brain

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

data class BrainModelMetadata(
    val sourceName: String,
    val sizeBytes: Long,
    val sha256: String,
    val importedAtMs: Long
)

data class BrainModelImportResult(
    val metadata: BrainModelMetadata,
    val destination: File
)

class BrainModelStore(private val context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun modelFile(): File = File(File(appContext.filesDir, "brain"), MODEL_NAME)

    fun metadata(): BrainModelMetadata? {
        val name = prefs.getString(KEY_NAME, null) ?: return null
        val size = prefs.getLong(KEY_SIZE, -1L).takeIf { it >= 0L } ?: return null
        val sha = prefs.getString(KEY_SHA, null) ?: return null
        val imported = prefs.getLong(KEY_IMPORTED, 0L)
        return BrainModelMetadata(name, size, sha, imported)
    }

    fun isProvisioned(): Boolean = modelFile().isFile && runCatching { hasGgufMagic(modelFile()) }.getOrDefault(false)

    fun import(
        uri: Uri,
        onProgress: (copiedBytes: Long, totalBytes: Long?) -> Unit = { _, _ -> }
    ): BrainModelImportResult {
        val sourceName = displayName(uri).ifBlank { "selected-model.gguf" }
        require(sourceName.lowercase().endsWith(".gguf")) { "Select a .gguf model file" }
        val total = sourceLength(uri)
        val destination = modelFile()
        destination.parentFile?.mkdirs()
        val temp = File(destination.parentFile, "$MODEL_NAME.importing")
        temp.delete()

        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        try {
            appContext.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Android could not open the selected model" }
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        copied += read
                        onProgress(copied, total)
                    }
                    output.fd.sync()
                }
            }
            require(copied > 0L) { "The selected model is empty" }
            require(hasGgufMagic(temp)) { "The selected file does not contain a GGUF model header" }
            moveIntoPlace(temp, destination)
            val metadata = BrainModelMetadata(
                sourceName = sourceName,
                sizeBytes = copied,
                sha256 = digest.digest().joinToString("") { "%02x".format(it) },
                importedAtMs = System.currentTimeMillis()
            )
            prefs.edit()
                .putString(KEY_NAME, metadata.sourceName)
                .putLong(KEY_SIZE, metadata.sizeBytes)
                .putString(KEY_SHA, metadata.sha256)
                .putLong(KEY_IMPORTED, metadata.importedAtMs)
                .apply()
            return BrainModelImportResult(metadata, destination)
        } catch (t: Throwable) {
            temp.delete()
            throw t
        }
    }

    private fun displayName(uri: Uri): String {
        return runCatching {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
            }.orEmpty()
        }.getOrDefault("")
    }

    private fun sourceLength(uri: Uri): Long? = runCatching {
        appContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            descriptor.length.takeIf { it >= 0L }
        }
    }.getOrNull()

    private fun hasGgufMagic(file: File): Boolean {
        FileInputStream(file).use { input ->
            val magic = ByteArray(4)
            if (input.read(magic) != magic.size) return false
            return magic.contentEquals(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte()))
        }
    }

    private fun moveIntoPlace(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        const val MODEL_NAME = "sage-brain.gguf"
        private const val PREFS = "sage_brain_model_v2"
        private const val KEY_NAME = "source_name"
        private const val KEY_SIZE = "size_bytes"
        private const val KEY_SHA = "sha256"
        private const val KEY_IMPORTED = "imported_at"
    }
}
