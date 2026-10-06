package com.pineapple.sageos2.speech.voicerepair

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class VoiceRepairSessionManager(
    private val clockMs: () -> Long = System::currentTimeMillis
) {
    private val currentRef = AtomicReference<VoiceRepairSession?>(null)

    fun current(): VoiceRepairSession? = currentRef.get()

    fun startSession(testPhrase: String, timeoutMs: Long = VoiceRepairPolicy.DEFAULT_TIMEOUT_MS): VoiceRepairSession {
        val id = UUID.randomUUID().toString()
        val now = clockMs()
        val session = VoiceRepairSession(
            id = id,
            state = VoiceRepairState.DIAGNOSING,
            cause = VoiceRepairCause.NONE,
            maxAttempts = VoiceRepairPolicy.MAX_ATTEMPTS,
            startedAtMs = now,
            deadlineMs = now + timeoutMs,
            testPhrase = testPhrase.trim(),
            steps = listOf(VoiceRepairStep("start", now, "session started"))
        )
        currentRef.set(session)
        return session
    }

    fun update(session: VoiceRepairSession): VoiceRepairSession {
        val now = clockMs()
        if (session.deadlineMs != null && now > session.deadlineMs && session.state !in setOf(VoiceRepairState.SUCCESS, VoiceRepairState.FAILED, VoiceRepairState.CANCELLED, VoiceRepairState.INTERRUPTED)) {
            val updated = session.copy(
                state = VoiceRepairState.FAILED,
                cause = VoiceRepairCause.DEADLINE_EXCEEDED,
                endedAtMs = now,
                steps = session.steps + VoiceRepairStep("deadline", now, "exceeded")
            )
            currentRef.set(updated)
            return updated
        }
        currentRef.set(session)
        return session
    }

    fun cancel(): VoiceRepairSession? {
        val current = currentRef.get() ?: return null
        val now = clockMs()
        val cancelled = current.copy(
            state = VoiceRepairState.CANCELLED,
            cause = VoiceRepairCause.CANCELLED_BY_OWNER,
            cancelled = true,
            endedAtMs = now,
            steps = current.steps + VoiceRepairStep("cancel", now, "owner cancelled")
        )
        currentRef.set(cancelled)
        return cancelled
    }

    fun markInterrupted(): VoiceRepairSession? {
        val current = currentRef.get() ?: return null
        val now = clockMs()
        val interrupted = current.copy(
            state = VoiceRepairState.INTERRUPTED,
            interrupted = true,
            endedAtMs = now,
            steps = current.steps + VoiceRepairStep("interrupt", now, "interrupted by app")
        )
        currentRef.set(interrupted)
        return interrupted
    }

    fun clear() {
        currentRef.set(null)
    }
}