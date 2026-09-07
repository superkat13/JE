package com.pineapple.sageos2.brain

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class LocalNativeBrainEngine(
    private val modelPath: String,
    private val maxTokens: Int = 192,
    private val bridge: NativeBrainBridge = JniNativeBrainBridge(),
    private val libraryLoader: () -> Unit = { System.loadLibrary("sage-brain") }
) : BrainEngine {
    override val name: String = "sage-local-native"

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "sage-local-brain").apply { isDaemon = true }
    }
    private val libraryLoadAttempted = AtomicBoolean(false)
    private val libraryReady = AtomicBoolean(false)
    private val loadAttempted = AtomicBoolean(false)
    private val loaded = AtomicBoolean(false)
    private val lastLatency = AtomicLong(-1L)
    private val inFlightStage = AtomicReference<BrainProgressStage?>(null)
    @Volatile private var lastError: String? = null

    /**
     * Health is deliberately observational. It must never load a native library, multi-gigabyte
     * GGUF model, or enter inference merely because the owner opened the cockpit/status screen.
     */
    override fun health(): BrainHealth {
        if (modelPath.isBlank()) return BrainHealth(false, "local model path is not configured")
        if (!File(modelPath).isFile) return BrainHealth(false, "local model file is missing: $modelPath")
        val progress = inFlightStage.get()
        if (libraryLoadAttempted.get() && !libraryReady.get() && progress == null) {
            return BrainHealth(false, lastError ?: "native library unavailable")
        }
        if (loadAttempted.get() && !loaded.get() && progress == null) {
            return BrainHealth(false, lastError ?: "local model did not finish loading")
        }
        return BrainHealth(
            ready = true,
            detail = when {
                progress == BrainProgressStage.LOADING_MODEL -> "local model is loading"
                progress == BrainProgressStage.GENERATING -> "local Brain is writing a response"
                progress == BrainProgressStage.PREPARING -> "local Brain is preparing"
                loaded.get() -> "native Brain ready"
                libraryReady.get() -> "native Brain library ready; model load deferred until first deep turn"
                else -> "native Brain available; native load deferred until first deep turn"
            },
            lastLatencyMs = lastLatency.get().takeIf { it >= 0L },
            progressStage = progress
        )
    }

    override fun start(request: BrainRequest, callback: (Result<BrainResponse>) -> Unit): BrainJob {
        val cancelled = AtomicBoolean(false)
        var future: Future<*>? = null
        val job = object : BrainJob {
            override val turnId: Long = request.turnId
            override fun cancel() {
                if (cancelled.compareAndSet(false, true)) {
                    if (libraryReady.get()) runCatching { bridge.cancel(request.turnId) }
                    future?.cancel(true)
                }
            }
        }

        reportProgress(request, BrainProgressStage.PREPARING)
        future = executor.submit {
            try {
                if (cancelled.get()) return@submit
                if (!loaded.get()) reportProgress(request, BrainProgressStage.LOADING_MODEL)
                if (!ensureLibraryReady()) {
                    if (!cancelled.get()) {
                        callback(Result.failure(BrainFailureException(
                            kind = BrainFailureKind.UNAVAILABLE,
                            engineName = name,
                            message = lastError ?: "local native Brain library is unavailable"
                        )))
                    }
                    return@submit
                }
                if (cancelled.get()) return@submit
                if (!ensureLoaded()) {
                    if (!cancelled.get()) {
                        callback(Result.failure(BrainFailureException(
                            kind = BrainFailureKind.UNAVAILABLE,
                            engineName = name,
                            message = lastError ?: "local native Brain model failed to load"
                        )))
                    }
                    return@submit
                }

                if (cancelled.get()) return@submit
                reportProgress(request, BrainProgressStage.GENERATING)
                val started = System.nanoTime()
                val systemPrompt = request.twinContextText.orEmpty()
                val userPrompt = request.prompt
                val text = bridge.generate(
                    requestId = request.turnId,
                    systemPrompt = systemPrompt,
                    userPrompt = userPrompt,
                    maxTokens = maxTokens,
                    deterministic = false
                ).trim()
                val elapsed = (System.nanoTime() - started) / 1_000_000L
                lastLatency.set(elapsed)
                if (cancelled.get()) return@submit
                if (text.isBlank()) {
                    callback(Result.failure(BrainFailureException(
                        kind = BrainFailureKind.MODEL_ERROR,
                        engineName = name,
                        message = "native Brain returned an empty response [stage=${safeStage()}]"
                    )))
                } else {
                    callback(Result.success(BrainResponse(
                        turnId = request.turnId,
                        text = text,
                        engine = name,
                        provenance = BrainProvenance(
                            engine = name,
                            provider = "on-device",
                            model = File(modelPath).name,
                            attempts = listOf(
                                "first_token_ms=${safeLong { bridge.firstTokenLatencyMs() }}",
                                "generation_ms=${safeLong { bridge.generationDurationMs() }}",
                                "prompt_tokens=${safeInt { bridge.promptTokenCount() }}",
                                "generated_tokens=${safeInt { bridge.generatedTokenCount() }}"
                            )
                        )
                    )))
                }
            } catch (t: Throwable) {
                val nativeError = runCatching { bridge.lastError().trim() }.getOrDefault("")
                lastError = nativeError.ifBlank { t.message ?: t::class.simpleName }
                if (!cancelled.get()) {
                    callback(Result.failure(BrainFailureException(
                        kind = BrainFailureKind.MODEL_ERROR,
                        engineName = name,
                        message = "native Brain failed: ${lastError.orEmpty()} [stage=${safeStage()}]"
                    )))
                }
            } finally {
                inFlightStage.set(null)
            }
        }
        return job
    }

    @Synchronized
    private fun ensureLibraryReady(): Boolean {
        if (libraryReady.get()) return true
        if (libraryLoadAttempted.get()) return false
        libraryLoadAttempted.set(true)
        return runCatching {
            libraryLoader()
            true
        }.onFailure {
            lastError = "native library unavailable: ${it.message ?: it::class.simpleName}"
        }.getOrDefault(false).also { libraryReady.set(it) }
    }

    @Synchronized
    private fun ensureLoaded(): Boolean {
        if (loaded.get()) return true
        if (loadAttempted.get()) return false
        loadAttempted.set(true)
        return runCatching { bridge.loadModel(modelPath) }
            .onFailure { lastError = "model load failed: ${it.message ?: it::class.simpleName}" }
            .getOrDefault(false)
            .also {
                loaded.set(it)
                if (!it && lastError == null) {
                    lastError = runCatching { bridge.lastError().trim() }.getOrDefault("").ifBlank { "nativeLoadModel returned false" }
                }
            }
    }

    private fun safeStage() = if (libraryReady.get()) runCatching { bridge.stage().trim() }.getOrDefault("") else "not_loaded"
    private inline fun safeLong(block: () -> Long) = if (libraryReady.get()) runCatching(block).getOrDefault(-1L) else -1L
    private inline fun safeInt(block: () -> Int) = if (libraryReady.get()) runCatching(block).getOrDefault(-1) else -1

    private fun reportProgress(request: BrainRequest, stage: BrainProgressStage) {
        inFlightStage.set(stage)
        runCatching { request.onProgress(BrainProgress(request.turnId, stage)) }
    }

    private fun completedJob(turnId: Long) = object : BrainJob {
        override val turnId: Long = turnId
        override fun cancel() = Unit
    }
}
