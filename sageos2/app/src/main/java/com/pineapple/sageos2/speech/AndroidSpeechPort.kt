package com.pineapple.sageos2.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.pineapple.sageos2.core.SageListeningMode
import java.util.Locale
import java.util.UUID

class AndroidSpeechPort(
    context: Context,
    private val wakeWordEngine: WakeWordEngine
) : SpeechPort, RecognitionListener, TextToSpeech.OnInitListener {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var listener: SpeechInputListener? = null
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: PendingSpeech? = null
    private var desiredMode = SageListeningMode.OFF
    private var generation = 0L
    private var turnId = 0L
    private var destroyed = false

    init {
        main.post { if (!destroyed) tts = TextToSpeech(appContext, this) }
    }

    override fun attach(listener: SpeechInputListener) { this.listener = listener }

    override fun setListening(mode: SageListeningMode, generation: Long, turnId: Long) {
        main.post {
            if (destroyed) return@post
            this.desiredMode = mode
            this.generation = generation
            this.turnId = turnId
            stopInput()
            when (mode) {
                SageListeningMode.OFF -> Unit
                SageListeningMode.WAKE_ONLY -> startWake(generation)
                SageListeningMode.COMMAND, SageListeningMode.FOLLOW_UP -> startRecognition(turnId, generation)
            }
        }
    }

    override fun speak(turnId: Long, text: String, onComplete: () -> Unit) {
        main.post {
            if (destroyed) { onComplete(); return@post }
            stopInput()
            val pending = PendingSpeech(turnId, text, onComplete)
            if (!ttsReady) { pendingSpeech = pending; return@post }
            speakNow(pending)
        }
    }

    override fun speakTransient(text: String) {
        main.post {
            if (destroyed || !ttsReady) return@post
            val resumeMode = desiredMode
            val resumeGeneration = generation
            stopInput()
            val id = "transient-${UUID.randomUUID()}"
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onError(utteranceId: String?) = onDone(utteranceId)
                override fun onDone(utteranceId: String?) {
                    main.post {
                        if (!destroyed && desiredMode == resumeMode && generation == resumeGeneration) {
                            when (resumeMode) {
                                SageListeningMode.WAKE_ONLY -> startWake(resumeGeneration)
                                SageListeningMode.COMMAND, SageListeningMode.FOLLOW_UP -> startRecognition(turnId, resumeGeneration)
                                SageListeningMode.OFF -> Unit
                            }
                        }
                    }
                }
            })
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        }
    }

    override fun onInit(status: Int) {
        main.post {
            if (destroyed) return@post
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale.US
                pendingSpeech?.also { pendingSpeech = null; speakNow(it) }
            } else {
                listener?.onSpeechDiagnostic("TTS initialization failed: $status")
                pendingSpeech?.also { pendingSpeech = null; it.onComplete() }
            }
        }
    }

    private fun speakNow(pending: PendingSpeech) {
        val id = "sage-${pending.turnId}-${UUID.randomUUID()}"
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onError(utteranceId: String?) = onDone(utteranceId)
            override fun onDone(utteranceId: String?) { main.post { pending.onComplete() } }
        })
        val result = tts?.speak(pending.text, TextToSpeech.QUEUE_FLUSH, null, id) ?: TextToSpeech.ERROR
        if (result == TextToSpeech.ERROR) pending.onComplete()
    }

    private fun startWake(generation: Long) {
        try {
            wakeWordEngine.start(generation) { heardGeneration -> listener?.onWakeDetected(heardGeneration) }
        } catch (t: Throwable) {
            listener?.onSpeechDiagnostic("wake start failed: ${t.message}")
        }
    }

    private fun startRecognition(turnId: Long, generation: Long) {
        if (!ensureRecognizer()) {
            listener?.onRecognitionError(turnId, generation, SpeechRecognizer.ERROR_CLIENT)
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        try { recognizer?.startListening(intent) }
        catch (t: Throwable) {
            listener?.onSpeechDiagnostic("recognizer start failed: ${t.message}")
            listener?.onRecognitionError(turnId, generation, SpeechRecognizer.ERROR_CLIENT)
        }
    }

    private fun ensureRecognizer(): Boolean {
        if (recognizer != null) return true
        return try {
            recognizer = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext) ->
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
                SpeechRecognizer.isRecognitionAvailable(appContext) -> SpeechRecognizer.createSpeechRecognizer(appContext)
                else -> null
            }
            recognizer?.setRecognitionListener(this)
            recognizer != null
        } catch (t: Throwable) {
            listener?.onSpeechDiagnostic("recognizer creation failed: ${t.message}")
            recognizer = null
            false
        }
    }

    private fun stopInput() {
        try { wakeWordEngine.stop() } catch (_: Throwable) {}
        try { recognizer?.cancel() } catch (_: Throwable) {}
    }

    override fun onResults(results: Bundle?) {
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull { it.isNotBlank() }
        if (text == null) listener?.onRecognitionError(turnId, generation, SpeechRecognizer.ERROR_NO_MATCH)
        else listener?.onTranscriptFinal(turnId, generation, text)
    }

    override fun onError(error: Int) { listener?.onRecognitionError(turnId, generation, error) }
    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onPartialResults(partialResults: Bundle?) = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    override fun shutdown() {
        main.post {
            destroyed = true
            stopInput()
            try { wakeWordEngine.close() } catch (_: Throwable) {}
            recognizer?.destroy(); recognizer = null
            tts?.stop(); tts?.shutdown(); tts = null
            pendingSpeech = null
        }
    }

    private data class PendingSpeech(val turnId: Long, val text: String, val onComplete: () -> Unit)
}
