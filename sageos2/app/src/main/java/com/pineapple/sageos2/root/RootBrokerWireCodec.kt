package com.pineapple.sageos2.root

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

object RootBrokerWireCodec {
    private const val HEADER = "SAGE_ROOTD 1"
    private const val MAX_RESPONSE_BYTES = 256 * 1024

    fun encode(request: RootBrokerRequest): ByteArray {
        require(request.requestId.isNotBlank() && request.requestId.length <= 128)
        val operation = encodeOperation(request.operation)
        val out = StringBuilder()
        out.append(HEADER).append('\n')
        out.append("id ").append(hex(request.requestId)).append('\n')
        out.append("op ").append(operation.name).append('\n')
        operation.fields.forEach { (key, value) ->
            require(value.isNotEmpty()) { "Protocol v1 does not encode empty field values" }
            out.append("f ").append(key).append(' ').append(hex(value)).append('\n')
        }
        operation.args.forEach { arg ->
            require(arg.isNotEmpty()) { "Protocol v1 does not encode empty argv entries" }
            out.append("a ").append(hex(arg)).append('\n')
        }
        operation.environment.forEach { (key, value) ->
            require(key.isNotEmpty() && value.isNotEmpty()) { "Protocol v1 does not encode empty environment entries" }
            out.append("e ").append(hex(key)).append(' ').append(hex(value)).append('\n')
        }
        out.append("END\n")
        return out.toString().toByteArray(StandardCharsets.UTF_8)
    }

    fun decode(input: InputStream): RootBrokerResult {
        val bytes = ByteArrayOutputStream()
        val one = ByteArray(1)
        var tail = ""
        while (bytes.size() < MAX_RESPONSE_BYTES) {
            val read = input.read(one)
            if (read < 0) break
            bytes.write(one, 0, read)
            tail = (tail + one[0].toInt().toChar()).takeLast(5)
            if (tail == "END\n" && bytes.toString(StandardCharsets.UTF_8.name()).contains("\nEND\n")) break
        }
        require(bytes.size() < MAX_RESPONSE_BYTES) { "Root broker response exceeded protocol limit" }
        val lines = bytes.toString(StandardCharsets.UTF_8.name()).lineSequence().toList()
        require(lines.firstOrNull() == HEADER) { "Root broker protocol mismatch" }
        val values = linkedMapOf<String, String>()
        for (line in lines.drop(1)) {
            if (line == "END") break
            val split = line.indexOf(' ')
            require(split > 0) { "Malformed root broker response line" }
            values[line.substring(0, split)] = line.substring(split + 1)
        }
        val requestId = unhex(values.getValue("id"))
        val success = when (values.getValue("success")) {
            "1" -> true
            "0" -> false
            else -> error("Invalid root broker success flag")
        }
        return RootBrokerResult(
            requestId = requestId,
            success = success,
            code = unhex(values.getValue("code")),
            detail = unhex(values.getValue("detail")),
            auditId = values["audit"]?.let(::unhex),
            exitCode = values["exit"]?.toIntOrNull(),
            stdout = values["stdout"]?.let(::unhex),
            stderr = values["stderr"]?.let(::unhex)
        )
    }

    private data class Encoded(
        val name: String,
        val fields: List<Pair<String, String>> = emptyList(),
        val args: List<String> = emptyList(),
        val environment: List<Pair<String, String>> = emptyList()
    )

    private fun encodeOperation(operation: RootOperation): Encoded = when (operation) {
        RootOperation.Health -> Encoded("HEALTH")
        is RootOperation.InstallPackage -> Encoded(
            "INSTALL_PACKAGE",
            listOf("path" to operation.apkPath, "replace" to bit(operation.replaceExisting))
        )
        is RootOperation.UninstallPackage -> Encoded(
            "UNINSTALL_PACKAGE",
            listOf("package" to operation.packageName, "keep_data" to bit(operation.keepData))
        )
        is RootOperation.SetPackageEnabled -> Encoded(
            "SET_PACKAGE_ENABLED",
            listOf("package" to operation.packageName, "enabled" to bit(operation.enabled))
        )
        is RootOperation.WriteSecureSetting -> {
            require(operation.value == null || operation.value.isNotEmpty()) { "Empty setting values are reserved for protocol v2" }
            buildList {
                add("namespace" to operation.namespace.name.lowercase())
                add("key" to operation.key)
                add("has_value" to bit(operation.value != null))
                operation.value?.let { add("value" to it) }
            }.let { Encoded("WRITE_SETTING", it) }
        }
        is RootOperation.SetFileOwnership -> Encoded(
            "CHOWN",
            listOf("path" to operation.path, "uid" to operation.uid.toString(), "gid" to operation.gid.toString())
        )
        is RootOperation.SetFileMode -> Encoded(
            "CHMOD",
            listOf("path" to operation.path, "mode" to operation.mode.toString())
        )
        is RootOperation.RestartSystemService -> Encoded(
            "RESTART_SERVICE",
            listOf("service" to operation.serviceName)
        )
        is RootOperation.Power -> Encoded("POWER", listOf("action" to operation.action.name))
        is RootOperation.ExecuteProcess -> Encoded(
            "EXEC",
            buildList {
                add("executable" to operation.executable)
                add("timeout_ms" to operation.timeoutMs.toString())
                operation.workingDirectory?.let { add("cwd" to it) }
            },
            args = operation.args,
            environment = operation.environment.entries.map { it.key to it.value }
        )
    }

    private fun bit(value: Boolean) = if (value) "1" else "0"

    private fun hex(text: String): String = text.toByteArray(StandardCharsets.UTF_8).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun unhex(text: String): String {
        require(text.length % 2 == 0) { "Invalid hex field" }
        val bytes = ByteArray(text.length / 2)
        for (i in bytes.indices) {
            bytes[i] = text.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return String(bytes, StandardCharsets.UTF_8)
    }
}
