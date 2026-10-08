package com.pineapple.sageos2.capability

import com.pineapple.sageos2.action.Direction
import com.pineapple.sageos2.action.FastCommand
import com.pineapple.sageos2.action.MediaAction
import com.pineapple.sageos2.action.VolumeDirection

/** Maps Brain-visible device tools onto the same deterministic commands used by direct owner commands. */
object DeviceCapabilityMapper {
    fun map(action: DeviceAction): FastCommand? = when (action.name) {
        "device.open_app" -> FastCommand.OpenApp(action.required("app"))
        "device.back" -> FastCommand.Back
        "device.home" -> FastCommand.Home
        "device.recents" -> FastCommand.Recents
        "device.notifications" -> FastCommand.Notifications
        "device.quick_settings" -> FastCommand.QuickSettings
        "device.screenshot" -> FastCommand.TakeScreenshot
        "device.read_notifications" -> FastCommand.ReadNotifications
        "device.tap_label" -> FastCommand.TapLabel(action.required("label"))
        "device.scroll" -> FastCommand.Scroll(action.enumValue("direction"))
        "device.swipe" -> FastCommand.Swipe(action.enumValue("direction"))
        "device.tap" -> FastCommand.Tap(action.required("x").toFloat(), action.required("y").toFloat())
        "device.volume" -> FastCommand.Volume(action.enumValue("direction"))
        "device.media" -> FastCommand.Media(action.enumValue("action"))
        "device.set_timer" -> FastCommand.SetTimer(action.required("seconds").toInt())
        "device.set_alarm" -> FastCommand.SetAlarm(
            action.required("hour24").toInt(),
            action.required("minute").toInt()
        )
        else -> null
    }

    private fun DeviceAction.required(key: String): String =
        arguments[key]?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("Missing argument '$key' for $name")

    private inline fun <reified T : Enum<T>> DeviceAction.enumValue(key: String): T =
        enumValueOf<T>(required(key).uppercase())
}
