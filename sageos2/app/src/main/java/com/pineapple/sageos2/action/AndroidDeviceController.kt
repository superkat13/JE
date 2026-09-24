package com.pineapple.sageos2.action

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.media.AudioManager
import android.os.Build
import android.provider.AlarmClock
import android.view.accessibility.AccessibilityNodeInfo
import com.pineapple.sage.SageAccessibilityService
import com.pineapple.sageos2.apps.EmptyOwnerAppProvider
import com.pineapple.sageos2.apps.OwnerAppProvider
import com.pineapple.sageos2.apps.OwnerAppResolver
import com.pineapple.sageos2.brain.BrainModelImportActivity

class AndroidDeviceController(
    private val context: Context,
    private val ownerApps: OwnerAppProvider = EmptyOwnerAppProvider,
    private val appResolver: OwnerAppResolver = OwnerAppResolver(),
    private val diagnosticReportProvider: () -> String = { "Sage diagnostic report is unavailable." }
) : DeviceController {
    override fun execute(command: FastCommand): DeviceControlResult = when (command) {
        is FastCommand.OpenApp -> openApp(command.appName)
        FastCommand.Back -> global(AccessibilityService.GLOBAL_ACTION_BACK, "Back")
        FastCommand.Home -> global(AccessibilityService.GLOBAL_ACTION_HOME, "Home")
        FastCommand.Recents -> global(AccessibilityService.GLOBAL_ACTION_RECENTS, "Recents")
        FastCommand.Notifications -> global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "Notifications")
        FastCommand.QuickSettings -> global(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS, "Quick settings")
        FastCommand.ShareDiagnosticReport -> shareDiagnosticReport()
        FastCommand.ImportBrainModel -> importBrainModel()
        is FastCommand.SetTimer -> setTimer(command.seconds)
        is FastCommand.SetAlarm -> setAlarm(command.hour24, command.minute)
        FastCommand.TakeScreenshot -> takeScreenshot()
        is FastCommand.TapLabel -> tapLabel(command.label)
        is FastCommand.Scroll -> scroll(command.direction)
        is FastCommand.Tap -> tap(command.x, command.y)
        is FastCommand.Swipe -> swipe(command.direction)
        is FastCommand.Volume -> volume(command.direction)
    }

    private fun shareDiagnosticReport(): DeviceControlResult {
        val report = runCatching(diagnosticReportProvider).getOrElse { error ->
            return DeviceControlResult(false, "Diagnostic report failed: ${error.message ?: error.javaClass.simpleName}")
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "SageOS 2 diagnostic report")
            putExtra(Intent.EXTRA_TEXT, report)
        }
        val chooser = Intent.createChooser(send, "Share Sage diagnostic report").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(chooser)
            DeviceControlResult(true, "Diagnostic report ready to share")
        }.getOrElse { error ->
            DeviceControlResult(false, "Could not open Android sharing: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun importBrainModel(): DeviceControlResult = runCatching {
        context.startActivity(Intent(context, BrainModelImportActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        DeviceControlResult(true, "Opened Sage Brain model import")
    }.getOrElse { error ->
        DeviceControlResult(false, "Could not open Brain model import: ${error.message ?: error.javaClass.simpleName}")
    }

    private fun setTimer(seconds: Int): DeviceControlResult {
        if (seconds !in 1..86_400) return DeviceControlResult(false, "Timer must be between 1 second and 24 hours")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            DeviceControlResult(true, "Timer set for ${seconds}s")
        }.getOrElse { error ->
            DeviceControlResult(false, "Android could not open a timer app: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun setAlarm(hour24: Int, minute: Int): DeviceControlResult {
        if (hour24 !in 0..23 || minute !in 0..59) return DeviceControlResult(false, "Alarm time is invalid")
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour24)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            DeviceControlResult(true, "Alarm ready for %02d:%02d".format(hour24, minute))
        }.getOrElse { error ->
            DeviceControlResult(false, "Android could not open an alarm app: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun takeScreenshot(): DeviceControlResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return DeviceControlResult(false, "Android screenshot control requires Android 9 or newer")
        }
        return global(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT, "Screenshot")
    }

    private fun tapLabel(label: String): DeviceControlResult {
        val service = SageAccessibilityService.activeInstance()
            ?: return DeviceControlResult(false, "Accessibility control is not active")
        val root = service.rootInActiveWindow
            ?: return DeviceControlResult(false, "No active window is available")
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        val labels = mutableListOf<String>()
        collectClickableTargets(root, nodes, labels, 300)
        return try {
            when (val selection = SemanticTargetSelector.choose(labels, label)) {
                is SemanticSelection.Match -> {
                    val node = nodes[selection.index]
                    if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        DeviceControlResult(true, "Tapped ${labels[selection.index]}")
                    } else {
                        DeviceControlResult(false, "Android rejected the ${labels[selection.index]} control")
                    }
                }
                is SemanticSelection.Ambiguous -> DeviceControlResult(
                    false,
                    "I found ${selection.count} controls matching $label, so I did not guess"
                )
                SemanticSelection.None -> DeviceControlResult(false, "I could not find a visible clickable control named $label")
            }
        } finally {
            nodes.forEach { runCatching { it.recycle() } }
        }
    }

    private fun collectClickableTargets(
        node: AccessibilityNodeInfo,
        nodes: MutableList<AccessibilityNodeInfo>,
        labels: MutableList<String>,
        limit: Int
    ) {
        if (nodes.size >= limit) return
        if (node.isVisibleToUser && node.isClickable) {
            val label = listOf(
                node.text?.toString().orEmpty(),
                node.contentDescription?.toString().orEmpty(),
                node.viewIdResourceName?.substringAfterLast('/').orEmpty()
            ).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            if (label.isNotEmpty()) {
                nodes += AccessibilityNodeInfo.obtain(node)
                labels += label
                if (nodes.size >= limit) return
            }
        }
        for (index in 0 until node.childCount) {
            if (nodes.size >= limit) break
            val child = node.getChild(index) ?: continue
            try { collectClickableTargets(child, nodes, labels, limit) } finally { child.recycle() }
        }
    }
    private fun openApp(name: String): DeviceControlResult {
        val pm = context.packageManager
        val remembered = appResolver.resolve(name, ownerApps.snapshot())
        if (remembered != null) {
            val launch = pm.getLaunchIntentForPackage(remembered.packageName)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launch)
                return DeviceControlResult(true, "Opened ${remembered.displayName}")
            }
        }

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
            .firstOrNull { (_, label) -> label.equals(name, ignoreCase = true) || label.lowercase().contains(normalized) }
            ?: return DeviceControlResult(false, "I couldn't find an app named $name")

        val packageName = best.first.activityInfo.packageName
        val launch = pm.getLaunchIntentForPackage(packageName)
            ?: return DeviceControlResult(false, "$name does not expose a launcher activity")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
        return DeviceControlResult(true, "Opened ${best.second}")
    }

    private fun global(action: Int, label: String): DeviceControlResult {
        val service = SageAccessibilityService.activeInstance() ?: return DeviceControlResult(false, "Accessibility control is not active")
        return if (service.performGlobalAction(action)) DeviceControlResult(true, label)
        else DeviceControlResult(false, "$label action was rejected by Android")
    }

    private fun scroll(direction: Direction): DeviceControlResult {
        val service = SageAccessibilityService.activeInstance() ?: return DeviceControlResult(false, "Accessibility control is not active")
        val root = service.rootInActiveWindow ?: return DeviceControlResult(false, "No active window is available")
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
            try { if (performScroll(child, action)) return true } finally { child.recycle() }
        }
        return false
    }

    private fun tap(x: Float, y: Float): DeviceControlResult {
        val service = SageAccessibilityService.activeInstance() ?: return DeviceControlResult(false, "Accessibility control is not active")
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 70)).build()
        return if (service.dispatchGesture(gesture, null, null)) DeviceControlResult(true, "Tapped $x, $y")
        else DeviceControlResult(false, "Android rejected the tap gesture")
    }

    private fun swipe(direction: Direction): DeviceControlResult {
        val service = SageAccessibilityService.activeInstance() ?: return DeviceControlResult(false, "Accessibility control is not active")
        val dm = context.resources.displayMetrics
        val width = dm.widthPixels.toFloat(); val height = dm.heightPixels.toFloat(); val cx = width / 2f; val cy = height / 2f
        val marginX = width * 0.2f; val marginY = height * 0.2f
        val path = Path()
        when (direction) {
            Direction.UP -> { path.moveTo(cx, height - marginY); path.lineTo(cx, marginY) }
            Direction.DOWN -> { path.moveTo(cx, marginY); path.lineTo(cx, height - marginY) }
            Direction.LEFT -> { path.moveTo(width - marginX, cy); path.lineTo(marginX, cy) }
            Direction.RIGHT -> { path.moveTo(marginX, cy); path.lineTo(width - marginX, cy) }
        }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 350)).build()
        return if (service.dispatchGesture(gesture, null, null)) DeviceControlResult(true, "Swiped ${direction.name.lowercase()}")
        else DeviceControlResult(false, "Android rejected the swipe gesture")
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
