package com.pineapple.sageos2.forge

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class ForgeApproval(
    val approved: Boolean,
    val surface: String,
    val action: String
)

interface ForgeCallback {
    fun complete(value: JSONObject)
    fun failed(detail: String)
}

class ForgeClient(
    context: Context,
    private val store: ForgeStore = ForgeStore(context),
    private val io: ExecutorService = Executors.newCachedThreadPool(),
    private val main: Handler = Handler(Looper.getMainLooper())
) {
    fun pair(url: String, certPin: String, pairingCode: String, deviceName: String, callback: ForgeCallback) {
        val body = JSONObject()
            .put("pairing_code", pairingCode)
            .put("device_name", deviceName)
        send(url, certPin, null, "POST", "/v1/pair", body, callback)
    }

    fun pairAndStore(url: String, certPin: String, pairingCode: String, deviceName: String, callback: ForgeCallback) {
        pair(url, certPin, pairingCode, deviceName, object : ForgeCallback {
            override fun complete(value: JSONObject) {
                runCatching {
                    store.savePairing(
                        url = url,
                        certSha256 = certPin,
                        deviceId = value.getString("device_id"),
                        deviceToken = value.getString("device_token")
                    )
                }.fold(
                    onSuccess = { callback.complete(value) },
                    onFailure = { callback.failed("Secure Forge storage failed: ${safeProblem(it)}") }
                )
            }
            override fun failed(detail: String) = callback.failed(detail)
        })
    }

    fun health(callback: ForgeCallback) {
        val pairing = store.pairing() ?: return callback.failed("Forge is not paired")
        send(pairing.url, pairing.certSha256, null, "GET", "/v1/health", null, callback)
    }

    fun tools(callback: ForgeCallback) = authenticated("GET", "/v1/tools", null, callback)
    fun job(jobId: String, callback: ForgeCallback) = authenticated("GET", "/v1/jobs/${ForgeProtocol.safeJobId(jobId)}", null, callback)
    fun cancel(jobId: String, callback: ForgeCallback) = authenticated("POST", "/v1/jobs/${ForgeProtocol.safeJobId(jobId)}/cancel", JSONObject(), callback)
    fun revoke(callback: ForgeCallback) = authenticated("POST", "/v1/devices/current/revoke", JSONObject(), callback)

    fun startJob(
        toolId: String,
        input: JSONObject,
        approval: ForgeApproval,
        callback: ForgeCallback
    ) {
        require(toolId.isNotBlank())
        val approvalContext = JSONObject()
            .put("surface", approval.surface)
            .put("action", approval.action)
        val body = JSONObject()
            .put("tool_id", toolId)
            .put("input", input)
            .put("owner_approved", approval.approved)
            .put("approval_context", approvalContext)
        authenticated("POST", "/v1/jobs", body, object : ForgeCallback {
            override fun complete(value: JSONObject) {
                value.optString("job_id").takeIf { it.isNotBlank() }?.let { id ->
                    runCatching { store.saveActiveJob(id) }
                }
                callback.complete(value)
            }
            override fun failed(detail: String) = callback.failed(detail)
        })
    }

    private fun authenticated(method: String, path: String, body: JSONObject?, callback: ForgeCallback) {
        val pairing = store.pairing() ?: return callback.failed("Forge is not paired")
        val token = runCatching { store.token() }.getOrElse {
            return callback.failed("Forge pairing token unavailable: ${safeProblem(it)}")
        }
        send(pairing.url, pairing.certSha256, token, method, path, body, callback)
    }

    private fun send(
        base: String,
        certPin: String,
        token: String?,
        method: String,
        path: String,
        body: JSONObject?,
        callback: ForgeCallback
    ) {
        io.submit {
            var connection: HttpsURLConnection? = null
            try {
                val origin = ForgeProtocol.normalizeOrigin(base)
                val normalizedPin = ForgeStore.normalizePin(certPin)
                val tls = SSLContext.getInstance("TLS")
                tls.init(null, arrayOf<TrustManager>(PinTrust(normalizedPin)), SecureRandom())
                val endpoint = URL(origin + path)
                connection = endpoint.openConnection() as HttpsURLConnection
                connection.sslSocketFactory = tls.socketFactory
                connection.connectTimeout = ForgeProtocol.CONNECT_TIMEOUT_MS
                connection.readTimeout = ForgeProtocol.READ_TIMEOUT_MS
                connection.requestMethod = method
                connection.setRequestProperty("Accept", "application/json")
                connection.useCaches = false
                if (token != null) {
                    connection.setRequestProperty("Authorization", "SageToken $token")
                    connection.setRequestProperty("X-Sage-Timestamp", (System.currentTimeMillis() / 1000L).toString())
                    connection.setRequestProperty("X-Sage-Nonce", UUID.randomUUID().toString().replace("-", ""))
                }
                if (body != null) {
                    val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
                    connection.doOutput = true
                    connection.setFixedLengthStreamingMode(bytes.size)
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(bytes) }
                }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val value = JSONObject(readBounded(stream))
                if (status !in 200..299) {
                    throw IllegalStateException("Forge rejected request ($status): ${value.optString("message", "unknown error")}")
                }
                main.post { callback.complete(value) }
            } catch (t: Throwable) {
                main.post { callback.failed(safeProblem(t)) }
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun readBounded(stream: InputStream?): String {
        if (stream == null) return "{}"
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        stream.use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= ForgeProtocol.MAX_RESPONSE_BYTES) { "Forge response exceeded limit" }
                out.write(buffer, 0, count)
            }
        }
        return out.toString(StandardCharsets.UTF_8.name())
    }

    private class PinTrust(private val pin: String) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {
            throw CertificateException("client certificates not accepted")
        }
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {
            if (chain.isNullOrEmpty()) throw CertificateException("Forge sent no certificate")
            val digest = MessageDigest.getInstance("SHA-256")
            val actual = buildString {
                for (value in digest.digest(chain[0].encoded)) append(String.format(Locale.US, "%02x", value))
            }
            if (!MessageDigest.isEqual(actual.toByteArray(StandardCharsets.US_ASCII), pin.toByteArray(StandardCharsets.US_ASCII))) {
                throw CertificateException("Forge certificate pin mismatch")
            }
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    companion object {
        private fun safeProblem(t: Throwable): String = (t.message ?: t::class.simpleName ?: "unknown error").replace(Regex("\\s+"), " ").trim()
    }
}
