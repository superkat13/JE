package com.pineapple.sageos2.mode

data class SageModeSnapshot(
    val profileId: String = "sage",
    val modeId: String? = null
)

interface SageModeController {
    fun activate(profileId: String, modeId: String?)
    fun current(): SageModeSnapshot
}

object DefaultSageModeController : SageModeController {
    @Volatile private var snapshot = SageModeSnapshot()

    override fun activate(profileId: String, modeId: String?) {
        snapshot = SageModeSnapshot(profileId, modeId)
    }

    override fun current(): SageModeSnapshot = snapshot
}
