package com.pineapple.sageos2.speech.voicerepair

/** Decides whether a voice repair session completion should be surfaced to the owner unprompted.
 * Result-carrying terminal captures (SUCCESS/HEALTHY/FAILED) are presented after capture by the
 * runtime's text-response channel, outside any turn state machine. Interruption and cancellation
 * are never pushed; they are surfaced through the status command and startup notices instead.
 */
object VoiceRepairCompletionPresenter {
    private val PRESENTABLE = setOf(
        VoiceRepairState.SUCCESS, VoiceRepairState.HEALTHY, VoiceRepairState.FAILED
    )

    /** Owner-visible completion text, or null when nothing should be pushed for this session. */
    fun ownerText(session: VoiceRepairSession): String? {
        if (session.state !in PRESENTABLE) return null
        return "Voice test complete. ${VoiceRepairReporter.terminalReport(session)}"
    }
}