package com.pineapple.sageos2.speech

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.os.SystemClock

/**
 * Main-process wake client. It deliberately contains no Sherpa/ONNX types and does not resolve
 * the remote service class, so loading this class cannot initialize native wake code in the Sage
 * cockpit process.
 */
class RemoteWakeWordEngine(
    context: Context,
    private val onDiagnostic: (String) -> Unit = {}
) : WakeWordEngine {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var callbackMessenger: Messenger? = null
    private var connectionEpoch = 0L
    private var healthTimeout: Runnable? = null
    private val recovery = WakeRecoveryBudget()

    @Volatile private var profiles: List<WakeProfile> = SharedPreferencesWakeProfileStore.defaults()
    @Volatile private var remote: Messenger? = null
    @Volatile private var bound = false
    @Volatile private var binding = false
    @Volatile private var desiredRunning = false
    @Volatile private var currentGeneration = 0L
    @Volatile private var wakeCallback: ((WakeHit) -> Unit)? = null
    @Volatile private var lastReady = false
    @Volatile private var lastDetail = "remote wake process not started"
    private var reconnectRunnable: Runnable? = null

    private var connection: ServiceConnection? = null

    private fun newConnection(epoch: Long): ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (epoch != connectionEpoch) return
            binding = false
            bound = true
            remote = service?.let(::Messenger)
            if (remote == null) {
                handleRemoteFailure("remote wake service returned no binder")
                return
            }
            lastReady = false
            lastDetail = "remote wake process connected; waiting for audio readiness"
            sendConfiguration()
            if (desiredRunning) sendStart()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            if (epoch == connectionEpoch) handleRemoteFailure("remote wake process disconnected")
        }

        override fun onBindingDied(name: ComponentName?) {
            if (epoch == connectionEpoch) handleRemoteFailure("remote wake process died")
        }

        override fun onNullBinding(name: ComponentName?) {
            if (epoch == connectionEpoch) handleRemoteFailure("remote wake service returned no binder")
        }
    }

    override fun configure(profiles: List<WakeProfile>) {
        val changed = this.profiles != profiles
        this.profiles = profiles
        if (changed) main.post { recovery.reset() }
        if (bound) main.post { sendConfiguration() }
    }

    override fun start(generation: Long, onWake: (WakeHit) -> Unit) {
        require(appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "microphone permission is not granted"
        }
        currentGeneration = generation
        wakeCallback = onWake
        desiredRunning = true
        main.post { ensureRemoteProcess() }
    }

    override fun stop() {
        desiredRunning = false
        wakeCallback = null
        main.post {
            cancelReconnect()
            cancelHealthTimeout()
            recovery.interrupted()
            lastReady = false
            lastDetail = "wake listening intentionally stopped"
            send(RemoteWakeProtocol.MSG_STOP)
        }
    }

    override fun health(): WakeWordHealth {
        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return WakeWordHealth(false, ENGINE, "microphone permission is not granted")
        }
        val compiledCount = profiles.filter { it.enabled }.sumOf { profile ->
            profile.phrases.count { profile.compiledTokensFor(it) != null }
        }
        if (compiledCount == 0) return WakeWordHealth(false, ENGINE, "no wake phrases have compiled BPE tokens")
        val evidence = recovery.lastFailure.takeIf { it.isNotBlank() }?.let { "; last failure: $it" }.orEmpty()
        return WakeWordHealth((lastReady || !desiredRunning) && !recovery.paused, ENGINE, lastDetail + evidence)
    }

    override fun close() {
        desiredRunning = false
        wakeCallback = null
        main.post {
            cancelReconnect()
            send(RemoteWakeProtocol.MSG_CLOSE)
            if (bound || binding) runCatching { connection?.let(appContext::unbindService) }
            bound = false
            binding = false
            remote = null
            cancelHealthTimeout()
            connectionEpoch++
            recovery.reset()
            runCatching { appContext.stopService(remoteServiceIntent()) }
        }
    }

    private fun ensureRemoteProcess() {
        if (recovery.paused) {
            lastReady = false
            lastDetail = "remote wake automatic recovery paused after ${recovery.attempts} failed attempts"
            return
        }
        if (!desiredRunning || reconnectRunnable != null) return
        if (bound) {
            lastReady = false
            recovery.interrupted()
            armHealthTimeout(START_TIMEOUT_MS)
            sendConfiguration()
            sendStart()
            return
        }
        if (binding) return
        binding = true
        val epoch = ++connectionEpoch
        callbackMessenger = Messenger(Handler(Looper.getMainLooper()) { message ->
            if (epoch == connectionEpoch) handleRemoteMessage(message) else true
        })
        armHealthTimeout(START_TIMEOUT_MS)
        lastDetail = "starting isolated wake process"
        val intent = remoteServiceIntent()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) appContext.startForegroundService(intent)
            else appContext.startService(intent)
            val newConnection = newConnection(epoch)
            connection = newConnection
            val ok = appContext.bindService(intent, newConnection, Context.BIND_AUTO_CREATE)
            if (!ok) {
                binding = false
                handleRemoteFailure("Android refused to bind remote wake service")
            }
        } catch (t: Throwable) {
            binding = false
            handleRemoteFailure("remote wake start failed: ${t.message ?: t::class.java.simpleName}")
        }
    }

    private fun handleRemoteFailure(detail: String) {
        cancelHealthTimeout()
        recovery.interrupted()
        connectionEpoch++
        if (bound || binding) runCatching { connection?.let(appContext::unbindService) }
        runCatching { appContext.stopService(remoteServiceIntent()) }
        connection = null
        remote = null
        bound = false
        binding = false
        lastReady = false
        lastDetail = detail
        runCatching { onDiagnostic("wake failure: $detail") }
        if (!desiredRunning) return
        scheduleReconnect(detail)
    }

    private fun scheduleReconnect(reason: String) {
        if (!desiredRunning || recovery.paused || reconnectRunnable != null) return
        val delay = recovery.failed(reason)
        val nextAttempt = recovery.attempts
        if (delay == null) {
            lastReady = false
            lastDetail = "$reason; automatic recovery paused after ${recovery.attempts} failed attempts"
            runCatching { onDiagnostic(lastDetail) }
            return
        }
        lastDetail = "$reason; wake recovery attempt $nextAttempt/${WakeReconnectPolicy.MAX_ATTEMPTS} in ${delay}ms"
        val runnable = Runnable {
            reconnectRunnable = null
            if (!desiredRunning || recovery.paused || bound || binding) return@Runnable
            runCatching { appContext.stopService(remoteServiceIntent()) }
            ensureRemoteProcess()
        }
        reconnectRunnable = runnable
        main.postDelayed(runnable, delay)
    }

    private fun cancelHealthTimeout() {
        healthTimeout?.let(main::removeCallbacks)
        healthTimeout = null
    }

    private fun armHealthTimeout(delayMs: Long) {
        cancelHealthTimeout()
        val timeout = Runnable {
            healthTimeout = null
            if (desiredRunning) handleRemoteFailure("wake audio health timed out after ${delayMs}ms")
        }
        healthTimeout = timeout
        main.postDelayed(timeout, delayMs)
    }

    private fun cancelReconnect() {
        reconnectRunnable?.let(main::removeCallbacks)
        reconnectRunnable = null
    }

    private fun remoteServiceIntent(): Intent = Intent().setComponent(
        ComponentName(appContext.packageName, REMOTE_SERVICE_CLASS)
    )

    private fun sendConfiguration() {
        val data = Bundle().apply {
            putString(RemoteWakeProtocol.KEY_PROFILES, RemoteWakeProtocol.encodeProfiles(profiles))
        }
        send(RemoteWakeProtocol.MSG_CONFIGURE, data)
    }

    private fun sendStart() {
        val data = Bundle().apply { putLong(RemoteWakeProtocol.KEY_GENERATION, currentGeneration) }
        send(RemoteWakeProtocol.MSG_START, data)
    }

    private fun send(what: Int, data: Bundle = Bundle.EMPTY) {
        val target = remote ?: return
        try {
            target.send(Message.obtain(null, what).apply {
                this.data = Bundle(data).apply { putLong(RemoteWakeProtocol.KEY_GENERATION, currentGeneration) }
                replyTo = callbackMessenger
            })
        } catch (e: RemoteException) {
            handleRemoteFailure("remote wake IPC failed: ${e.message ?: "binder disconnected"}")
        }
    }

    private fun handleRemoteMessage(message: Message): Boolean {
        when (message.what) {
            RemoteWakeProtocol.MSG_WAKE_HIT -> {
                val generation = message.data.getLong(RemoteWakeProtocol.KEY_GENERATION)
                if (generation != currentGeneration || !desiredRunning) return true
                val profileId = message.data.getString(RemoteWakeProtocol.KEY_PROFILE_ID).orEmpty()
                val modeId = message.data.getString(RemoteWakeProtocol.KEY_MODE_ID)?.takeIf { it.isNotBlank() }
                val acknowledgement = message.data.getString(RemoteWakeProtocol.KEY_ACK).orEmpty().ifBlank { "Yes" }
                desiredRunning = false
                cancelHealthTimeout()
                recovery.interrupted()
                val callback = wakeCallback
                wakeCallback = null
                callback?.invoke(WakeHit(generation, profileId, modeId, acknowledgement))
                return true
            }
            RemoteWakeProtocol.MSG_ACKNOWLEDGED -> return true
            RemoteWakeProtocol.MSG_STATUS -> {
                if (!desiredRunning || recovery.paused ||
                    message.data.getLong(RemoteWakeProtocol.KEY_GENERATION) != currentGeneration) return true
                val ready = message.data.getBoolean(RemoteWakeProtocol.KEY_READY)
                val detail = message.data.getString(RemoteWakeProtocol.KEY_DETAIL).orEmpty()
                    .ifBlank { "remote wake status updated" }
                lastReady = ready
                lastDetail = detail
                if (ready) {
                    val previousAttempts = recovery.attempts
                    recovery.listening(SystemClock.elapsedRealtime())
                    if (previousAttempts > 0 && recovery.attempts == 0) {
                        runCatching { onDiagnostic("wake recovery confirmed by sustained audio progress") }
                    }
                    armHealthTimeout(HEALTH_TIMEOUT_MS)
                } else {
                    handleRemoteFailure(detail)
                }
                return true
            }
        }
        return false
    }

    companion object {
        private const val START_TIMEOUT_MS = 30_000L
        private const val HEALTH_TIMEOUT_MS = 10_000L
        private const val ENGINE = "sherpa-onnx-kws-remote-1.13.7"
        private const val REMOTE_SERVICE_CLASS = "com.pineapple.sageos2.speech.SageWakeRemoteService"
    }
}
