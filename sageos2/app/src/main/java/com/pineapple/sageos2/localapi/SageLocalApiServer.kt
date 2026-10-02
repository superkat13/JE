package com.pineapple.sageos2.localapi

import android.os.Handler
import android.os.Looper
import com.pineapple.sageos2.core.SageRuntimeState
import com.pineapple.sageos2.runtime.SageRuntimeHost
import com.pineapple.sageos2.runtime.SageRuntimeListener
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

sealed interface SageLocalApiResult {
    data class Success(val text: String) : SageLocalApiResult
    data class Failure(val status: Int, val message: String) : SageLocalApiResult
}

fun interface SagePromptGateway {
    fun generate(prompt: String): SageLocalApiResult
}

/**
 * Adapts one localhost request into Sage's existing text-turn pipeline.
 *
 * The bridge deliberately refuses to enqueue behind an in-progress owner turn. That keeps a
 * terminal request from accidentally receiving the response for a different turn.
 */
class SageRuntimePromptGateway(
    private val host: SageRuntimeHost,
    private val main: Handler = Handler(Looper.getMainLooper()),
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS
) : SagePromptGateway {
    private val requestInFlight = AtomicBoolean(false)

    override fun generate(prompt: String): SageLocalApiResult {
        val clean = prompt.trim()
        if (clean.isEmpty()) return SageLocalApiResult.Failure(400, "prompt is empty")
        if (!requestInFlight.compareAndSet(false, true)) {
            return SageLocalApiResult.Failure(409, "Sage local API already has a request in flight")
        }

        val latch = CountDownLatch(1)
        val result = AtomicReference<SageLocalApiResult?>()
        val listener = object : SageRuntimeListener {
            override fun onTextResponse(turnId: Long, text: String) {
                if (result.compareAndSet(null, SageLocalApiResult.Success(text))) {
                    latch.countDown()
                }
            }

            override fun onTypedInputRejected(reason: String) {
                if (result.compareAndSet(null, SageLocalApiResult.Failure(409, reason))) {
                    latch.countDown()
                }
            }
        }

        host.addListener(listener)
        try {
            main.post {
                val accepted = runCatching { host.submitTextIfReady(clean) }
                    .getOrElse {
                        result.compareAndSet(
                            null,
                            SageLocalApiResult.Failure(
                                500,
                                "Sage could not accept the local request: ${it.message ?: it::class.java.simpleName}"
                            )
                        )
                        false
                    }
                if (!accepted && result.compareAndSet(
                        null,
                        SageLocalApiResult.Failure(
                            409,
                            "Sage is busy; retry when she is idle"
                        )
                    )
                ) {
                    latch.countDown()
                } else if (!accepted) {
                    latch.countDown()
                }
            }

            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                return SageLocalApiResult.Failure(
                    504,
                    "Sage did not finish the local request within ${timeoutMs / 1000L} seconds"
                )
            }
            return result.get() ?: SageLocalApiResult.Failure(500, "Sage local request ended without a response")
        } finally {
            host.removeListener(listener)
            requestInFlight.set(false)
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 130_000L
    }
}

/**
 * Minimal Ollama-compatible localhost endpoint for GNU gettext's `spit` utility.
 *
 * Supported contract:
 *   POST /api/generate
 *   {"model":"sage","prompt":"..."}
 *
 * The socket is bound only to 127.0.0.1. It is not reachable over Wi-Fi or other interfaces.
 */
