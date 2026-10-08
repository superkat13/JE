package com.pineapple.sageos2.mode

data class SageModeSnapshot(
    val profileId: String = "sage",
    val modeId: String? = null,
    val tone: SageTone = SageTone.UNFILTERED
)

enum class SageTone {
    CLEAN,
    CASUAL,
    UNFILTERED;

    fun displayName(): String = name.lowercase().replaceFirstChar(Char::uppercase)

    fun brainDirection(): String = when (this) {
        CLEAN -> "Use warm language without profanity."
        CASUAL -> "Speak casually in Sage's own voice; occasional natural mild profanity is welcome."
        UNFILTERED -> "Speak naturally in Sage's own voice, mirror the owner's language, and feel free to use playful profanity."
    }
}

interface SageModeController {
    fun activate(profileId: String, modeId: String?)
    fun setTone(tone: SageTone) = Unit
    fun current(): SageModeSnapshot
}

object DefaultSageModeController : SageModeController {
    @Volatile private var snapshot = SageModeSnapshot()

    override fun activate(profileId: String, modeId: String?) {
        snapshot = snapshot.copy(profileId = profileId, modeId = modeId)
    }

    override fun setTone(tone: SageTone) { snapshot = snapshot.copy(tone = tone) }

    override fun current(): SageModeSnapshot = snapshot
}
