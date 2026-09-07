package com.pineapple.sageos2.brain

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sequential Brain fallback. Engine/provider failures keep their provenance.
 * A provider limitation is not rewritten as Sage's own refusal.
 */
class BrainRouterEngine(
    private val engines: List<BrainEngine>
) : BrainEngine {
    init { require(engines.isNotEmpty()) }

    override val name: String = "sage-brain-router"

    override fun health(): BrainHealth {
        val healthy = engines.map { it to runCatching { it.health() }.getOrElse { BrainHealth(false, it.message ?: "health failed") } }
        val ready = healthy.filter { it.second.ready }
        return BrainHealth(
            ready = ready.isNotEmpty(),
            detail = healthy.joinToString("; ") { (engine, health) -> "${engine.name}=${if (health.ready) "ready" else "down"}:${health.detail}" },
            lastLatencyMs = ready.mapNotNull { it.second.lastLatencyMs }.minOrNull(),
            progressStage = ready.firstNotNullOfOrNull { it.second.progressStage }
        )
    }

    override fun start(request: BrainRequest, callback: (Result<BrainResponse>) -> Unit): BrainJob {
        val cancelled = AtomicBoolean(false)
        val attempts = mutableListOf<String>()
        var active: BrainJob? = null

        val outer = object : BrainJob {
            override val turnId: Long = request.turnId
            override fun cancel() {
                cancelled.set(true)
                active?.cancel()
            }
        }

        fun tryIndex(index: Int) {
            if (cancelled.get()) return
            if (index >= engines.size) {
                callback(Result.failure(BrainFailureException(
                    kind = BrainFailureKind.UNAVAILABLE,
                    engineName = name,
                    message = "No configured Brain completed the request. Attempts: ${attempts.joinToString(" | ")}"
                )))
                return
            }

            val engine = engines[index]
            val health = runCatching { engine.health() }.getOrNull()
            if (health != null && !health.ready) {
                attempts += "${engine.name}: unavailable (${health.detail})"
                tryIndex(index + 1)
                return
            }

            attempts += "${engine.name}: attempted"
            active = try {
                engine.start(request) { result ->
                    if (cancelled.get()) return@start
                    result.fold(
                        onSuccess = { response ->
                            val merged = response.copy(
                                provenance = response.provenance.copy(
                                    attempts = attempts.toList()
                                )
                            )
                            callback(Result.success(merged))
                        },
                        onFailure = { error ->
                            val failure = error as? BrainFailureException
                            val description = if (failure != null) {
                                "${engine.name}: ${failure.kind} (${failure.message})"
                            } else {
                                "${engine.name}: UNKNOWN (${error.message ?: error::class.simpleName})"
                            }
                            attempts += description
                            when (failure?.kind ?: BrainFailureKind.UNKNOWN) {
                                BrainFailureKind.CANCELLED -> callback(Result.failure(error))
                                BrainFailureKind.UNAVAILABLE,
                                BrainFailureKind.TIMEOUT,
                                BrainFailureKind.PROVIDER_LIMITATION,
                                BrainFailureKind.MODEL_ERROR,
                                BrainFailureKind.UNKNOWN -> tryIndex(index + 1)
                            }
                        }
                    )
                }
            } catch (t: Throwable) {
                attempts += "${engine.name}: start failed (${t.message ?: t::class.simpleName})"
                tryIndex(index + 1)
                null
            }
        }

        tryIndex(0)
        return outer
    }
}
