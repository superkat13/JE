package com.pineapple.sage

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.Service
import android.app.admin.DeviceAdminReceiver
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.service.notification.NotificationListenerService
import android.view.accessibility.AccessibilityEvent
import com.pineapple.sageos2.MainActivity as SageOsMainActivity
import java.lang.ref.WeakReference

class MainActivity : SageOsMainActivity()
class SageDeviceAdminReceiver : DeviceAdminReceiver()

class SageAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        current = WeakReference(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    companion object {
        @Volatile private var current: WeakReference<SageAccessibilityService>? = null
        fun activeInstance(): SageAccessibilityService? = current?.get()
    }
}

class SageNotificationListener : NotificationListenerService()

class SageBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Runtime boot continuation is connected after the service lifecycle gate.
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
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
