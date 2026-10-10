package com.pineapple.sageos2.speech.voicerepair

/** Owner-facing, phrase-only readiness prompt. Rendered only when the recognizer itself has
 *  reported readiness, so the owner never speaks into a microphone that is not yet open. No
 *  identifiers, codes, or terminal phrasing; the typed phrase is the one piece of owner input. */
object VoiceRepairReadinessPresenter {

    /** The prompt to speak, or null when there is nothing to prompt for yet. */
    fun ownerText(session: VoiceRepairSession): String? {
        if (session.state != VoiceRepairState.TESTING_EXPECTED && session.state != VoiceRepairState.RETESTING) return null
        if (session.steps.none { it.name == "ready" }) return null
        return "My microphone is listening now. Please say “${session.testPhrase}” clearly, once."
    }
}
