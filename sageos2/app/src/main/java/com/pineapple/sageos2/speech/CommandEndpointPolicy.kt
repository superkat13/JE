package com.pineapple.sageos2.speech

/**
 * Pure endpoint / empty-result completion policy for the local command recognizer.
 *
 * Extracted from SageSherpaRecognitionService so the completion rules are testable off-device,
 * following the same convention as CommandRecognizerPolicy.
 *
 * Scope note: this policy decides WHEN a turn stops consuming microphone audio. It never
 * changes which text is produced for the audio it has already seen and it never re-decodes.
 * The text handed to [onWindowEnd] is always the same value the service already derived.
 *
 * It does, however, now change the *outcome* of a turn: [MIN_ENDPOINT_AUDIO_MS] refuses an empty
 * endpoint until enough audio has been consumed, so the decoder gets more of the utterance and can
 * produce text it previously never saw. That is the point of the change, and it is why the earlier
 * claim that this policy could not affect recognition accuracy no longer holds. What remains true
 * is that the policy never alters a text that has already been decoded.
 */
object CommandEndpointPolicy {
    /** Unchanged from the service: this policy does not shorten the overall budget. */
    const val DEFAULT_MAX_UTTERANCE_MS: Long = 15_000L
    const val SPEECH_ONSET_ENERGY: Long = 180L
    const val MIN_PEAK_ABS: Int = 32

    /**
     * Minimum microphone audio a turn must have consumed before an *empty* endpoint is allowed to
     * end it.
     *
     * Device evidence (VASOUN L10_T05, build 213). Every turn that ended `code=7` with no decoded
     * text finished 2,844 / 2,997 / 2,959 ms after the recognizer became ready, while the one turn
     * that produced text (`nonempty=true`) ran 5,573 ms. The native endpoint was truncating turns
     * roughly 2.6 s before the decoder had ever been observed to emit anything, so the empty result
     * was a consequence of the window closing, not of a broken model. 3,500 ms sits above all
     * observed failures and below the observed success, so an intra-utterance pause can no longer
     * truncate a command below the length the decoder needs.
     */
    const val MIN_ENDPOINT_AUDIO_MS: Long = 3_500L

    enum class ChunkAction { CONTINUE, FINISH_WITH_TEXT, FINISH_EMPTY }

    enum class WindowEnd { RESULTS, NO_MATCH, AUDIO_ERROR, SUPPRESSED }

    /**
     * @param endpointReached native recognizer endpoint (trailing silence) for this chunk
     * @param text text the decoder currently exposes for the stream; may be empty
     * @param speechBegan whether the amplitude onset gate has already fired this turn
     * @param audioMs microphone audio consumed this turn so far. Defaults to "already long enough"
     * so the rule is opt-in for callers that cannot supply it.
     *
     * Endpoint completion is deliberately gated on observed speech onset. The stock sherpa
     * rule1 can fire on leading silence, so completing an empty turn without the onset guard
     * would turn a normal quiet start into an instant no-match.
     *
     * An *empty* endpoint is additionally refused until [MIN_ENDPOINT_AUDIO_MS] of audio has been
     * consumed, because closing a turn too early guarantees a no-match. A non-empty text is never
     * held back: if the decoder has already produced something, that is a legitimate result and
     * keeping the microphone open would only add trailing noise. So this guard can convert an
     * empty turn into a real one, and can never discard real text.
     */
    @JvmStatic
    @JvmOverloads
    fun onChunk(
        endpointReached: Boolean,
        text: String,
        speechBegan: Boolean,
        audioMs: Long = Long.MAX_VALUE,
    ): ChunkAction = when {
        !endpointReached -> ChunkAction.CONTINUE
        !text.isBlank() -> ChunkAction.FINISH_WITH_TEXT
        audioMs < MIN_ENDPOINT_AUDIO_MS -> ChunkAction.CONTINUE
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
