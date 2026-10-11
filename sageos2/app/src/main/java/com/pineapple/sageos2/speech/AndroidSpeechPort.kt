package com.pineapple.sageos2.speech

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Message
import android.os.Messenger
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
) : SpeechPort, TextToSpeech.OnInitListener, com.pineapple.sageos2.speech.voicerepair.VoiceRepairCapable {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var listener: SpeechInputListener? = null
    private var recognizer: SpeechRecognizer? = null
    private var recognizerBackend = CommandRecognizerBackend.UNAVAILABLE
    private val sessions = RecognitionSessionGate()
    private val diagnosticSessions = RecognitionSessionGate()
    private var diagnosticOwner: String? = null
    private var diagnosticEpoch = 0L
    private var diagnosticReady = false
    private var diagnosticStopFailed = false
    private var cancelWakeBarrier: (() -> Unit)? = null
    /** Ordinary Push-to-Talk also needs to wait for the isolated wake microphone to stop. */
    private var cancelCommandWakeBarrier: (() -> Unit)? = null
    private var commandWakeBarrierEpoch = 0L
    private var waitingCapture: (() -> Unit)? = null
    private var speechActive = false
    private val deferredSpeech = java.util.ArrayDeque<PendingSpeech>()
    private var localFallbackAttempted = false
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: PendingSpeech? = null
    private var desiredMode = SageListeningMode.OFF
    private var generation = 0L
    private var turnId = 0L
    /** Capture provenance for this exact listening generation, not for a previous wake event. */
    private var wakeTailPresent = true
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

    // Admission and normal input changes share the main looper. An off-main caller cannot
    // synchronously reserve a window ahead of a queued command.
    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == main.looper) action() else main.post { action() }
    }

    override fun setListening(mode: SageListeningMode, generation: Long, turnId: Long) =
        setListening(mode, generation, turnId, wakeTailPresent = true)

    override fun setListening(mode: SageListeningMode, generation: Long, turnId: Long, wakeTailPresent: Boolean) {
        onMain {
            if (destroyed) return@onMain
            val leavingWakeOnly = this.desiredMode == SageListeningMode.WAKE_ONLY
            this.desiredMode = mode
            this.generation = generation
            this.turnId = turnId
            this.wakeTailPresent = wakeTailPresent
            if (diagnosticOwner != null) return@onMain
            stopInput()
            localFallbackAttempted = false
            when (mode) {
                SageListeningMode.OFF -> Unit
                SageListeningMode.WAKE_ONLY -> startWake(generation)
                SageListeningMode.COMMAND, SageListeningMode.FOLLOW_UP -> {
                    if (mode == SageListeningMode.COMMAND && wakeWordEngine is RemoteWakeWordEngine &&
                        (leavingWakeOnly || wakeTailPresent)) {
                        // RemoteWakeWordEngine.stop() only queues an IPC message; it does not
                        // release the other process's AudioRecord before returning. Normal Talk
                        // must not open a second microphone until that remote stop is confirmed.
                        awaitCommandWakeStop(mode, turnId, generation)
                    } else startRecognition(turnId, generation)
                }
            }
        }
    }

    override fun speak(turnId: Long, text: String, onComplete: () -> Unit) {
        main.post {
            if (destroyed) { onComplete(); return@post }
            val pending = PendingSpeech(turnId, text, onComplete)
            if (diagnosticOwner != null) {
                deferredSpeech.addLast(pending)
                return@post
            }
            stopInput()
            if (!ttsReady) { pendingSpeech = pending; return@post }
            speakNow(pending)
        }
    }

    override fun speakTransient(text: String) {
        main.post {
            if (destroyed || !ttsReady || diagnosticOwner != null) return@post
            val resumeMode = desiredMode
            val resumeGeneration = generation
            stopInput()
            speechActive = true
            val id = "transient-${UUID.randomUUID()}"
            runCatching {
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onError(utteranceId: String?) = onDone(utteranceId)
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId != id) return
                        main.post {
                            speechActive = false
                            if (!destroyed && diagnosticOwner == null && desiredMode == resumeMode && generation == resumeGeneration) {
                                when (resumeMode) {
                                    SageListeningMode.WAKE_ONLY -> startWake(resumeGeneration)
                                    SageListeningMode.COMMAND, SageListeningMode.FOLLOW_UP -> startRecognition(turnId, resumeGeneration)
                                    SageListeningMode.OFF -> Unit
                                }
                            }
                        }
                    }
                })
                if (tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.ERROR) {
                    speechActive = false
                }
            }.onFailure {
                speechActive = false
                listener?.onSpeechDiagnostic("transient TTS failed: ${it.message ?: it::class.simpleName}")
            }
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
                    if (diagnosticOwner == null) pendingSpeech?.also { pendingSpeech = null; speakNow(it) }
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
        speechActive = true
        val completeOnce = {
            if (completed.compareAndSet(false, true)) {
                speechActive = false
                pending.onComplete()
                drainDeferredSpeech()
            }
        }
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
        if (destroyed || diagnosticOwner != null) return
        try {
            wakeWordEngine.configure(wakeProfiles.profiles())
            wakeWordEngine.start(generation) { hit -> onMain {
                if (!destroyed && diagnosticOwner == null && desiredMode == SageListeningMode.WAKE_ONLY &&
                    hit.generation == this.generation) listener?.onWakeDetected(hit)
            } }
        } catch (t: Throwable) {
            listener?.onSpeechDiagnostic("wake start failed: ${t.message}")
        }
    }

    private fun startRecognition(turnId: Long, generation: Long, allowLocal: Boolean = true) {
        if (destroyed || diagnosticOwner != null) return
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
        val session = sessions.next()
        recognizer?.setRecognitionListener(SessionRecognitionListener(session, turnId, generation))
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            // Only a true wake-triggered command may discard wake-tail audio. Ordinary Talk and
            // follow-up turns keep all samples, even their first 400ms.
            putExtra(SageSpeechIntents.EXTRA_WAKE_TAIL_PRESENT,
                this@AndroidSpeechPort.wakeTailPresent && desiredMode == SageListeningMode.COMMAND)
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
        sessions.invalidate()
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
        // A mode change, new turn, TTS or shutdown invalidates any delayed STOP reply.
        commandWakeBarrierEpoch++
        cancelCommandWakeBarrier?.invoke()
        cancelCommandWakeBarrier = null
        try { wakeWordEngine.stop() } catch (_: Throwable) {}
        sessions.invalidate()
        try { recognizer?.cancel() } catch (_: Throwable) {}
    }

    private fun awaitCommandWakeStop(mode: SageListeningMode, expectedTurn: Long, expectedGeneration: Long) {
        val epoch = ++commandWakeBarrierEpoch
        val cancel = awaitRemoteWakeStop { stopped ->
            if (destroyed || diagnosticOwner != null || epoch != commandWakeBarrierEpoch ||
                desiredMode != mode || turnId != expectedTurn || generation != expectedGeneration) {
                return@awaitRemoteWakeStop
            }
            cancelCommandWakeBarrier = null
            if (stopped) {
                startRecognition(expectedTurn, expectedGeneration)
            } else {
                // Fail closed. Do not silently capture with two services competing for the mic.
                listener?.onSpeechDiagnostic("command microphone handoff failed or timed out")
                listener?.onRecognitionError(expectedTurn, expectedGeneration, SpeechRecognizer.ERROR_AUDIO)
            }
        }
        if (epoch == commandWakeBarrierEpoch && desiredMode == mode &&
            generation == expectedGeneration && turnId == expectedTurn && !destroyed) {
            cancelCommandWakeBarrier = cancel
        } else cancel()
    }

    @SuppressLint("NewApi") // The only caller selects this branch after an explicit API 31 check.
    private fun createOnDeviceRecognizer(): SpeechRecognizer {
        return SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
    }

    private fun handleResults(session: Long, capturedTurnId: Long, capturedGeneration: Long, results: Bundle?) {
        if (destroyed || diagnosticOwner != null || !sessions.isCurrent(session)) {
            listener?.onSpeechDiagnostic("ignored stale recognizer result session=$session")
            return
        }
        sessions.invalidate()
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull { it.isNotBlank() }
        if (text == null) {
            listener?.onRecognitionError(capturedTurnId, capturedGeneration, SpeechRecognizer.ERROR_NO_MATCH)
        } else {
            listener?.onSpeechDiagnostic("command recognizer final backend=$recognizerBackend nonempty=true")
            listener?.onTranscriptFinal(capturedTurnId, capturedGeneration, text)
        }
    }

    private fun handleRecognitionError(
        session: Long,
        capturedTurnId: Long,
        capturedGeneration: Long,
        error: Int
    ) {
        if (destroyed || diagnosticOwner != null || !sessions.isCurrent(session)) {
            listener?.onSpeechDiagnostic("ignored stale recognizer error session=$session code=$error")
            return
        }
        val canFallback = recognizerBackend == CommandRecognizerBackend.LOCAL_SHERPA &&
            !localFallbackAttempted && error in LOCAL_BACKEND_FAILURES &&
            capturedTurnId == turnId && capturedGeneration == generation &&
            desiredMode in setOf(SageListeningMode.COMMAND, SageListeningMode.FOLLOW_UP)
        sessions.invalidate()
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
            if (destroyed || diagnosticOwner != null || !sessions.isCurrent(session) ||
                capturedTurnId != turnId || capturedGeneration != generation ||
                desiredMode !in setOf(SageListeningMode.COMMAND, SageListeningMode.FOLLOW_UP)) return
            listener?.onSpeechDiagnostic("command recognizer ready backend=$recognizerBackend")
            listener?.onCommandRecognizerReady(capturedTurnId, capturedGeneration)
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

    fun acquireDiagnosticWindow(owner: String): Boolean {
        if (Looper.myLooper() != main.looper || owner.isBlank() || destroyed ||
            diagnosticOwner != null || speechActive || pendingSpeech != null ||
            desiredMode in setOf(SageListeningMode.COMMAND, SageListeningMode.FOLLOW_UP)) return false
        diagnosticOwner = owner
        val epoch = ++diagnosticEpoch
        diagnosticReady = false
        diagnosticStopFailed = false
        sessions.invalidate()
        // Reserve first; capture waits for actual remote STOP acknowledgement, not a delay
        // or the local stop() enqueue. Each lease has its own reply Messenger.
        main.post {
            if (diagnosticOwner != owner || diagnosticEpoch != epoch || destroyed) return@post
            try {
                recognizer?.cancel()
                wakeWordEngine.stop()
                if (wakeWordEngine is RemoteWakeWordEngine) {
                    cancelWakeBarrier = awaitRemoteWakeStop {
                        if (diagnosticOwner == owner && diagnosticEpoch == epoch && !destroyed) {
                            diagnosticReady = it
                            diagnosticStopFailed = !it
                            waitingCapture?.also { capture -> waitingCapture = null; capture() }
                        }
                    }
                } else {
                    diagnosticReady = true // in-process engine.stop() is synchronous
                }
            } catch (_: Exception) {
                diagnosticStopFailed = true
            }
        }
        return true
    }

    /** Uses the existing service STOP protocol with a per-lease acknowledgement channel. */
    private fun awaitRemoteWakeStop(completed: (Boolean) -> Unit): () -> Unit {
        var finished = false
        var bound = false
        lateinit var connection: ServiceConnection
        lateinit var timeout: Runnable
        fun finish(success: Boolean, notify: Boolean = true) {
            if (finished) return
            finished = true
            main.removeCallbacks(timeout)
            if (bound) runCatching { appContext.unbindService(connection) }
            if (notify) completed(success)
        }
        val reply = Messenger(Handler(main.looper) { message ->
            when (message.what) {
                RemoteWakeProtocol.MSG_ACKNOWLEDGED -> if (
                    message.data.getString(RemoteWakeProtocol.KEY_DETAIL) == "remote wake engine stopped"
                ) finish(true)
                RemoteWakeProtocol.MSG_STATUS -> finish(false)
            }
            true
        })
        timeout = Runnable { finish(false) }
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (finished) return
                try {
                    checkNotNull(service)
                    Messenger(service).send(Message.obtain(null, RemoteWakeProtocol.MSG_STOP).apply {
                        replyTo = reply
                        data = Bundle().apply { putLong(RemoteWakeProtocol.KEY_GENERATION, generation) }
                    })
                } catch (_: Exception) { finish(false) }
            }
            override fun onServiceDisconnected(name: ComponentName?) = finish(false)
            override fun onNullBinding(name: ComponentName?) = finish(false)
            override fun onBindingDied(name: ComponentName?) = finish(false)
        }
        main.postDelayed(timeout, 5_000L)
        try {
            bound = appContext.bindService(Intent().setComponent(ComponentName(appContext.packageName,
                "com.pineapple.sageos2.speech.SageWakeRemoteService")), connection, Context.BIND_AUTO_CREATE)
            if (!bound) finish(false)
        } catch (_: Exception) { finish(false) }
        return { finish(false, notify = false) }
    }

    fun captureDiagnosticPhrase(
        owner: String,
        expected: String,
        onReady: () -> Unit = {},
        onSpeechBegan: () -> Unit = {},
        onResult: (com.pineapple.sageos2.speech.voicerepair.VoiceRepairTestResult) -> Unit
    ) {
        val epoch = diagnosticEpoch
        main.post {
            if (destroyed || diagnosticOwner != owner || diagnosticEpoch != epoch) {
                return@post
            }
            if (!diagnosticReady && !diagnosticStopFailed) {
                waitingCapture = { captureDiagnosticPhrase(owner, expected, onReady, onSpeechBegan, onResult) }
                return@post
            }
            val startMs = System.currentTimeMillis()
            val sessionToken = diagnosticSessions.next()
            if (diagnosticStopFailed) {
                diagnosticSessions.invalidate()
                onResult(com.pineapple.sageos2.speech.voicerepair.VoiceRepairTestResult(
                    expected, null, SpeechRecognizer.ERROR_AUDIO, CommandRecognizerBackend.UNAVAILABLE))
                return@post
            }

            val localComponent = SageSherpaRecognitionService.primaryComponent(appContext)
            val backend = CommandRecognizerPolicy.choose(
                localSherpaReady = localComponent != null,
                androidOnDeviceAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext),
                androidDefaultAvailable = SpeechRecognizer.isRecognitionAvailable(appContext)
            )

            if (!ensureRecognizer(backend, localComponent)) {
                diagnosticSessions.invalidate()
                onResult(
                    com.pineapple.sageos2.speech.voicerepair.VoiceRepairTestResult(
                        expected = expected,
                        recognized = null,
                        errorCode = SpeechRecognizer.ERROR_CLIENT,
                        backend = CommandRecognizerBackend.UNAVAILABLE,
                        elapsedMs = System.currentTimeMillis() - startMs,
                        nonEmpty = false
                    )
                )
                return@post
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                // Tells the local sherpa service this capture starts on an already-idle microphone
                // (the wake engine stop was acknowledged), so it must not discard the wake tail —
                // that tail is the beginning of the owner's phrase.
                putExtra(SageSpeechIntents.EXTRA_DIAGNOSTIC_CAPTURE, true)
            }

            try {
                recognizer?.setRecognitionListener(
                    DiagnosticRecognitionListener(sessionToken, owner, expected, startMs, onReady, onSpeechBegan, onResult)
                )
                recognizer?.startListening(intent)
            } catch (t: Throwable) {
                listener?.onSpeechDiagnostic("diagnostic recognizer start failed: ${t.message}")
                if (diagnosticSessions.isCurrent(sessionToken) && diagnosticOwner == owner) {
                    diagnosticSessions.invalidate()
                    onResult(
                        com.pineapple.sageos2.speech.voicerepair.VoiceRepairTestResult(
                            expected = expected,
                            recognized = null,
                            errorCode = SpeechRecognizer.ERROR_CLIENT,
                            backend = recognizerBackend,
                            elapsedMs = System.currentTimeMillis() - startMs,
                            nonEmpty = false
                        )
                    )
                }
            }
        }
    }

    private inner class DiagnosticRecognitionListener(
        private val sessionToken: Long,
        private val owner: String,
        private val expected: String,
        private val startMs: Long,
        private val onReady: () -> Unit,
        private val onSpeechBegan: () -> Unit,
        private val onResult: (com.pineapple.sageos2.speech.voicerepair.VoiceRepairTestResult) -> Unit
    ) : RecognitionListener {
        override fun onResults(results: Bundle?) {
            if (destroyed || !diagnosticSessions.isCurrent(sessionToken) || diagnosticOwner != owner) return
            diagnosticSessions.invalidate()
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull { it.isNotBlank() }
            val elapsed = System.currentTimeMillis() - startMs
            val res = com.pineapple.sageos2.speech.voicerepair.VoiceRepairTestResult(
                expected = expected,
                recognized = text,
                errorCode = if (text.isNullOrBlank()) SpeechRecognizer.ERROR_NO_MATCH else null,
                backend = recognizerBackend,
                elapsedMs = elapsed,
                nonEmpty = !text.isNullOrBlank()
            )
            onResult(res)
        }

        override fun onError(error: Int) {
            if (destroyed || !diagnosticSessions.isCurrent(sessionToken) || diagnosticOwner != owner) return
            diagnosticSessions.invalidate()
            val elapsed = System.currentTimeMillis() - startMs
            val res = com.pineapple.sageos2.speech.voicerepair.VoiceRepairTestResult(
                expected = expected,
                recognized = null,
                errorCode = error,
                backend = recognizerBackend,
                elapsedMs = elapsed,
                nonEmpty = false
            )
            onResult(res)
        }

        override fun onReadyForSpeech(params: Bundle?) {
            if (destroyed || !diagnosticSessions.isCurrent(sessionToken) || diagnosticOwner != owner) return
            listener?.onSpeechDiagnostic("diagnostic recognizer ready backend=$recognizerBackend")
            onReady()
        }
        override fun onBeginningOfSpeech() {
            if (destroyed || !diagnosticSessions.isCurrent(sessionToken) || diagnosticOwner != owner) return
            listener?.onSpeechDiagnostic("diagnostic recognizer speech began backend=$recognizerBackend")
            onSpeechBegan()
        }
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    fun releaseDiagnosticWindow(owner: String) {
        onMain {
            if (diagnosticOwner != owner) return@onMain
            diagnosticOwner = null
            ++diagnosticEpoch
            diagnosticSessions.invalidate()
            waitingCapture = null
            cancelWakeBarrier?.invoke()
            cancelWakeBarrier = null
            runCatching { recognizer?.cancel() }
            if (!drainDeferredSpeech()) resumeListening()
        }
    }

    private fun resumeListening() {
        if (destroyed || diagnosticOwner != null || speechActive || pendingSpeech != null) return
        when (desiredMode) {
            SageListeningMode.WAKE_ONLY -> startWake(generation)
            SageListeningMode.COMMAND, SageListeningMode.FOLLOW_UP -> startRecognition(turnId, generation)
            SageListeningMode.OFF -> Unit
        }
    }

    private fun drainDeferredSpeech(): Boolean {
        if (destroyed || diagnosticOwner != null || speechActive) return false
        val pending = deferredSpeech.pollFirst() ?: return false
        val resumed = pending.copy(onComplete = {
            pending.onComplete()
            if (deferredSpeech.isEmpty()) resumeListening()
        })
        stopInput()
        if (ttsReady) speakNow(resumed) else pendingSpeech = resumed
        return true
    }

    override fun shutdown() {
        onMain {
            destroyed = true
            ++diagnosticEpoch
            waitingCapture = null
            cancelWakeBarrier?.invoke()
            cancelWakeBarrier = null
            diagnosticOwner = null
            diagnosticSessions.invalidate()
            stopInput()
            try { wakeWordEngine.close() } catch (_: Throwable) {}
            runCatching { recognizer?.destroy() }; recognizer = null
            recognizerBackend = CommandRecognizerBackend.UNAVAILABLE
            runCatching { tts?.stop() }
            runCatching { tts?.shutdown() }
            tts = null
            pendingSpeech?.onComplete()
            pendingSpeech = null
            while (deferredSpeech.isNotEmpty()) deferredSpeech.removeFirst().onComplete()
        }
    }

    private data class PendingSpeech(val turnId: Long, val text: String, val onComplete: () -> Unit)

    override fun resetRecognizer(reason: String) {
        resetRecognizer(reason) {}
    }

    override fun resetRecognizer(reason: String, completed: (Boolean) -> Unit) {
        // Ordinary reset cannot interfere with a diagnostic lease.
        resetRecognizerForOwner(null, reason, completed)
    }

    fun resetDiagnosticRecognizer(owner: String, completed: (Boolean) -> Unit) {
        resetRecognizerForOwner(owner, "repair reset", completed)
    }

    private fun resetRecognizerForOwner(owner: String?, reason: String, completed: (Boolean) -> Unit) {
        val epoch = diagnosticEpoch
        main.post {
            if (destroyed || diagnosticOwner != owner || diagnosticEpoch != epoch ||
                (owner != null && (!diagnosticReady || diagnosticStopFailed)) ||
                (owner == null && desiredMode in setOf(SageListeningMode.COMMAND, SageListeningMode.FOLLOW_UP))) {
                completed(false)
                return@post
            }
            listener?.onSpeechDiagnostic("recognizer reset requested: $reason")
            sessions.invalidate()
            diagnosticSessions.invalidate()
            val cancelled = runCatching { recognizer?.cancel() }.isSuccess
            val tornDown = runCatching { recognizer?.destroy() }.isSuccess
            // Retain a failed teardown for a later cleanup attempt; never reuse it as healthy.
            if (tornDown) recognizer = null
            recognizerBackend = CommandRecognizerBackend.UNAVAILABLE
            completed(cancelled && tornDown)
        }
    }

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
