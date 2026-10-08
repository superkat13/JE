package com.pineapple.sageos2.brain

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** Reads only GGUF metadata and tensor descriptors; model tensor data is never read. */
object BrainIdentityInspector {
    private const val MAX_HEADER_BYTES = 512L * 1024L * 1024L
    private const val MAX_STRING_BYTES = 16 * 1024 * 1024
    private const val MAX_ARRAY_ITEMS = 2_000_000L
    private const val MAX_ENTRIES = 1_000_000L
    private const val MAX_PARAMETERS = 1_000_000_000_000_000L
    private const val MAX_REPORTED_METADATA_CHARS = 4_000_000

    data class Result(
        val sizeBytes: Long,
        val sha256: String,
        val ggufVersion: Long,
        val tensorCount: Long,
        val parameterCount: Long,
        val tensorTypes: Map<String, Int>,
        val headerBytes: Long,
        val tensorDataOffset: Long,
        val metadata: Map<String, String>
    ) {
        fun report(): String = buildString {
            appendLine("Sage Brain identity report (read-only)")
            appendLine("Path: files/brain/sage-brain.gguf")
            appendLine("Size bytes: $sizeBytes")
            appendLine("SHA-256: $sha256")
            appendLine("GGUF version: $ggufVersion")
            appendLine("Architecture: ${metadata["general.architecture"] ?: "UNKNOWN"}")
            appendLine("Embedded name: ${metadata["general.name"] ?: "UNKNOWN"}")
            val fileType = metadata["general.file_type"]?.toIntOrNull()
            appendLine("File type: ${fileType ?: "UNKNOWN"} (${FILE_TYPES[fileType] ?: "UNKNOWN"})")
            appendLine("Quantization version: ${metadata["general.quantization_version"] ?: "UNKNOWN"}")
            appendLine("Tensor count: $tensorCount")
            appendLine("Parameter count: $parameterCount")
            appendLine("Tensor types: ${tensorTypes.toSortedMap()}")
            appendLine("Header and descriptors bytes: $headerBytes")
            appendLine("Tensor data offset: $tensorDataOffset")
            appendLine("Metadata:")
            metadata.toSortedMap().forEach { (key, value) -> appendLine("$key = $value") }
        }.trimEnd()
    }

    fun inspect(file: File): Result {
        require(file.isFile) { "The installed Sage Brain file is missing" }
        return FileInputStream(file).use { original ->
            val size = original.channel.size()
            require(size >= 24L) { "The Sage Brain file is too small for a GGUF header" }
            val reader = Reader(BufferedInputStream(original, 1024 * 1024), size)
            require(reader.bytes(4).contentEquals("GGUF".toByteArray(Charsets.US_ASCII))) {
                "The installed Brain is not a GGUF file"
            }
            val version = reader.u32()
            require(version == 2L || version == 3L) { "Unsupported GGUF version $version" }
            val tensorCount = reader.u64()
            val metadataCount = reader.u64()
            require(tensorCount <= MAX_ENTRIES && metadataCount <= MAX_ENTRIES) { "GGUF entry count exceeds safe bound" }

            val metadata = linkedMapOf<String, String>()
            var reportedChars = 0L
            repeat(metadataCount.toInt()) {
                val key = reader.string()
                require(key.isNotEmpty() && key.length <= 4096 && !metadata.containsKey(key)) {
                    "Invalid or duplicate GGUF metadata key"
                }
                val value = reader.value(reader.u32().toInt())
                reportedChars += key.length + value.length
                require(reportedChars <= MAX_REPORTED_METADATA_CHARS) { "GGUF report metadata exceeds safe bound" }
                metadata[key] = value
            }

            var parameters = 0L
            val tensorTypes = mutableMapOf<String, Int>()
            repeat(tensorCount.toInt()) {
                reader.string() // tensor name
                val dimensions = reader.u32()
                require(dimensions in 1L..16L) { "Invalid tensor dimensions" }
                var elements = 1L
                repeat(dimensions.toInt()) {
                    val dimension = reader.u64()
                    require(dimension in 1L..MAX_PARAMETERS && elements <= MAX_PARAMETERS / dimension) {
                        "Tensor parameter count exceeds safe bound"
                    }
                    elements *= dimension
                }
                val type = reader.u32().toInt()
                reader.u64() // offset relative to tensor data
                require(parameters <= MAX_PARAMETERS - elements) { "Model parameter count exceeds safe bound" }
                parameters += elements
                val label = TENSOR_TYPES[type] ?: "UNKNOWN_$type"
                tensorTypes[label] = (tensorTypes[label] ?: 0) + 1
            }
            val alignment = metadata["general.alignment"]?.toLongOrNull() ?: 32L
            require(alignment in 1L..1_048_576L) { "Invalid GGUF alignment" }
            val dataOffset = ((reader.offset + alignment - 1L) / alignment) * alignment
            if (tensorCount > 0L) {
                require(dataOffset < size) { "GGUF tensor data offset exceeds file size" }
            }
            val header = Header(version, tensorCount, parameters, tensorTypes, reader.offset, dataOffset, metadata)
            val digest = sha256(original.channel, size)
            Result(size, digest, header.version, header.tensors, header.parameters,
                header.tensorTypes, header.headerBytes, header.dataOffset, header.metadata)
        }
    }

