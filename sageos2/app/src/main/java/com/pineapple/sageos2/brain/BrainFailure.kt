package com.pineapple.sageos2.brain

enum class BrainFailureKind {
    UNAVAILABLE,
    TIMEOUT,
    PROVIDER_LIMITATION,
    MODEL_ERROR,
    CANCELLED,
    UNKNOWN
}

class BrainFailureException(
    val kind: BrainFailureKind,
    val engineName: String,
    val provider: String? = null,
    val model: String? = null,
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)
