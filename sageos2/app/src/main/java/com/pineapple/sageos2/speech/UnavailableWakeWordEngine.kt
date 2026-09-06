package com.pineapple.sageos2.speech

/** Temporary explicit adapter until the dedicated offline KWS engine is packaged. */
class UnavailableWakeWordEngine : WakeWordEngine {
    @Volatile private var profiles: List<WakeProfile> = emptyList()
    override fun configure(profiles: List<WakeProfile>) { this.profiles = profiles }
    override fun start(generation: Long, onWake: (WakeHit) -> Unit) = Unit
    override fun stop() = Unit
    override fun health() = WakeWordHealth(false, "not-installed", "dedicated offline keyword spotter is not packaged yet; push-to-talk remains available")
}