    private data class Header(
        val version: Long, val tensors: Long, val parameters: Long,
        val tensorTypes: Map<String, Int>, val headerBytes: Long,
        val dataOffset: Long, val metadata: Map<String, String>
    )

    private fun sha256(channel: FileChannel, expectedSize: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteBuffer.allocate(1024 * 1024)
        channel.position(0L)
        var total = 0L
        while (total < expectedSize) {
            buffer.clear()
            buffer.limit(minOf(buffer.capacity().toLong(), expectedSize - total).toInt())
            val count = channel.read(buffer)
            require(count > 0) { "The Sage Brain file changed during inspection" }
            digest.update(buffer.array(), 0, count)
            total += count
        }
        require(channel.size() == expectedSize) { "The Sage Brain file size changed during inspection" }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private class Reader(private val input: BufferedInputStream, private val fileSize: Long) {
        var offset = 0L
            private set

        fun bytes(count: Int): ByteArray {
            require(count >= 0 && offset + count <= MAX_HEADER_BYTES && offset + count <= fileSize) {
                "GGUF metadata exceeds safe bound or file size"
            }
            val data = ByteArray(count)
            var read = 0
            while (read < count) {
                val part = input.read(data, read, count - read)
                require(part > 0) { "Truncated GGUF metadata" }
                read += part
            }
            offset += count
            return data
        }

        fun u32(): Long = ByteBuffer.wrap(bytes(4)).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffff_ffffL

        fun raw64(): Long = ByteBuffer.wrap(bytes(8)).order(ByteOrder.LITTLE_ENDIAN).long

        fun u64(): Long {
            val number = raw64()
            require(number >= 0L) { "GGUF unsigned value exceeds safe bound" }
            return number
        }

        fun string(): String {
            val length = u64()
            require(length <= MAX_STRING_BYTES) { "GGUF string exceeds safe bound" }
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            return decoder.decode(ByteBuffer.wrap(bytes(length.toInt()))).toString()
        }

        fun value(type: Int): String = when (type) {
            0 -> (bytes(1)[0].toInt() and 0xff).toString()
            1 -> bytes(1)[0].toString()
            2 -> (little(bytes(2)).toInt() and 0xffff).toString()
            3 -> little(bytes(2)).toShort().toString()
            4 -> u32().toString()
            5 -> u32().toInt().toString()
            6 -> java.lang.Float.intBitsToFloat(u32().toInt()).toString()
            7 -> (bytes(1)[0].toInt() != 0).toString()
            8 -> {
                val content = string()
                if (content.length <= 65_536) quote(content)
                else {
                    val hash = MessageDigest.getInstance("SHA-256")
                        .digest(content.toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(it) }
                    "string(chars=${content.length},sha256=$hash,preview=${quote(content.take(4096))})"
                }
            }
            9 -> array()
            10 -> java.lang.Long.toUnsignedString(raw64())
            11 -> raw64().toString()
            12 -> java.lang.Double.longBitsToDouble(raw64()).toString()
            else -> throw IllegalArgumentException("Unsupported GGUF metadata type $type")
        }

        private fun array(): String {
            val type = u32().toInt()
            require(type in 0..8 || type in 10..12) { "Unsupported GGUF array type $type" }
            val count = u64()
            require(count <= MAX_ARRAY_ITEMS) { "GGUF array exceeds safe bound" }
            val digest = MessageDigest.getInstance("SHA-256")
            val preview = ArrayList<String>(8)
            repeat(count.toInt()) { index ->
                if (type == 8) {
                    val length = u64()
                    require(length <= MAX_STRING_BYTES) { "GGUF array string exceeds safe bound" }
                    val raw = bytes(length.toInt())
                    digest.update(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(length).array())
                    digest.update(raw)
                    if (index < 8) preview += quote(String(raw, Charsets.UTF_8).take(256))
                } else {
                    val raw = bytes(TYPE_WIDTHS[type] ?: error("Missing GGUF type width"))
                    digest.update(raw)
                    if (index < 8) preview += scalarPreview(type, raw)
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            return "array(type=$type,count=$count,sha256=$hash,preview=${preview.joinToString(prefix = "[", postfix = "]")})"
        }

        private fun scalarPreview(type: Int, bytes: ByteArray): String {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return when (type) {
                0 -> (buffer.get().toInt() and 0xff).toString()
                1 -> buffer.get().toString()
                2 -> (buffer.short.toInt() and 0xffff).toString()
                3 -> buffer.short.toString()
                4 -> (buffer.int.toLong() and 0xffff_ffffL).toString()
                5 -> buffer.int.toString()
                6 -> buffer.float.toString()
                7 -> (buffer.get().toInt() != 0).toString()
                10 -> java.lang.Long.toUnsignedString(buffer.long)
                11 -> buffer.long.toString()
                12 -> buffer.double.toString()
                else -> error("Unsupported GGUF array type")
            }
        }

        private fun little(bytes: ByteArray): Long = bytes.indices.fold(0L) { result, index ->
            result or ((bytes[index].toLong() and 0xffL) shl (index * 8))
        }
    }

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }

    private val TYPE_WIDTHS = mapOf(0 to 1, 1 to 1, 2 to 2, 3 to 2, 4 to 4, 5 to 4,
        6 to 4, 7 to 1, 10 to 8, 11 to 8, 12 to 8)

    private val FILE_TYPES = mapOf(
        0 to "ALL_F32", 1 to "MOSTLY_F16", 2 to "MOSTLY_Q4_0", 3 to "MOSTLY_Q4_1",
        4 to "MOSTLY_Q4_1_SOME_F16", 7 to "MOSTLY_Q8_0", 8 to "MOSTLY_Q5_0",
        9 to "MOSTLY_Q5_1", 10 to "MOSTLY_Q2_K", 11 to "MOSTLY_Q3_K_S",
        12 to "MOSTLY_Q3_K_M", 13 to "MOSTLY_Q3_K_L", 14 to "MOSTLY_Q4_K_S",
        15 to "MOSTLY_Q4_K_M", 16 to "MOSTLY_Q5_K_S", 17 to "MOSTLY_Q5_K_M",
        18 to "MOSTLY_Q6_K", 19 to "MOSTLY_IQ2_XXS", 20 to "MOSTLY_IQ2_XS",
        21 to "MOSTLY_Q2_K_S", 22 to "MOSTLY_IQ3_XS", 23 to "MOSTLY_IQ3_XXS",
        24 to "MOSTLY_IQ1_S", 25 to "MOSTLY_IQ4_NL", 26 to "MOSTLY_IQ3_S",
        27 to "MOSTLY_IQ3_M", 28 to "MOSTLY_IQ2_S", 29 to "MOSTLY_IQ2_M",
        30 to "MOSTLY_IQ4_XS", 31 to "MOSTLY_IQ1_M", 32 to "MOSTLY_BF16",
        36 to "MOSTLY_TQ1_0", 37 to "MOSTLY_TQ2_0", 38 to "MOSTLY_MXFP4_MOE",
        39 to "MOSTLY_NVFP4", 40 to "MOSTLY_Q1_0", 41 to "MOSTLY_Q2_0"
    )

    private val TENSOR_TYPES = mapOf(
        0 to "F32", 1 to "F16", 2 to "Q4_0", 3 to "Q4_1", 6 to "Q5_0", 7 to "Q5_1",
        8 to "Q8_0", 9 to "Q8_1", 10 to "Q2_K", 11 to "Q3_K", 12 to "Q4_K",
        13 to "Q5_K", 14 to "Q6_K", 15 to "Q8_K", 16 to "IQ2_XXS", 17 to "IQ2_XS",
        18 to "IQ3_XXS", 19 to "IQ1_S", 20 to "IQ4_NL", 21 to "IQ3_S", 22 to "IQ2_S",
        23 to "IQ4_XS", 24 to "I8", 25 to "I16", 26 to "I32", 27 to "I64", 28 to "F64",
        29 to "IQ1_M", 30 to "BF16", 31 to "Q4_0_4_4", 32 to "Q4_0_4_8", 33 to "Q4_0_8_8",
        34 to "TQ1_0", 35 to "TQ2_0", 39 to "MXFP4", 40 to "NVFP4", 41 to "Q1_0", 42 to "Q2_0"
    )
}
