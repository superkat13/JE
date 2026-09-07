package com.pineapple.sageos2.core

/** Owner-facing language stays human; exact engine evidence remains in Diagnostics. */
object SageResponseCopy {
    fun forBrainFailure(reason: String): String {
        val normalized = reason.lowercase()
        return when {
            normalized.contains("model file is missing") ||
                normalized.contains("local model path is not configured") ->
                "I can't find my local Brain model yet. Open Settings → Local Brain, choose the GGUF model, then send that again."

            normalized.contains("model load failed") ||
                normalized.contains("could not load that gguf") ||
                normalized.contains("did not finish loading") ->
                "My local Brain couldn't finish loading that model. Your message is still here. Open Settings → Local Brain for the next step."

            normalized.contains("timed out") || normalized.contains("timeout") ->
                "I was taking too long, so I stopped this turn instead of leaving chat stuck. Your message is saved—try it once more."

            normalized.contains("context window") || normalized.contains("prompt exceeded") ->
                "That was more context than my local Brain could hold in one turn. Your message is saved—send a shorter version and I'll pick it up."

            else ->
                "I couldn't finish that reply, but I'm still here and your message is saved. Try it again; the details are under Settings → Diagnostics."
        }
    }
}
