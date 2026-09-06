package com.pineapple.sageos2.runtime

import com.pineapple.sageos2.core.SageRuntimeSnapshot

interface RuntimeObserver {
    fun onDiagnostic(message: String) = Unit
    fun onTypedInputQueued(depth: Int) = Unit
    fun onTypedInputRejected(reason: String) = Unit
    fun onUnhandledFailure(message: String, cause: Throwable? = null) = Unit
    fun onStateChanged(snapshot: SageRuntimeSnapshot) = Unit
    fun onTextResponse(turnId: Long, text: String) = Unit
}

object NoOpRuntimeObserver : RuntimeObserver
