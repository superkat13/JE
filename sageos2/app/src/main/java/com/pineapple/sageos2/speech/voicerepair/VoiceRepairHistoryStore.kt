package com.pineapple.sageos2.speech.voicerepair

/** Persists the bounded voice-repair lifecycle so restart evidence stays honest.
 * A session that never reached a terminal state is retained as the in-flight/interrupted marker,
 * never relabeled repaired, and never used to automatically repeat a repair.
 */
interface VoiceRepairHistoryStore {
    /** Observes every published session. Terminal sessions enter the bounded history;
     * active sessions pin the in-flight marker that restart reports as interrupted.
     */
    fun observe(session: VoiceRepairSession)

    /** Most recently completed terminal session, ordered by endedAtMs; null when empty. */
    fun latestTerminal(): VoiceRepairSession?

    /** A previously active session that never reached a terminal state (interrupted/unverified). */
    fun interruptedSession(): VoiceRepairSession?

    /** Bounded terminal history, oldest first. */
    fun history(): List<VoiceRepairSession>

    fun clear()
}