class SageLocalApiServer(
    private val gateway: SagePromptGateway,
    private val port: Int = DEFAULT_PORT,
    private val logger: (String) -> Unit = {}
) {
    private val running = AtomicBoolean(false)
    private val clients = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "sage-local-api-client").apply { isDaemon = true }
    }

    @Volatile private var serverSocket: ServerSocket? = null

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return serverSocket != null
        return try {
            val socket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName(LOOPBACK), port), BACKLOG)
            }
            serverSocket = socket
            Thread({ acceptLoop(socket) }, "sage-local-api").apply {
                isDaemon = true
                start()
            }
            logger("listening on ${socket.inetAddress.hostAddress}:${socket.localPort}")
            true
        } catch (t: Throwable) {
            running.set(false)
            serverSocket = null
            logger("failed to bind localhost API: ${t.message ?: t::class.java.simpleName}")
            false
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        clients.shutdownNow()
        logger("stopped")
    }

    fun boundPort(): Int? = serverSocket?.localPort
    fun boundAddress(): InetAddress? = serverSocket?.inetAddress

    private fun acceptLoop(server: ServerSocket) {
        while (running.get()) {
            try {
                val client = server.accept()
                clients.execute { handleClient(client) }
            } catch (t: Throwable) {
                if (running.get()) logger("accept failed: ${t.message ?: t::class.java.simpleName}")
            }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            client.soTimeout = READ_TIMEOUT_MS
            runCatching {
                val request = readRequest(client)
                val response = handle(request)
                writeResponse(client, response)
            }.onFailure { error ->
                logger("request failed: ${error.message ?: error::class.java.simpleName}")
                runCatching {
                    writeResponse(
                        client,
                        HttpResponse(
                            400,
                            "Bad Request",
                            JSONObject().put("error", error.message ?: "bad request").toString()
                        )
                    )
                }
            }
        }
    }

    private fun handle(request: HttpRequest): HttpResponse {
        if (request.method != "POST") {
            return jsonError(405, "Method Not Allowed", "only POST is supported")
        }
        if (request.path != GENERATE_PATH) {
            return jsonError(404, "Not Found", "unknown endpoint")
        }

        val payload = runCatching { JSONObject(request.body) }.getOrElse {
            return jsonError(400, "Bad Request", "request body must be JSON")
        }
        val model = payload.optString("model", "").trim()
        val prompt = payload.optString("prompt", "")
        if (model != MODEL_NAME) {
            return jsonError(404, "Not Found", "model '$model' is not available; use '$MODEL_NAME'")
        }
        if (prompt.isBlank()) {
            return jsonError(400, "Bad Request", "prompt is empty")
        }

        return when (val generated = gateway.generate(prompt)) {
            is SageLocalApiResult.Success -> {
                val line = JSONObject()
                    .put("model", MODEL_NAME)
                    .put("response", generated.text)
                    .put("done", true)
                    .toString() + "\n"
                HttpResponse(200, "OK", line, "application/x-ndjson; charset=utf-8")
            }
            is SageLocalApiResult.Failure -> {
                val reason = reasonFor(generated.status)
                jsonError(generated.status, reason, generated.message)
            }
        }
    }

    private fun readRequest(socket: Socket): HttpRequest {
        val input = BufferedInputStream(socket.getInputStream())
        val headerBytes = ByteArrayOutputStream()
        var matched = 0
        while (headerBytes.size() < MAX_HEADER_BYTES) {
            val value = input.read()
            if (value < 0) throw IllegalArgumentException("connection ended before HTTP headers")
            headerBytes.write(value)
            val expected = HEADER_END[matched].code
            matched = if (value == expected) matched + 1 else if (value == HEADER_END[0].code) 1 else 0
            if (matched == HEADER_END.length) break
        }
        if (matched != HEADER_END.length) throw IllegalArgumentException("HTTP headers are too large")

        val headerText = headerBytes.toString(StandardCharsets.ISO_8859_1.name())
        val lines = headerText.removeSuffix(HEADER_END).split("\r\n")
        val requestLine = lines.firstOrNull()?.split(' ') ?: emptyList()
        if (requestLine.size < 2) throw IllegalArgumentException("invalid HTTP request line")

        val headers = mutableMapOf<String, String>()
        lines.drop(1).forEach { line ->
            val index = line.indexOf(':')
            if (index > 0) {
                headers[line.substring(0, index).trim().lowercase(Locale.US)] =
                    line.substring(index + 1).trim()
            }
        }
        val contentLength = headers["content-length"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Content-Length is required")
        if (contentLength !in 0..MAX_BODY_BYTES) throw IllegalArgumentException("request body is too large")

        val bodyBytes = ByteArray(contentLength)
        var offset = 0
        while (offset < contentLength) {
            val count = input.read(bodyBytes, offset, contentLength - offset)
            if (count < 0) throw IllegalArgumentException("connection ended before request body")
            offset += count
        }

        return HttpRequest(
            method = requestLine[0].uppercase(Locale.US),
            path = requestLine[1].substringBefore('?'),
            body = String(bodyBytes, StandardCharsets.UTF_8)
        )
    }

    private fun writeResponse(socket: Socket, response: HttpResponse) {
        val body = response.body.toByteArray(StandardCharsets.UTF_8)
        val output = BufferedOutputStream(socket.getOutputStream())
        val headers = buildString {
            append("HTTP/1.1 ${response.status} ${response.reason}\r\n")
            append("Content-Type: ${response.contentType}\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n")
            append("Cache-Control: no-store\r\n")
            append("\r\n")
        }.toByteArray(StandardCharsets.ISO_8859_1)
        output.write(headers)
        output.write(body)
        output.flush()
    }

    private fun jsonError(status: Int, reason: String, message: String) =
        HttpResponse(status, reason, JSONObject().put("error", message).toString() + "\n")

    private fun reasonFor(status: Int): String = when (status) {
        400 -> "Bad Request"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        409 -> "Conflict"
        504 -> "Gateway Timeout"
        else -> "Internal Server Error"
    }

    private data class HttpRequest(val method: String, val path: String, val body: String)
    private data class HttpResponse(
        val status: Int,
        val reason: String,
        val body: String,
        val contentType: String = "application/json; charset=utf-8"
    )

    companion object {
        const val DEFAULT_PORT = 11434
        const val MODEL_NAME = "sage"
        const val GENERATE_PATH = "/api/generate"
        private const val LOOPBACK = "127.0.0.1"
        private const val BACKLOG = 4
        private const val READ_TIMEOUT_MS = 10_000
        private const val MAX_HEADER_BYTES = 32 * 1024
        private const val MAX_BODY_BYTES = 1024 * 1024
        private const val HEADER_END = "\r\n\r\n"
    }
}
