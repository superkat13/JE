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
import android.view.accessibility.AccessibilityEvent
import android.service.notification.NotificationListenerService
import com.pineapple.sageos2.MainActivity as SageOsMainActivity

/**
 * Compatibility components whose fully-qualified names existed in Sage 1.33.3.
 * Keep these names stable across the in-place SageOS 2 migration so Android can
 * preserve package/component-scoped owner grants. Behavior is intentionally thin
 * until each new SageOS 2 adapter is wired and tested.
 */
class MainActivity : SageOsMainActivity()

class SageDeviceAdminReceiver : DeviceAdminReceiver()

class SageAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
}

class SageNotificationListener : NotificationListenerService()

class SageBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Boot continuity is connected only after the new runtime service passes
        // its lifecycle tests. Keeping the receiver identity now preserves the path.
    }
}

class SageAssistActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, SageOsMainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
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
