package com.pineapple.sageos2.action

sealed interface FastCommand {
    data class OpenApp(val appName: String) : FastCommand
    data object Back : FastCommand
    data object Home : FastCommand
    data object Recents : FastCommand
    data object Notifications : FastCommand
    data object QuickSettings : FastCommand
    data object ShareDiagnosticReport : FastCommand
    data class Scroll(val direction: Direction) : FastCommand
    data class Tap(val x: Float, val y: Float) : FastCommand
    data class Swipe(val direction: Direction) : FastCommand
    data class Volume(val direction: VolumeDirection) : FastCommand
}

enum class Direction { UP, DOWN, LEFT, RIGHT }
enum class VolumeDirection { UP, DOWN, MUTE, UNMUTE }

class FastCommandParser {
    fun parse(raw: String): FastCommand? {
        val value = raw.lowercase().replace(Regex("\\s+"), " ").trim()
        if (value.startsWith("open ")) return app(value.removePrefix("open "))
        if (value.startsWith("launch ")) return app(value.removePrefix("launch "))
        if (value == "go back" || value == "back" || value == "press back") return FastCommand.Back
        if (value == "go home" || value == "home" || value == "press home") return FastCommand.Home
        if (value == "recents" || value == "recent apps" || value == "show recents") return FastCommand.Recents
        if (value == "show notifications" || value == "notifications") return FastCommand.Notifications
        if (value == "quick settings" || value == "show quick settings") return FastCommand.QuickSettings
        if (value == "share diagnostic report" || value == "share sage diagnostic report" || value == "send diagnostic report") {
            return FastCommand.ShareDiagnosticReport
        }

        Regex("scroll (up|down|left|right)").matchEntire(value)?.let {
            return FastCommand.Scroll(Direction.valueOf(it.groupValues[1].uppercase()))
        }
        Regex("swipe (up|down|left|right)").matchEntire(value)?.let {
            return FastCommand.Swipe(Direction.valueOf(it.groupValues[1].uppercase()))
        }
        Regex("tap (?:at )?(\\d+(?:\\.\\d+)?)[, ]+(\\d+(?:\\.\\d+)?)").matchEntire(value)?.let {
            return FastCommand.Tap(it.groupValues[1].toFloat(), it.groupValues[2].toFloat())
        }
        return when (value) {
            "volume up", "turn volume up" -> FastCommand.Volume(VolumeDirection.UP)
            "volume down", "turn volume down" -> FastCommand.Volume(VolumeDirection.DOWN)
            "mute", "mute volume" -> FastCommand.Volume(VolumeDirection.MUTE)
            "unmute", "unmute volume" -> FastCommand.Volume(VolumeDirection.UNMUTE)
            else -> null
        }
    }

    private fun app(name: String): FastCommand? = name.trim().takeIf { it.isNotEmpty() }?.let(FastCommand::OpenApp)
}
