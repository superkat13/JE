package com.pineapple.sageos2.runtime

interface RuntimeObserver {
    fun onDiagnostic(message: String) = Unit
    fun onTypedInputQueued(depth: Int) = Unit
    fun onTypedInputRejected(reason: String) = Unit
    fun onUnhandledFailure(message: String, cause: Throwable? = null) = Unit
}

object NoOpRuntimeObserver : RuntimeObserver
