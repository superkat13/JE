package com.pineapple.sageos2.localapi

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SageLocalApiServerTest {

    @Test
    fun spitCompatibleGenerateRequestReturnsOneNdjsonResponse() {
        val server = SageLocalApiServer(
            gateway = SagePromptGateway { prompt ->
                SageLocalApiResult.Success("Sage heard: $prompt")
            },
            port = 0
        )

        try {
            assertTrue(server.start())
            assertTrue(server.boundAddress()?.isLoopbackAddress == true)

            val body = JSONObject()
                .put("model", "sage")
                .put("prompt", "hello from Termux")
                .toString()
            val response = request(server.boundPort()!!, "POST", "/api/generate", body)

            assertTrue(response.startsWith("HTTP/1.1 200 OK"))
            val payload = JSONObject(response.substringAfter("\r\n\r\n").trim())
            assertEquals("sage", payload.getString("model"))
            assertEquals("Sage heard: hello from Termux", payload.getString("response"))
            assertTrue(payload.getBoolean("done"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun unknownModelIsRejectedBeforeReachingSage() {
        val calls = AtomicInteger(0)
        val server = SageLocalApiServer(
            gateway = SagePromptGateway {
                calls.incrementAndGet()
                SageLocalApiResult.Success("should not run")
            },
            port = 0
        )

        try {
            assertTrue(server.start())
            val body = JSONObject()
                .put("model", "qwen")
                .put("prompt", "hello")
                .toString()
            val response = request(server.boundPort()!!, "POST", "/api/generate", body)

            assertTrue(response.startsWith("HTTP/1.1 404 Not Found"))
            assertEquals(0, calls.get())
            assertTrue(response.substringAfter("\r\n\r\n").contains("use 'sage'"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun busySageMapsToHttp409() {
        val server = SageLocalApiServer(
            gateway = SagePromptGateway {
                SageLocalApiResult.Failure(409, "Sage is busy; retry when she is idle")
            },
            port = 0
        )

        try {
            assertTrue(server.start())
            val body = JSONObject()
                .put("model", "sage")
                .put("prompt", "second request")
                .toString()
            val response = request(server.boundPort()!!, "POST", "/api/generate", body)

            assertTrue(response.startsWith("HTTP/1.1 409 Conflict"))
            val payload = JSONObject(response.substringAfter("\r\n\r\n").trim())
            assertTrue(payload.getString("error").contains("busy"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun onlyGeneratePostEndpointIsExposed() {
        val server = SageLocalApiServer(
            gateway = SagePromptGateway { SageLocalApiResult.Success("unused") },
            port = 0
        )

        try {
            assertTrue(server.start())
            val getResponse = request(server.boundPort()!!, "GET", "/api/generate", "")
            assertTrue(getResponse.startsWith("HTTP/1.1 405 Method Not Allowed"))

            val missingResponse = request(server.boundPort()!!, "POST", "/api/tags", "{}")
            assertTrue(missingResponse.startsWith("HTTP/1.1 404 Not Found"))
        } finally {
            server.stop()
        }
    }

    private fun request(port: Int, method: String, path: String, body: String): String =
        Socket("127.0.0.1", port).use { socket ->
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            val request = buildString {
                append("$method $path HTTP/1.1\r\n")
                append("Host: 127.0.0.1\r\n")
                append("Content-Length: ${bytes.size}\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }.toByteArray(StandardCharsets.ISO_8859_1)

            socket.getOutputStream().apply {
                write(request)
                write(bytes)
                flush()
            }
            socket.getInputStream().bufferedReader(StandardCharsets.UTF_8).readText()
        }
}
