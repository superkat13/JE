package com.pineapple.sageos2.speech.voicerepair

import java.util.UUID

/** Serializes session ownership; retired callbacks cannot overwrite a successor or terminal result. */
class VoiceRepairSessionManager(
    private val store: VoiceRepairStore = MemoryVoiceRepairStore(),
    private val clockMs: () -> Long = System::currentTimeMillis
) {
    private val records = store.load().takeLast(HISTORY_LIMIT).toMutableList()
    private var session: VoiceRepairSession? = records.lastOrNull()

    init {
        val restored = session
        if (restored != null && restored.state !in TERMINAL) {
            save(restored.copy(state = VoiceRepairState.INTERRUPTED, interrupted = true,
                endedAtMs = clockMs(), steps = restored.steps + VoiceRepairStep("interrupted", clockMs())))
        }
    }

    @Synchronized fun history(): List<VoiceRepairSession> { expire(); return records.toList() }

    private fun save(value: VoiceRepairSession): VoiceRepairSession {
        session = value
        records.removeAll { it.id == value.id }
        records.add(value)
        while (records.size > HISTORY_LIMIT) records.removeAt(0)
        store.save(records.toList())
        return value
    }

    @Synchronized fun current(): VoiceRepairSession? {
        expire()
        return session
    }

    @Synchronized fun startSession(testPhrase: String, timeoutMs: Long = VoiceRepairPolicy.DEFAULT_TIMEOUT_MS): VoiceRepairSession {
        require(timeoutMs > 0) { "timeout must be positive" }
        expire()
        check(session == null || session!!.state in TERMINAL) { "voice repair already active" }
        val now = clockMs()
        return VoiceRepairSession(
            id = UUID.randomUUID().toString(), state = VoiceRepairState.DIAGNOSING,
            cause = VoiceRepairCause.NONE, maxAttempts = VoiceRepairPolicy.MAX_ATTEMPTS,
            startedAtMs = now, deadlineMs = now + timeoutMs, testPhrase = testPhrase.trim(),
            steps = listOf(VoiceRepairStep("start", now, "session started"))
        ).also { save(it) }
    }

    @Synchronized fun update(candidate: VoiceRepairSession): VoiceRepairSession {
        expire()
        val current = checkNotNull(session) { "no voice repair session" }
        if (current.id != candidate.id || current.state in TERMINAL) return current
        if (candidate.state.ordinal < current.state.ordinal) return current
        require(candidate.deadlineMs == current.deadlineMs) { "deadline cannot be extended by callbacks" }
        require(candidate.attemptCount in current.attemptCount..current.maxAttempts) { "repair attempt budget exceeded" }
        if (candidate.state == VoiceRepairState.SUCCESS) {
            require(candidate.secondTest?.errorCode == null && candidate.secondTest != null &&
                candidate.repairAppliedAtMs != null && candidate.attemptCount == 1 &&
                candidate.secondTest.expected == current.testPhrase &&
                candidate.secondTest.recognized?.trim()?.equals(current.testPhrase.trim(), ignoreCase = true) == true) {
                "repair success requires a matching retest after a repair"
            }
        }
        return save(candidate)
    }

    @Synchronized fun cancel(): VoiceRepairSession? = finish(VoiceRepairState.CANCELLED)
    @Synchronized fun markInterrupted(): VoiceRepairSession? = finish(VoiceRepairState.INTERRUPTED)

    private fun finish(state: VoiceRepairState): VoiceRepairSession? {
        expire()
        val current = session ?: return null
        if (current.state in TERMINAL) return null
        val now = clockMs()
        return current.copy(state = state,
            cause = if (state == VoiceRepairState.CANCELLED) VoiceRepairCause.CANCELLED_BY_OWNER else current.cause,
            cancelled = state == VoiceRepairState.CANCELLED,
            interrupted = state == VoiceRepairState.INTERRUPTED,
            endedAtMs = now,
            steps = current.steps + VoiceRepairStep(state.name.lowercase(), now)
        ).also { save(it) }
    }

    private fun expire() {
        val current = session ?: return
        val deadline = current.deadlineMs ?: return
        val now = clockMs()
        if (current.state !in TERMINAL && now >= deadline) {
            save(current.copy(state = VoiceRepairState.FAILED, cause = VoiceRepairCause.DEADLINE_EXCEEDED,
                endedAtMs = now, steps = current.steps + VoiceRepairStep("deadline", now, "exceeded")))
        }
    }

    @Synchronized fun clear() { session = null }

    companion object {
        const val HISTORY_LIMIT = 10
        private val TERMINAL = setOf(VoiceRepairState.HEALTHY, VoiceRepairState.SUCCESS, VoiceRepairState.FAILED, VoiceRepairState.CANCELLED, VoiceRepairState.INTERRUPTED)
    }
}
