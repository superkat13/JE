package com.pineapple.sage

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.admin.DeviceAdminReceiver
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.service.notification.NotificationListenerService
import android.view.accessibility.AccessibilityEvent
import com.pineapple.sageos2.MainActivity as SageOsMainActivity
import com.pineapple.sageos2.runtime.SageRuntimeHost
import java.lang.ref.WeakReference

class MainActivity : SageOsMainActivity()
class SageDeviceAdminReceiver : DeviceAdminReceiver()

class SageAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() { super.onServiceConnected(); current = WeakReference(this) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun onDestroy() { if (current?.get() === this) current = null; super.onDestroy() }

    companion object {
        @Volatile private var current: WeakReference<SageAccessibilityService>? = null
        fun activeInstance(): SageAccessibilityService? = current?.get()
    }
}

class SageNotificationListener : NotificationListenerService()

class SageBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        runCatching { context.startForegroundService(Intent(context, SageVoiceService::class.java)) }
    }
}

class SageAssistActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, SageOsMainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}

class SageVoiceService : Service() {
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Sage runtime", NotificationManager.IMPORTANCE_LOW))
        }
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION") android.app.Notification.Builder(this)
        }
            .setContentTitle("Sage")
            .setContentText("Virtual twin runtime active")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        SageRuntimeHost.get(this).start()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        SageRuntimeHost.get(this).start()
        return START_STICKY
    }

    companion object {
        private const val CHANNEL = "sage_runtime"
        private const val NOTIFICATION_ID = 2202
    }
}
