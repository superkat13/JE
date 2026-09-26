package com.pineapple.sageos2.speech

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.SystemClock

/**
 * Native Sherpa/ONNX wake detection lives in its own process.
 * A native wake failure must not be able to kill the Sage cockpit process.
 */
class SageWakeRemoteService : Service() {
    private val engine by lazy { SherpaWakeWordEngine(this) }
    private val incoming = Messenger(Handler(Looper.getMainLooper(), ::handleMessage))
    private var client: Messenger? = null
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0L
    private var monitor: Runnable? = null

    private fun stopMonitoring() {
        monitor?.let(main::removeCallbacks)
        monitor = null
    }

    private fun monitorAudio(activeGeneration: Long) {
        stopMonitoring()
        val started = SystemClock.elapsedRealtime()
        val task = object : Runnable {
            override fun run() {
                if (monitor !== this) return
                val health = engine.audioHealth()
                if (health.ready) sendStatus(true, health.detail, activeGeneration)
                else if (engine.hasAudioFailure() || SystemClock.elapsedRealtime() - started >= 8_000L) {
                    sendStatus(false, health.detail, activeGeneration)
                    stopMonitoring()
                    return
                }
                main.postDelayed(this, 2_000L)
            }
        }
        monitor = task
        main.post(task)
    }

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
    }

    override fun onBind(intent: Intent?): IBinder = incoming.binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        stopMonitoring()
        runCatching { engine.close() }
        client = null
        super.onDestroy()
    }

    private fun handleMessage(message: Message): Boolean {
        client = message.replyTo ?: client
        generation = message.data.getLong(RemoteWakeProtocol.KEY_GENERATION)
        return try {
            when (message.what) {
                RemoteWakeProtocol.MSG_CONFIGURE -> {
                    val raw = message.data.getString(RemoteWakeProtocol.KEY_PROFILES).orEmpty()
                    engine.configure(RemoteWakeProtocol.decodeProfiles(raw))
                    sendAcknowledgement("remote wake profiles configured")
                }
                RemoteWakeProtocol.MSG_START -> {
                    val generation = message.data.getLong(RemoteWakeProtocol.KEY_GENERATION)
                    stopMonitoring()
                    engine.start(generation) { hit -> main.post {
                        if (hit.generation == this.generation) {
                            stopMonitoring()
                            sendWake(hit)
                        }
                    } }
                    monitorAudio(generation)
                }
                RemoteWakeProtocol.MSG_STOP -> {
                    stopMonitoring()
                    engine.stop()
                    sendAcknowledgement("remote wake engine stopped")
                }
                RemoteWakeProtocol.MSG_CLOSE -> {
                    stopMonitoring()
                    engine.close()
                    sendAcknowledgement("remote wake engine closed")
                    stopSelf()
                }
                else -> return false
            }
            true
        } catch (t: Throwable) {
            stopMonitoring()
            sendStatus(false, "remote wake failure: ${t.message ?: t::class.java.simpleName}")
            true
        }
    }

    private fun sendWake(hit: WakeHit) {
        val target = client ?: return
        val data = Bundle().apply {
            putLong(RemoteWakeProtocol.KEY_GENERATION, hit.generation)
            putString(RemoteWakeProtocol.KEY_PROFILE_ID, hit.profileId)
            putString(RemoteWakeProtocol.KEY_MODE_ID, hit.modeId)
            putString(RemoteWakeProtocol.KEY_ACK, hit.acknowledgement)
        }
        runCatching {
            target.send(Message.obtain(null, RemoteWakeProtocol.MSG_WAKE_HIT).apply { this.data = data })
        }
    }

    private fun sendAcknowledgement(detail: String) {
        val target = client ?: return
        runCatching {
            target.send(Message.obtain(null, RemoteWakeProtocol.MSG_ACKNOWLEDGED).apply {
                data = Bundle().apply { putString(RemoteWakeProtocol.KEY_DETAIL, detail) }
            })
        }
    }

    private fun sendStatus(ready: Boolean, detail: String, statusGeneration: Long = generation) {
        val target = client ?: return
        val data = Bundle().apply {
            putLong(RemoteWakeProtocol.KEY_GENERATION, statusGeneration)
            putBoolean(RemoteWakeProtocol.KEY_READY, ready)
            putString(RemoteWakeProtocol.KEY_DETAIL, detail)
        }
        runCatching {
            target.send(Message.obtain(null, RemoteWakeProtocol.MSG_STATUS).apply { this.data = data })
        }
    }

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Sage wake", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION") android.app.Notification.Builder(this)
        }
        val notification = builder
            .setContentTitle("Sage")
            .setContentText("Offline wake listening")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val CHANNEL = "sage_wake_remote"
        private const val NOTIFICATION_ID = 2203
    }
}
