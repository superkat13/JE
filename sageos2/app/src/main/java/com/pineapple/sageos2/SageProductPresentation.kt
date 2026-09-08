package com.pineapple.sageos2

enum class SageDestination {
    MEMORIES,
    APPS,
    VOICE,
    APPEARANCE,
    ADVANCED,
    CORE,
    TASKS,
    WORKFLOWS,
    LOCAL_REPLIES,
    DIAGNOSTICS
}

data class SageSettingsItem(
    val title: String,
    val description: String,
    val destination: SageDestination
)

data class SageStarter(
    val label: String,
    val message: String
)

/** The small, owner-facing product vocabulary shown before Advanced is opened. */
object SageProductPresentation {
    const val HOME_GREETING =
        "I'm here. Talk to me the way you always have—type anything below, or tap Talk and say it out loud."

    const val SETTINGS_INTRO =
        "Keep the things that make Sage yours in one place. You don't need to configure anything here before talking to her."

    const val ADVANCED_INTRO =
        "Setup, recovery, and engineering details live here. Everyday Sage does not depend on you managing these screens."

    val starters = listOf(
        SageStarter("How do I use Sage?", "What can you do?"),
        SageStarter("What do you remember?", "What do you remember about me?"),
        SageStarter("Think with me", "Help me think something through")
    )

    val settings = listOf(
        SageSettingsItem("What Sage remembers", "Your shared memories and phrases Sage has learned", SageDestination.MEMORIES),
        SageSettingsItem("Apps Sage knows", "The app names and purposes that are familiar to Sage", SageDestination.APPS),
        SageSettingsItem("Voice & wake", "How Sage listens, answers, and changes tone", SageDestination.VOICE),
        SageSettingsItem("Appearance", "Your saved Sage look and background", SageDestination.APPEARANCE),
        SageSettingsItem("Advanced", "Setup and repair controls for when you actually need them", SageDestination.ADVANCED)
    )

    val advancedSettings = listOf(
        SageSettingsItem("Identity & continuity", "Sage Core and its saved history", SageDestination.CORE),
        SageSettingsItem("Recoverable work", "Task checkpoints that can be resumed safely", SageDestination.TASKS),
        SageSettingsItem("Workflows & scopes", "Owner-defined workflow configuration", SageDestination.WORKFLOWS),
        SageSettingsItem("Local replies & device access", "Local GGUF status and capability state", SageDestination.LOCAL_REPLIES),
        SageSettingsItem("Diagnostics", "Detailed evidence for troubleshooting", SageDestination.DIAGNOSTICS)
    )
}
