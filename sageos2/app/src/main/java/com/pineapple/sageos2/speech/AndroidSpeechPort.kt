package com.pineapple.sageos2.speech

import android.annotation.SuppressLint
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
import com.pineapple.sage.SageSherpaRecognitionService
import com.pineapple.sageos2.core.SageListeningMode
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class AndroidSpeechPort(
    context: Context,
    private val wakeWordEngine: WakeWordEngine,
    private val wakeProfiles: WakeProfileProvider = SharedPreferencesWakeProfileStore(context)
) : SpeechPort, TextToSpeech.OnInitListener {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var listener: SpeechInputListener? = null
    private var recognizer: SpeechRecognizer? = null
    private var recognizerBackend = CommandRecognizerBackend.UNAVAILABLE
    private var recognitionSession = 0L
    private var localFallbackAttempted = false
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: PendingSpeech? = null
    private var desiredMode = SageListeningMode.OFF
    private var generation = 0L
    private var turnId = 0L
    private var destroyed = false

    init {
        runCatching { wakeWordEngine.configure(wakeProfiles.profiles()) }
        main.post {
            if (destroyed) return@post
            runCatching { TextToSpeech(appContext, this) }
                .onSuccess { engine -> tts = engine }
                .onFailure { error -> listener?.onSpeechDiagnostic("TTS creation failed: ${error.message ?: error::class.simpleName}") }
        }
    }

    override fun attach(listener: SpeechInputListener) { this.listener = listener }

    override fun setListening(mode: SageListeningMode, generation: Long, turnId: Long) {
        main.post {
            if (destroyed) return@post
            this.desiredMode = mode
            this.generation = generation
            this.turnId = turnId
            stopInput()
            localFallbackAttempted = false
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
            runCatching {
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onError(utteranceId: String?) = onDone(utteranceId)
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId != id) return
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
            }.onFailure { listener?.onSpeechDiagnostic("transient TTS failed: ${it.message ?: it::class.simpleName}") }
        }
    }

    override fun onInit(status: Int) {
        main.post {
            if (destroyed) return@post
            runCatching {
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) {
                    tts?.language = Locale.US
                    runCatching { applyLegacyVoiceProfileIfPresent() }
                        .onFailure { listener?.onSpeechDiagnostic("legacy TTS profile failed: ${it.message ?: it::class.simpleName}") }
                    pendingSpeech?.also { pendingSpeech = null; speakNow(it) }
                } else {
                    listener?.onSpeechDiagnostic("TTS initialization failed: $status")
                    pendingSpeech?.also { pendingSpeech = null; it.onComplete() }
                }
            }.onFailure { error ->
                ttsReady = false
                listener?.onSpeechDiagnostic("TTS init callback failed: ${error.message ?: error::class.simpleName}")
                pendingSpeech?.also { pendingSpeech = null; it.onComplete() }
            }
        }
    }

    /** Preserve the owner-selected 1.33.3 Android voice/rate/pitch on an in-place upgrade. */
    private fun applyLegacyVoiceProfileIfPresent() {
        val prefs = appContext.getSharedPreferences("sage_voice_profile", Context.MODE_PRIVATE)
        val values = prefs.all
        val hasLegacyProfile = values.containsKey("voice_name") || values.containsKey("speech_rate") || values.containsKey("speech_pitch")
        if (!hasLegacyProfile) return
        val engine = tts ?: return
        val rate = preferenceFloat(values["speech_rate"], 0.90f).coerceIn(0.5f, 2.0f)
        val pitch = preferenceFloat(values["speech_pitch"], 0.98f).coerceIn(0.5f, 2.0f)
        engine.setSpeechRate(rate)
        engine.setPitch(pitch)
        val requestedVoice = (values["voice_name"] as? String).orEmpty().trim()
        if (requestedVoice.isNotEmpty()) {
            val match = engine.voices?.firstOrNull { it.name == requestedVoice }
            if (match != null) engine.voice = match
            else listener?.onSpeechDiagnostic("Legacy TTS voice '$requestedVoice' is not installed; keeping Android's available voice")
        }
    }

    private fun preferenceFloat(value: Any?, fallback: Float): Float = when (value) {
        is Number -> value.toFloat()
        is String -> value.toFloatOrNull() ?: fallback
        else -> fallback
    }

    private fun speakNow(pending: PendingSpeech) {
        val id = "sage-${pending.turnId}-${UUID.randomUUID()}"
        val completed = AtomicBoolean(false)
        val completeOnce = { if (completed.compareAndSet(false, true)) pending.onComplete() }
        val result = runCatching {
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onError(utteranceId: String?) = onDone(utteranceId)
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == id) main.post { completeOnce() }
                }
            })
            tts?.speak(pending.text, TextToSpeech.QUEUE_FLUSH, null, id) ?: TextToSpeech.ERROR
        }.getOrElse {
            listener?.onSpeechDiagnostic("TTS speak failed: ${it.message ?: it::class.simpleName}")
            TextToSpeech.ERROR
        }
        if (result == TextToSpeech.ERROR) completeOnce()
    }

    private fun startWake(generation: Long) {
        try {
            wakeWordEngine.configure(wakeProfiles.profiles())
            wakeWordEngine.start(generation) { hit -> listener?.onWakeDetected(hit) }
        } catch (t: Throwable) {
            listener?.onSpeechDiagnostic("wake start failed: ${t.message}")
        }
    }

    private fun startRecognition(turnId: Long, generation: Long, allowLocal: Boolean = true) {
        val localComponent = if (allowLocal) SageSherpaRecognitionService.primaryComponent(appContext) else null
        val backend = CommandRecognizerPolicy.choose(
            localSherpaReady = localComponent != null,
            androidOnDeviceAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext),
            androidDefaultAvailable = SpeechRecognizer.isRecognitionAvailable(appContext)
        )
        if (!ensureRecognizer(backend, localComponent)) {
            listener?.onRecognitionError(turnId, generation, SpeechRecognizer.ERROR_CLIENT)
            return
        }
        val session = ++recognitionSession
        recognizer?.setRecognitionListener(SessionRecognitionListener(session, turnId, generation))
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
            handleRecognitionError(session, turnId, generation, SpeechRecognizer.ERROR_CLIENT)
        }
    }

    private fun ensureRecognizer(
        backend: CommandRecognizerBackend,
        localComponent: android.content.ComponentName?
    ): Boolean {
        if (backend == CommandRecognizerBackend.UNAVAILABLE) return false
        if (recognizer != null && recognizerBackend == backend) return true
        recognitionSession += 1
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
        recognizerBackend = CommandRecognizerBackend.UNAVAILABLE
        return try {
            recognizer = when (backend) {
                CommandRecognizerBackend.LOCAL_SHERPA -> SpeechRecognizer.createSpeechRecognizer(
                    appContext,
                    requireNotNull(localComponent)
                )
                CommandRecognizerBackend.ANDROID_ON_DEVICE -> createOnDeviceRecognizer()
                CommandRecognizerBackend.ANDROID_DEFAULT -> SpeechRecognizer.createSpeechRecognizer(appContext)
                CommandRecognizerBackend.UNAVAILABLE -> null
            }
            recognizerBackend = if (recognizer == null) CommandRecognizerBackend.UNAVAILABLE else backend
            recognizer != null
        } catch (t: Throwable) {
            listener?.onSpeechDiagnostic("recognizer creation failed: ${t.message}")
            recognizer = null
            false
        }
    }

    private fun stopInput() {
        try { wakeWordEngine.stop() } catch (_: Throwable) {}
        recognitionSession += 1
        try { recognizer?.cancel() } catch (_: Throwable) {}
    }

    @SuppressLint("NewApi") // The only caller selects this branch after an explicit API 31 check.
    private fun createOnDeviceRecognizer(): SpeechRecognizer {
        return SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
    }

    private fun handleResults(session: Long, capturedTurnId: Long, capturedGeneration: Long, results: Bundle?) {
        if (session != recognitionSession) {
            listener?.onSpeechDiagnostic("ignored stale recognizer result session=$session")
            return
        }
        recognitionSession += 1
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull { it.isNotBlank() }
        if (text == null) {
            listener?.onRecognitionError(capturedTurnId, capturedGeneration, SpeechRecognizer.ERROR_NO_MATCH)
        } else {
            listener?.onSpeechDiagnostic("command recognizer final backend=$recognizerBackend chars=0")
            listener?.onTranscriptFinal(capturedTurnId, capturedGeneration, text)
        }
    }

    private fun handleRecognitionError(
        session: Long,
        capturedTurnId: Long,
        capturedGeneration: Long,
        error: Int
    ) {
        if (session != recognitionSession) {
            listener?.onSpeechDiagnostic("ignored stale recognizer error session=$session code=$error")
            return
        }
        val canFallback = recognizerBackend == CommandRecognizerBackend.LOCAL_SHERPA &&
            !localFallbackAttempted && error in LOCAL_BACKEND_FAILURES &&
            capturedTurnId == turnId && capturedGeneration == generation &&
            desiredMode in setOf(SageListeningMode.COMMAND, SageListeningMode.FOLLOW_UP)
        recognitionSession += 1
        if (canFallback) {
            localFallbackAttempted = true
            listener?.onSpeechDiagnostic("local command recognizer failed code=$error; trying Android once")
            runCatching { recognizer?.cancel() }
            runCatching { recognizer?.destroy() }
            recognizer = null
            recognizerBackend = CommandRecognizerBackend.UNAVAILABLE
            startRecognition(capturedTurnId, capturedGeneration, allowLocal = false)
            return
        }
        listener?.onRecognitionError(capturedTurnId, capturedGeneration, error)
    }

    private inner class SessionRecognitionListener(
        private val session: Long,
        private val capturedTurnId: Long,
        private val capturedGeneration: Long
    ) : RecognitionListener {
        override fun onResults(results: Bundle?) =
            handleResults(session, capturedTurnId, capturedGeneration, results)
        override fun onError(error: Int) =
            handleRecognitionError(session, capturedTurnId, capturedGeneration, error)
        override fun onReadyForSpeech(params: Bundle?) {
            listener?.onSpeechDiagnostic("command recognizer ready backend=$recognizerBackend")
        }
        override fun onBeginningOfSpeech() {
            listener?.onSpeechDiagnostic("command recognizer speech began backend=$recognizerBackend")
        }
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    override fun shutdown() {
        main.post {
            destroyed = true
            stopInput()
            try { wakeWordEngine.close() } catch (_: Throwable) {}
            runCatching { recognizer?.destroy() }; recognizer = null
            recognizerBackend = CommandRecognizerBackend.UNAVAILABLE
            runCatching { tts?.stop() }
            runCatching { tts?.shutdown() }
            tts = null
            pendingSpeech = null
        }
    }

    private data class PendingSpeech(val turnId: Long, val text: String, val onComplete: () -> Unit)

    companion object {
        private val LOCAL_BACKEND_FAILURES = setOf(
            SpeechRecognizer.ERROR_AUDIO,
            SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SpeechRecognizer.ERROR_SERVER,
            ERROR_SERVER_DISCONNECTED_COMPAT
        )
        // API 31's ERROR_SERVER_DISCONNECTED is the inlined value 11. Keeping the compatibility
        // name local avoids resolving the newer framework field on Sage's minSdk 26.
        private const val ERROR_SERVER_DISCONNECTED_COMPAT = 11
    }
}
