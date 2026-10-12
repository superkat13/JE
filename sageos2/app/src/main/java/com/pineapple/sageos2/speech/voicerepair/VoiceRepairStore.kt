package com.pineapple.sageos2.speech.voicerepair

interface VoiceRepairStore {
    fun load(): List<VoiceRepairSession>
    fun save(sessions: List<VoiceRepairSession>)
}

class MemoryVoiceRepairStore : VoiceRepairStore {
    private var sessions = emptyList<VoiceRepairSession>()
    override fun load() = sessions.toList()
    override fun save(sessions: List<VoiceRepairSession>) { this.sessions = sessions.toList() }
}
