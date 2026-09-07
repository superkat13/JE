package com.pineapple.sageos2.brain

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class LocalNativeBrainEngine(
    private val modelPath: String,
    private val maxTokens: Int = 24,
    private val bridge: NativeBrainBridge = JniNativeBrainBridge(),
    private val libraryLoader: () -> Unit = { System.loadLibrary("sage-brain") }
) : BrainEngine {
    override val name: String = "sage-local-native"

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "sage-local-brain").apply { isDaemon = true }
    }
    private val progressExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "sage-local-brain-progress").apply { isDaemon = true }
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
            return BrainHealth(false, lastError ?: "native library unavailable", telemetry = telemetry())
        }
        return BrainHealth(
            ready = true,
            detail = when {
                progress == BrainProgressStage.LOADING_MODEL -> "local model is loading"
                progress == BrainProgressStage.READING_CONTEXT -> "local Brain is reading the turn context"
                progress == BrainProgressStage.GENERATING -> "local Brain is writing a response"
                progress == BrainProgressStage.PREPARING -> "local Brain is preparing"
                loaded.get() -> "native Brain ready"
                loadAttempted.get() && lastError != null -> "previous model load failed; Sage will retry on the next message"
                libraryReady.get() -> "native Brain library ready; model load deferred until first deep turn"
                else -> "native Brain available; native load deferred until first deep turn"
            },
            lastLatencyMs = lastLatency.get().takeIf { it >= 0L },
            progressStage = progress,
            telemetry = telemetry()
        )
    }

    override fun start(request: BrainRequest, callback: (Result<BrainResponse>) -> Unit): BrainJob {
        val cancelled = AtomicBoolean(false)
        val completionSent = AtomicBoolean(false)
        var future: Future<*>? = null
        val complete: (Result<BrainResponse>) -> Unit = { result ->
            if (completionSent.compareAndSet(false, true)) {
                inFlightStage.set(null)
                callback(result)
            }
        }
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
                        complete(Result.failure(BrainFailureException(
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
                        complete(Result.failure(BrainFailureException(
                            kind = BrainFailureKind.UNAVAILABLE,
                            engineName = name,
                            message = lastError ?: "local native Brain model failed to load"
                        )))
                    }
                    return@submit
                }

                if (cancelled.get()) return@submit
                reportProgress(request, BrainProgressStage.READING_CONTEXT)
                val started = System.nanoTime()
                val systemPrompt = request.twinContextText.orEmpty()
                val userPrompt = request.prompt
                val requestedTokens = (request.maxOutputTokens ?: maxTokens).coerceIn(1, maxTokens)
                val lastReported = AtomicReference(BrainProgressStage.READING_CONTEXT)
                val lastReportedTokens = AtomicLong(-1L)
                val progressMonitor = progressExecutor.scheduleAtFixedRate({
                    if (!cancelled.get() && !completionSent.get()) {
                        val telemetry = telemetry()
                        nativeProgressStage(telemetry?.nativeStage.orEmpty())?.let { stage ->
                            val generatedTokens = telemetry?.generatedTokens?.toLong() ?: -1L
                            val stageChanged = lastReported.getAndSet(stage) != stage
                            val tokenCountChanged = generatedTokens >= 0L &&
                                lastReportedTokens.getAndSet(generatedTokens) != generatedTokens
                            if (stageChanged || tokenCountChanged) {
                                reportProgress(request, stage, telemetry)
                            }
                        }
                    }
                }, 100L, 350L, TimeUnit.MILLISECONDS)
                val rawText = try {
                    bridge.generate(
                        requestId = request.turnId,
                        systemPrompt = systemPrompt,
                        userPrompt = userPrompt,
                        maxTokens = requestedTokens,
                        deterministic = request.deterministic
                    )
                } finally {
                    progressMonitor.cancel(false)
                }
                val text = BrainOutputCleaner.clean(rawText)
                val elapsed = (System.nanoTime() - started) / 1_000_000L
                lastLatency.set(elapsed)
                if (cancelled.get()) return@submit
                val generatedTokens = safeInt { bridge.generatedTokenCount() }
                val expectedLiteral = request.expectedLiteral
                if (expectedLiteral != null && generatedTokens <= 0) {
                    complete(Result.failure(BrainFailureException(
                        kind = BrainFailureKind.MODEL_ERROR,
                        engineName = name,
                        message = "local Brain self-check generated no measured tokens ${telemetryDetail()}"
                    )))
                } else if (expectedLiteral != null && !BrainRequestPolicy.literalMatches(text, expectedLiteral)) {
                    complete(Result.failure(BrainFailureException(
                        kind = BrainFailureKind.MODEL_ERROR,
                        engineName = name,
                        message = "local Brain self-check did not return the requested words ${telemetryDetail()}"
                    )))
                } else if (text.isBlank()) {
                    complete(Result.failure(BrainFailureException(
                        kind = BrainFailureKind.MODEL_ERROR,
                        engineName = name,
                        message = if (rawText.isBlank()) {
                            "native Brain returned an empty response ${telemetryDetail()}"
                        } else {
                            "native Brain returned only hidden reasoning or model-control text ${telemetryDetail()}"
                        }
                    )))
                } else {
                    lastError = null
                    complete(Result.success(BrainResponse(
                        turnId = request.turnId,
                        text = text,
                        engine = name,
                        provenance = BrainProvenance(
                            engine = name,
                            provider = "on-device",
                            model = File(modelPath).name,
                            attempts = listOf(
                                "native_stage=${safeStage()}",
                                "first_token_ms=${safeLong { bridge.firstTokenLatencyMs() }}",
                                "generation_ms=${safeLong { bridge.generationDurationMs() }}",
                                "prefill_ms=${safeLong { bridge.promptPrefillDurationMs() }}",
                                "prompt_tokens=${safeInt { bridge.promptTokenCount() }}",
                                "prompt_tps=${safeFloat { bridge.promptTokensPerSecond() }}",
                                "generated_tokens=${safeInt { bridge.generatedTokenCount() }}",
                                "requested_tokens=$requestedTokens",
                                "deterministic=${request.deterministic}"
                            )
                        )
                    )))
                }
            } catch (t: Throwable) {
                val nativeError = runCatching { bridge.lastError().trim() }.getOrDefault("")
                lastError = nativeError.ifBlank { t.message ?: t::class.simpleName }
                if (!cancelled.get()) {
                    complete(Result.failure(BrainFailureException(
                        kind = BrainFailureKind.MODEL_ERROR,
                        engineName = name,
                        message = "native Brain failed: ${lastError.orEmpty()} ${telemetryDetail()}"
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
        loadAttempted.set(true)
        return runCatching { bridge.loadModel(modelPath) }
            .onFailure { lastError = "model load failed: ${it.message ?: it::class.simpleName}" }
            .getOrDefault(false)
            .also {
                loaded.set(it)
                if (it) lastError = null
                if (!it && lastError == null) {
                    lastError = runCatching { bridge.lastError().trim() }.getOrDefault("").ifBlank { "nativeLoadModel returned false" }
                }
            }
    }

    private fun safeStage() = if (libraryReady.get()) runCatching { bridge.stage().trim() }.getOrDefault("") else "not_loaded"
    private inline fun safeLong(block: () -> Long) = if (libraryReady.get()) runCatching(block).getOrDefault(-1L) else -1L
    private inline fun safeInt(block: () -> Int) = if (libraryReady.get()) runCatching(block).getOrDefault(-1) else -1
    private inline fun safeFloat(block: () -> Float) = if (libraryReady.get()) runCatching(block).getOrDefault(-1f) else -1f

    private fun nativeProgressStage(stage: String): BrainProgressStage? = when (stage) {
        "model_verification", "model_load", "context_creation" -> BrainProgressStage.LOADING_MODEL
        "prompt_tokenization", "prompt_prefill" -> BrainProgressStage.READING_CONTEXT
        "sampling", "first_token", "generation" -> BrainProgressStage.GENERATING
        else -> null
    }

    private fun telemetry(): BrainTelemetry? {
        if (!libraryReady.get()) return null
        return BrainTelemetry(
            nativeStage = safeStage().ifBlank { null },
            promptTokens = safeInt { bridge.promptTokenCount() }.takeIf { it >= 0 },
            generatedTokens = safeInt { bridge.generatedTokenCount() }.takeIf { it >= 0 },
            promptPrefillMs = safeLong { bridge.promptPrefillDurationMs() }.takeIf { it >= 0L },
            firstTokenMs = safeLong { bridge.firstTokenLatencyMs() }.takeIf { it >= 0L },
            generationMs = safeLong { bridge.generationDurationMs() }.takeIf { it >= 0L },
            promptTokensPerSecond = safeFloat { bridge.promptTokensPerSecond() }.takeIf { it >= 0f }
        )
    }

    private fun telemetryDetail(): String = telemetry()?.let { telemetry ->
        "[stage=${telemetry.nativeStage ?: "unknown"}, prompt_tokens=${telemetry.promptTokens ?: -1}, " +
            "generated_tokens=${telemetry.generatedTokens ?: -1}, prefill_ms=${telemetry.promptPrefillMs ?: -1}, " +
            "first_token_ms=${telemetry.firstTokenMs ?: -1}]"
    } ?: "[stage=not_loaded]"

    private fun reportProgress(
        request: BrainRequest,
        stage: BrainProgressStage,
        telemetry: BrainTelemetry? = null
    ) {
        inFlightStage.set(stage)
        runCatching { request.onProgress(BrainProgress(request.turnId, stage, telemetry)) }
    }

    private fun completedJob(turnId: Long) = object : BrainJob {
        override val turnId: Long = turnId
        override fun cancel() = Unit
    }
}
