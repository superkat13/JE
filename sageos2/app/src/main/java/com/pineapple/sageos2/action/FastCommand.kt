package com.pineapple.sageos2.action

sealed interface FastCommand {
    data class OpenApp(val appName: String) : FastCommand
    data object Back : FastCommand
    data object Home : FastCommand
    data object Recents : FastCommand
    data object Notifications : FastCommand
    data object QuickSettings : FastCommand
    data object ShareDiagnosticReport : FastCommand
    data object ImportBrainModel : FastCommand
    data class SetTimer(val seconds: Int) : FastCommand
    data class SetAlarm(val hour24: Int, val minute: Int) : FastCommand
    data object TakeScreenshot : FastCommand
    data object ReadNotifications : FastCommand
    data class Media(val action: MediaAction) : FastCommand
    data class TapLabel(val label: String) : FastCommand
    data class Scroll(val direction: Direction) : FastCommand
    data class Tap(val x: Float, val y: Float) : FastCommand
    data class Swipe(val direction: Direction) : FastCommand
    data class Volume(val direction: VolumeDirection) : FastCommand
}

enum class Direction { UP, DOWN, LEFT, RIGHT }
enum class VolumeDirection { UP, DOWN, MUTE, UNMUTE }
enum class MediaAction { PLAY, PAUSE, NEXT, PREVIOUS }

class FastCommandParser {
    /**
     * Politeness and filler words the owner adds to speech that the exact matcher below cannot see.
     *
     * The command recognizer is a 20M-parameter streaming zipformer, so "play music" and
     * "please play the music" are all equally likely transcripts of the same intent. Only the first
     * matched the original `==` / [Regex.matchEntire] checks, so every polite or determiner-carrying
     * phrasing fell through to the Brain and cost a full prompt prefill on this tablet. The device
     * traces also show short one- and two-character results from the same model, which is the
     * failure mode this tolerance is meant to absorb.
     *
     * These are only ever removed from the outside of a phrase, and only as whole words, so a
     * command's own payload is never altered. "open the youtube app" still resolves its app name
     * after the leading determiner, and no interior word is ever dropped: "play some music" loses
     * only its leading "please". Nothing here makes a match fuzzier than an exact one, so the rule
     * that a phrase must never bypass the Brain unless the fast executor can actually carry it out
     * still holds. An unrecognised phrase returns null exactly as before.
     */
    fun parse(raw: String): FastCommand? =
        parseCanonical(raw) ?: parseCanonical(stripPoliteness(raw))

    /** The original matcher, unchanged, so a tolerant outer layer cannot alter what it accepts. */
    private fun parseCanonical(raw: String): FastCommand? {
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
        if (value == "import brain model" || value == "choose brain model" || value == "load brain model") {
            return FastCommand.ImportBrainModel
        }
        if (value == "take screenshot" || value == "take a screenshot" || value == "screenshot") {
            return FastCommand.TakeScreenshot
        }
        if (value == "read notifications" || value == "what are my notifications" || value == "notification summary") {
            return FastCommand.ReadNotifications
        }
        when (value) {
            "play music", "resume music", "resume media" -> return FastCommand.Media(MediaAction.PLAY)
            "pause music", "pause media" -> return FastCommand.Media(MediaAction.PAUSE)
            "next track", "skip track", "skip song" -> return FastCommand.Media(MediaAction.NEXT)
            "previous track", "previous song", "go back a track" -> return FastCommand.Media(MediaAction.PREVIOUS)
        }
        Regex("(?:set )?(?:a )?timer for (\\d+) (seconds?|secs?|minutes?|mins?|hours?|hrs?)").matchEntire(value)?.let {
            val amount = it.groupValues[1].toIntOrNull() ?: return@let
            val unit = it.groupValues[2]
            val multiplier = when {
                unit.startsWith("sec") -> 1
                unit.startsWith("min") -> 60
                else -> 3600
            }
            val seconds = amount.toLong() * multiplier.toLong()
            if (seconds in 1..86_400) return FastCommand.SetTimer(seconds.toInt())
        }
        Regex("set (?:an )?alarm for (\\d{1,2})(?:[: ](\\d{2}))? ?(am|pm)").matchEntire(value)?.let {
            val rawHour = it.groupValues[1].toIntOrNull() ?: return@let
            val minute = it.groupValues[2].takeIf(String::isNotEmpty)?.toIntOrNull() ?: 0
            if (rawHour in 1..12 && minute in 0..59) {
                val hour24 = (rawHour % 12) + if (it.groupValues[3] == "pm") 12 else 0
                return FastCommand.SetAlarm(hour24, minute)
            }
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
        Regex("(?:tap|press) (.+)").matchEntire(value)?.let {
            val label = it.groupValues[1].trim()
            if (label.isNotEmpty() && label !in setOf("back", "home")) return FastCommand.TapLabel(label)
        }
        return when (value) {
            "volume up", "turn volume up" -> FastCommand.Volume(VolumeDirection.UP)
            "volume down", "turn volume down" -> FastCommand.Volume(VolumeDirection.DOWN)
            "mute", "mute volume" -> FastCommand.Volume(VolumeDirection.MUTE)
            "unmute", "unmute volume" -> FastCommand.Volume(VolumeDirection.UNMUTE)
            else -> null
        }
    }

    /**
     * App names arrive with the determiner and noun the owner actually spoke: "open the youtube
     * app", not "open youtube". Only whole-word leading determiners and a single trailing "app"
     * are removed, so an app genuinely named with one of those words at its edge is not truncated.
     */
    private fun app(name: String): FastCommand? {
        var value = name.trim()
        for (determiner in listOf("the ", "a ", "an ", "my ")) {
            if (value.length > determiner.length && value.startsWith(determiner)) {
                value = value.removePrefix(determiner).trim()
                break
            }
        }
        if (value.length > 4 && value.endsWith(" app")) value = value.removeSuffix(" app").trim()
        return value.takeIf { it.isNotEmpty() }?.let(FastCommand::OpenApp)
    }

    private companion object {
        /**
         * Leading politeness, permission, and determiner words, longest first so that "can you"
         * is consumed before a bare "can". These are stripped only from the start, and only as
         * whole words, and only while the remaining text still looks like a command.
         */
        val LEADING_FILLER = listOf(
            "could you please", "can you please", "would you please", "will you please",
            "can you", "could you", "would you", "will you",
            "please could you", "please can you", "please would you",
            "sage", "hey sage", "ok sage", "okay sage",
            "please", "just", "now", "then", "and",
            "the", "a", "an", "my"
        )

        /** Trailing fillers, matched the same way. */
        val TRAILING_FILLER = listOf("please", "for me", "thanks", "thank you", "now", "okay", "ok")

        private fun stripPoliteness(raw: String): String {
            var value = raw.lowercase().replace(Regex("[^a-z0-9.\\s]"), " ").replace(Regex("\\s+"), " ").trim()
            var changed = true
            while (changed) {
                changed = false
                for (filler in LEADING_FILLER) {
                    if (value.length > filler.length && value.startsWith("$filler ")) {
                        value = value.removePrefix(filler).trim()
                        changed = true
                    }
                }
                for (filler in TRAILING_FILLER) {
                    if (value.length > filler.length && value.endsWith(" $filler")) {
                        value = value.removeSuffix(filler).trim()
                        changed = true
                    }
                }
            }
            return value
        }
    }
}
