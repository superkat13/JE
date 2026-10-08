package com.pineapple.sageos2.speech.voicerepair

interface VoiceRepairFaultInjector {
    fun shouldInjectLifecycleFailure(): Boolean
    fun injectedLifecycleErrorCode(): Int
    fun reset()
}

object NoopVoiceRepairFaultInjector : VoiceRepairFaultInjector {
    override fun shouldInjectLifecycleFailure(): Boolean = false
    override fun injectedLifecycleErrorCode(): Int = 0
    override fun reset() = Unit
}