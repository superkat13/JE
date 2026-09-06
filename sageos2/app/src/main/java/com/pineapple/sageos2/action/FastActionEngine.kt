package com.pineapple.sageos2.action

data class FastActionRequest(val turnId: Long, val command: String)

data class FastActionResponse(
    val turnId: Long,
    val text: String,
    val allowFollowUp: Boolean = true
)

interface FastActionJob {
    val turnId: Long
    fun cancel()
}

interface FastActionEngine {
    fun start(
        request: FastActionRequest,
        callback: (Result<FastActionResponse>) -> Unit
    ): FastActionJob
}
