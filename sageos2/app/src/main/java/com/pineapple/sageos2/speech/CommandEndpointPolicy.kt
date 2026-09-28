package com.pineapple.sageos2.speech

/**
 * Pure endpoint / empty-result completion policy for the local command recognizer.
 *
 * Extracted from SageSherpaRecognitionService so the completion rules are testable off-device,
 * following the same convention as CommandRecognizerPolicy.
 *
 * Scope note: this policy decides WHEN a turn stops consuming microphone audio. It never
 * changes which text is produced and it never re-decodes, so it cannot change recognition
 * accuracy. The text handed to [onWindowEnd] is the same value the service already derived.
 * Reducing failed-turn delay is the only behavioural effect.
 */
object CommandEndpointPolicy {
    /** Unchanged from the service: this policy does not shorten the overall budget. */
    const val DEFAULT_MAX_UTTERANCE_MS: Long = 15_000L
    const val SPEECH_ONSET_ENERGY: Long = 180L
    const val MIN_PEAK_ABS: Int = 32

    enum class ChunkAction { CONTINUE, FINISH_WITH_TEXT, FINISH_EMPTY }

    enum class WindowEnd { RESULTS, NO_MATCH, AUDIO_ERROR, SUPPRESSED }

    /**
     * @param endpointReached native recognizer endpoint (trailing silence) for this chunk
     * @param text text the decoder currently exposes for the stream; may be empty
     * @param speechBegan whether the amplitude onset gate has already fired this turn
     *
     * Endpoint completion is deliberately gated on observed speech onset. The stock sherpa
     * rule1 can fire on leading silence, so completing an empty turn without the onset guard
     * would turn a normal quiet start into an instant no-match.
     */
    @JvmStatic
    fun onChunk(endpointReached: Boolean, text: String, speechBegan: Boolean): ChunkAction = when {
        !endpointReached -> ChunkAction.CONTINUE
        !text.isBlank() -> ChunkAction.FINISH_WITH_TEXT
        speechBegan -> ChunkAction.FINISH_EMPTY
        else -> ChunkAction.CONTINUE
    }

    @JvmStatic
    fun shouldContinue(elapsedMs: Long, stopRequested: Boolean, maxUtteranceMs: Long = DEFAULT_MAX_UTTERANCE_MS): Boolean =
        !stopRequested && elapsedMs < maxUtteranceMs

    /**
     * Classifies a finished window.
     *
     * SUPPRESSED wins over everything so an explicit stop never emits a late transcript or a
     * late error into a turn the caller already abandoned.
     */
    @JvmStatic
    fun onWindowEnd(stopRequested: Boolean, totalSamples: Long, peakAbs: Int, finalText: String): WindowEnd = when {
        stopRequested -> WindowEnd.SUPPRESSED
        finalText.isNotBlank() -> WindowEnd.RESULTS
        totalSamples == 0L || peakAbs < MIN_PEAK_ABS -> WindowEnd.AUDIO_ERROR
        else -> WindowEnd.NO_MATCH
    }

    /** Mirrors the service's amplitude onset gate (1-in-4 subsample mean absolute value). */
    @JvmStatic
    fun hasSpeechEnergy(subsampledAbsSum: Long, count: Int): Boolean =
        subsampledAbsSum / maxOf(1, count / 4) > SPEECH_ONSET_ENERGY
}
