package com.pineapple.sageos2.brain

import com.pineapple.sage.SageBrainManager
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class LocalNativeBrainEngine(
    private val modelPath: String,
    private val maxTokens: Int = 192,
    private val manager: SageBrainManager = SageBrainManager(),
    private val libraryLoader: () -> Unit = { System.loadLibrary("sage-brain") }
) : BrainEngine {
    override val name: String = "sage-local-native"

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "sage-local-brain").apply { isDaemon = true }
    }
    private val loadAttempted = AtomicBoolean(false)
    private val loaded = AtomicBoolean(false)
    private val libraryReady = AtomicBoolean(false)
    private val lastLatency = AtomicLong(-1L)
    @Volatile private var lastError: String? = null

    init {
        runCatching { libraryLoader(); libraryReady.set(true) }
            .onFailure { lastError = "native library unavailable: ${it.message ?: it::class.simpleName}" }
    }

    override fun health(): BrainHealth {
        if (!libraryReady.get()) return BrainHealth(false, lastError ?: "native library unavailable")
        if (modelPath.isBlank()) return BrainHealth(false, "local model path is not configured")
        if (!File(modelPath).isFile) return BrainHealth(false, "local model file is missing: $modelPath")
        val ready = ensureLoaded()
        return BrainHealth(
            ready = ready,
            detail = if (ready) "native Brain ready" else lastError ?: "native Brain model failed to load",
            lastLatencyMs = lastLatency.get().takeIf { it >= 0L }
        )
    }

    override fun start(request: BrainRequest, callback: (Result<BrainResponse>) -> Unit): BrainJob {
        val cancelled = AtomicBoolean(false)
        if (!libraryReady.get() || !ensureLoaded()) {
            callback(Result.failure(BrainFailureException(
                kind = BrainFailureKind.UNAVAILABLE,
                engineName = name,
                message = lastError ?: "local native Brain is unavailable"
            )))
            return completedJob(request.turnId)
        }

        var future: Future<*>? = null
        val job = object : BrainJob {
            override val turnId: Long = request.turnId
            override fun cancel() {
                if (cancelled.compareAndSet(false, true)) {
                    runCatching { manager.nativeCancelGeneration(request.turnId) }
                    future?.cancel(true)
                }
            }
        }

        future = executor.submit {
            val started = System.nanoTime()
            try {
                val context = request.twinContextText.orEmpty()
                val text = manager.nativeGenerate(
                    request.turnId,
                    request.prompt,
                    context,
                    maxTokens,
                    false
                ).trim()
                val elapsed = (System.nanoTime() - started) / 1_000_000L
                lastLatency.set(elapsed)
                if (cancelled.get()) return@submit
                if (text.isBlank()) {
                    callback(Result.failure(BrainFailureException(
                        kind = BrainFailureKind.MODEL_ERROR,
                        engineName = name,
                        message = "native Brain returned an empty response"
                    )))
                } else {
                    callback(Result.success(BrainResponse(
                        turnId = request.turnId,
                        text = text,
                        engine = name,
                        provenance = BrainProvenance(
                            engine = name,
                            provider = "on-device",
                            model = File(modelPath).name
                        )
                    )))
                }
            } catch (t: Throwable) {
                lastError = t.message ?: t::class.simpleName
                if (!cancelled.get()) {
                    callback(Result.failure(BrainFailureException(
                        kind = BrainFailureKind.MODEL_ERROR,
                        engineName = name,
                        message = "native Brain failed: ${lastError.orEmpty()}"
                    )))
                }
            }
        }
        return job
    }

    @Synchronized
    private fun ensureLoaded(): Boolean {
        if (loaded.get()) return true
        if (loadAttempted.get()) return false
        loadAttempted.set(true)
        return runCatching { manager.nativeLoadModel(modelPath) }
            .onFailure { lastError = "model load failed: ${it.message ?: it::class.simpleName}" }
            .getOrDefault(false)
            .also {
                loaded.set(it)
                if (!it && lastError == null) lastError = "nativeLoadModel returned false"
            }
    }

    private fun completedJob(turnId: Long) = object : BrainJob {
        override val turnId: Long = turnId
        override fun cancel() = Unit
    }
}
