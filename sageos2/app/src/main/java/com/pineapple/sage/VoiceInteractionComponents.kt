package com.pineapple.sage

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

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

/**
 * Preserves the recognizer component expected by Sage 1.x and by Android's voice
 * interaction metadata. Real recognition is intentionally not faked here: until
 * the new local recognizer is connected, callers receive an explicit client error.
 */
class SageSherpaRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        listener?.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onStopListening(listener: Callback?) = Unit

    override fun onCancel(listener: Callback?) = Unit
}
