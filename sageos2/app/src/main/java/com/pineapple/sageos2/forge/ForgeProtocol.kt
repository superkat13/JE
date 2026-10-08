package com.pineapple.sageos2.forge

import java.net.URL

object ForgeProtocol {
    const val MAX_RESPONSE_BYTES = 262_144
    const val CONNECT_TIMEOUT_MS = 7_000
    const val READ_TIMEOUT_MS = 12_000
    private val JOB_ID = Regex("job_[0-9a-f]{24}")

    fun normalizeOrigin(value: String): String {
        val url = URL(value.trim())
        require(url.protocol == "https") { "Forge address must use HTTPS" }
        require(url.path.isEmpty() || url.path == "/") { "Forge address must be an HTTPS origin with no path" }
        require(url.query == null && url.ref == null) { "Forge address must be an HTTPS origin with no query or fragment" }
        require(url.host.isNotBlank()) { "Forge address has no host" }
        return value.trim().removeSuffix("/")
    }

    fun safeJobId(value: String): String {
        require(JOB_ID.matches(value)) { "invalid Forge job ID" }
        return value
    }
}
