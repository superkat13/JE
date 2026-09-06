package com.pineapple.sageos2.root

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.util.UUID

class SocketRootBrokerClient(
    private val connectTimeoutMs: Int = 2_000
) : RootBrokerClient {

    override fun health(): RootBrokerHealth {
        val result = execute(RootBrokerRequest(UUID.randomUUID().toString(), RootOperation.Health))
        if (!result.success) return RootBrokerHealth(false, detail = result.detail)
        val prefix = "sage-rootd "
        val version = result.detail.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)
        return RootBrokerHealth(true, version = version, detail = result.detail)
    }

    override fun execute(request: RootBrokerRequest): RootBrokerResult {
        return try {
            LocalSocket().use { socket ->
                socket.soTimeout = connectTimeoutMs
                socket.connect(LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.RESERVED))
                socket.outputStream.write(RootBrokerWireCodec.encode(request))
                socket.outputStream.flush()
                RootBrokerWireCodec.decode(socket.inputStream)
            }
        } catch (t: Throwable) {
            RootBrokerResult(
                requestId = request.requestId,
                success = false,
                code = "ROOT_BROKER_UNAVAILABLE",
                detail = "SageOS root broker handshake failed: ${t.message ?: t::class.java.simpleName}"
            )
        }
    }

    companion object {
        const val SOCKET_NAME = "sage_rootd"
    }
}
