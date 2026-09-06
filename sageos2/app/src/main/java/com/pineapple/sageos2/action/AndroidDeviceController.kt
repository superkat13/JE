package com.pineapple.sageos2.action

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.media.AudioManager
import android.view.accessibility.AccessibilityNodeInfo
import com.pineapple.sage.SageAccessibilityService

class AndroidDeviceController(private val context: Context) : DeviceController {
    override fun execute(command: FastCommand): DeviceControlResult = when (command) {
        is FastCommand.OpenApp -> openApp(command.appName)
        FastCommand.Back -> global(AccessibilityService.GLOBAL_ACTION_BACK, "Back")
        FastCommand.Home -> global(AccessibilityService.GLOBAL_ACTION_HOME, "Home")
        FastCommand.Recents -> global(AccessibilityService.GLOBAL_ACTION_RECENTS, "Recents")
        FastCommand.Notifications -> global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "Notifications")
        FastCommand.QuickSettings -> global(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS, "Quick settings")
        is FastCommand.Scroll -> scroll(command.direction)
        is FastCommand.Tap -> tap(command.x, command.y)
        is FastCommand.Swipe -> swipe(command.direction)
        is FastCommand.Volume -> volume(command.direction)
    }

    private fun openApp(name: String): DeviceControlResult {
        val pm = context.packageManager
        val launcherQuery = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val matches = pm.queryIntentActivities(launcherQuery, 0)
        val normalized = name.lowercase().trim()
        val best = matches
            .map { it to it.loadLabel(pm).toString() }
            .sortedWith(compareBy<Pair<android.content.pm.ResolveInfo, String>> {
                when {
                    it.second.equals(name, ignoreCase = true) -> 0
                    it.second.lowercase().startsWith(normalized) -> 1
                    it.second.lowercase().contains(normalized) -> 2
                    else -> 3
                }
            }.thenBy { it.second.length })
            .firstOrNull { (_, label) ->
                label.equals(name, ignoreCase = true) || label.lowercase().contains(normalized)
            }
            ?: return DeviceControlResult(false, "I couldn't find an app named $name")

        val packageName = best.first.activityInfo.packageName
        val launch = pm.getLaunchIntentForPackage(packageName)
            ?: return DeviceControlResult(false, "$name does not expose a launcher activity")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
        return DeviceControlResult(true, "Opened ${best.second}")
    }

    private fun global(action: Int, label: String): DeviceControlResult {
        val service = SageAccessibilityService.activeInstance()
            ?: return DeviceControlResult(false, "Accessibility control is not active")
        return if (service.performGlobalAction(action)) {
            DeviceControlResult(true, label)
        } else {
            DeviceControlResult(false, "$label action was rejected by Android")
        }
    }

    private fun scroll(direction: Direction): DeviceControlResult {
        val service = SageAccessibilityService.activeInstance()
            ?: return DeviceControlResult(false, "Accessibility control is not active")
        val root = service.rootInActiveWindow
            ?: return DeviceControlResult(false, "No active window is available")
        val action = when (direction) {
            Direction.DOWN, Direction.RIGHT -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            Direction.UP, Direction.LEFT -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        if (performScroll(root, action)) return DeviceControlResult(true, "Scrolled ${direction.name.lowercase()}")
        return swipe(direction)
    }

    private fun performScroll(node: AccessibilityNodeInfo, action: Int): Boolean {
        if (node.isScrollable && node.performAction(action)) return true
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try {
                if (performScroll(child, action)) return true
            } finally {
                child.recycle()
            }
        }
        return false
    }

    private fun tap(x: Float, y: Float): DeviceControlResult {
        val service = SageAccessibilityService.activeInstance()
            ?: return DeviceControlResult(false, "Accessibility control is not active")
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 70))
            .build()
        return if (service.dispatchGesture(gesture, null, null)) {
            DeviceControlResult(true, "Tapped $x, $y")
        } else DeviceControlResult(false, "Android rejected the tap gesture")
    }

    private fun swipe(direction: Direction): DeviceControlResult {
        val service = SageAccessibilityService.activeInstance()
            ?: return DeviceControlResult(false, "Accessibility control is not active")
        val dm = context.resources.displayMetrics
        val width = dm.widthPixels.toFloat()
        val height = dm.heightPixels.toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val marginX = width * 0.2f
        val marginY = height * 0.2f
        val path = Path()
        when (direction) {
            Direction.UP -> { path.moveTo(cx, height - marginY); path.lineTo(cx, marginY) }
            Direction.DOWN -> { path.moveTo(cx, marginY); path.lineTo(cx, height - marginY) }
            Direction.LEFT -> { path.moveTo(width - marginX, cy); path.lineTo(marginX, cy) }
            Direction.RIGHT -> { path.moveTo(marginX, cy); path.lineTo(width - marginX, cy) }
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 350))
            .build()
        return if (service.dispatchGesture(gesture, null, null)) {
            DeviceControlResult(true, "Swiped ${direction.name.lowercase()}")
        } else DeviceControlResult(false, "Android rejected the swipe gesture")
    }

    private fun volume(direction: VolumeDirection): DeviceControlResult {
        val audio = context.getSystemService(AudioManager::class.java)
        when (direction) {
            VolumeDirection.UP -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            VolumeDirection.DOWN -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            VolumeDirection.MUTE -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
            VolumeDirection.UNMUTE -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
        }
        return DeviceControlResult(true, "Volume ${direction.name.lowercase()}")
    }
}
