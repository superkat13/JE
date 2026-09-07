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

/**
 * Main-process wake client. It deliberately contains no Sherpa/ONNX types and does not resolve
 * the remote service class, so loading this class cannot initialize native wake code in the Sage
 * cockpit process.
 */
class RemoteWakeWordEngine(context: Context) : WakeWordEngine {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val callbackMessenger = Messenger(Handler(Looper.getMainLooper(), ::handleRemoteMessage))

    @Volatile private var profiles: List<WakeProfile> = SharedPreferencesWakeProfileStore.defaults()
    @Volatile private var remote: Messenger? = null
    @Volatile private var bound = false
    @Volatile private var binding = false
    @Volatile private var desiredRunning = false
    @Volatile private var currentGeneration = 0L
    @Volatile private var wakeCallback: ((WakeHit) -> Unit)? = null
    @Volatile private var lastReady = false
    @Volatile private var lastDetail = "remote wake process not started"

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binding = false
            bound = true
            remote = service?.let(::Messenger)
            lastReady = true
            lastDetail = "remote wake process connected"
            sendConfiguration()
            if (desiredRunning) sendStart()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
            bound = false
            binding = false
            lastReady = false
            lastDetail = "remote wake process disconnected"
        }

        override fun onBindingDied(name: ComponentName?) {
            remote = null
            bound = false
            binding = false
            lastReady = false
            lastDetail = "remote wake process died"
            runCatching { appContext.unbindService(this) }
        }

        override fun onNullBinding(name: ComponentName?) {
            remote = null
            bound = false
            binding = false
            lastReady = false
            lastDetail = "remote wake service returned no binder"
        }
    }

    override fun configure(profiles: List<WakeProfile>) {
        this.profiles = profiles
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
        main.post { send(RemoteWakeProtocol.MSG_STOP) }
    }

    override fun health(): WakeWordHealth {
        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return WakeWordHealth(false, ENGINE, "microphone permission is not granted")
        }
        val compiledCount = profiles.filter { it.enabled }.sumOf { profile ->
            profile.phrases.count { profile.compiledTokensFor(it) != null }
        }
        if (compiledCount == 0) return WakeWordHealth(false, ENGINE, "no wake phrases have compiled BPE tokens")
        return WakeWordHealth(lastReady || !desiredRunning, ENGINE, lastDetail)
    }

    override fun close() {
        desiredRunning = false
        wakeCallback = null
        main.post {
            send(RemoteWakeProtocol.MSG_CLOSE)
            if (bound || binding) runCatching { appContext.unbindService(connection) }
            bound = false
            binding = false
            remote = null
            runCatching { appContext.stopService(remoteServiceIntent()) }
        }
    }

    private fun ensureRemoteProcess() {
        if (bound) {
            sendConfiguration()
            sendStart()
            return
        }
        if (binding) return
        binding = true
        lastDetail = "starting isolated wake process"
        val intent = remoteServiceIntent()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) appContext.startForegroundService(intent)
            else appContext.startService(intent)
            val ok = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            if (!ok) {
                binding = false
                lastReady = false
                lastDetail = "Android refused to bind remote wake service"
            }
        } catch (t: Throwable) {
            binding = false
            lastReady = false
            lastDetail = "remote wake start failed: ${t.message ?: t::class.java.simpleName}"
        }
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
                this.data = data
                replyTo = callbackMessenger
            })
        } catch (e: RemoteException) {
            remote = null
            bound = false
            lastReady = false
            lastDetail = "remote wake IPC failed: ${e.message ?: "binder disconnected"}"
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
                wakeCallback?.invoke(WakeHit(generation, profileId, modeId, acknowledgement))
                wakeCallback = null
                return true
            }
            RemoteWakeProtocol.MSG_STATUS -> {
                lastReady = message.data.getBoolean(RemoteWakeProtocol.KEY_READY)
                lastDetail = message.data.getString(RemoteWakeProtocol.KEY_DETAIL).orEmpty().ifBlank { "remote wake status updated" }
                return true
            }
        }
        return false
    }

    companion object {
        private const val ENGINE = "sherpa-onnx-kws-remote-1.13.7"
        private const val REMOTE_SERVICE_CLASS = "com.pineapple.sageos2.speech.SageWakeRemoteService"
    }
}
