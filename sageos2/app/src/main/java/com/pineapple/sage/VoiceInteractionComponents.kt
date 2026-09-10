package com.pineapple.sage

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/**
 * System-selected, lightweight home for Sage's always-available voice identity.
 * Do not load the LLM or other heavyweight engines in this process.
 */
class SageVoiceInteractionService : VoiceInteractionService() {
    @Volatile
    private var systemReady = false

    override fun onReady() {
        super.onReady()
        systemReady = true
        // The slim wake engine is attached here in the next speech-runtime gate.
    }

    override fun onShutdown() {
        systemReady = false
        super.onShutdown()
    }

    fun isSystemReadyForWake(): Boolean = systemReady
}

class SageVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        SageVoiceInteractionSession(this)
}

class SageVoiceInteractionSession(context: Context) : VoiceInteractionSession(context)
