package com.pineapple.sageos2.core

import com.pineapple.sageos2.action.FastCommand
import com.pineapple.sageos2.action.FastCommandParser
import com.pineapple.sageos2.apps.EmptyOwnerAppProvider
import com.pineapple.sageos2.apps.OwnerAppProvider
import com.pineapple.sageos2.apps.OwnerAppResolver
import com.pineapple.sageos2.personal.EmptySagePersonalResponder
import com.pineapple.sageos2.personal.SagePersonalResolution
import com.pineapple.sageos2.personal.SagePersonalResponder
import java.util.Locale

class SageCommandRouter(
    private val personal: SagePersonalResponder = EmptySagePersonalResponder,
    private val fastCommands: FastCommandParser = FastCommandParser(),
    private val ownerApps: OwnerAppProvider = EmptyOwnerAppProvider,
    private val appResolver: OwnerAppResolver = OwnerAppResolver()
) {
    fun route(rawText: String): RouteDecision {
        val normalized = normalize(rawText)

        if (normalized == "do you feel like chicken tonight") {
            return RouteDecision(
                route = SageRoute.OWNER_WORKFLOW,
                normalizedText = normalized,
                workflowId = "chicken_tonight"
            )
        }

        when (val personalResolution = personal.resolve(rawText)) {
            is SagePersonalResolution.Reply -> return RouteDecision(
                route = SageRoute.LOCAL_SAGE,
                normalizedText = rawText.trim(),
                localReply = personalResolution.text
            )
            is SagePersonalResolution.RewrittenRequest -> return routeResolved(personalResolution.text)
            null -> Unit
        }

        return routeResolved(rawText)
    }

    private fun routeResolved(rawText: String): RouteDecision {
        // The fast parser owns command grammar. Keep punctuation in executable payloads: the
        // workflow matcher above may discard it, but decimal coordinates and app/control names
        // must reach the same parser unchanged when the executor runs.
        val executableText = rawText.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()

        // The parser is the single source of truth for deterministic device commands. A phrase
        // must never bypass Sage's Brain unless the fast executor can actually carry it out.
        val fastCommand = fastCommands.parse(executableText)
        if (fastCommand != null) {
            if (fastCommand is FastCommand.OpenApp) {
                val remembered = appResolver.resolve(fastCommand.appName, ownerApps.snapshot())
                val wholeAppName = remembered?.let { app ->
                    (listOf(app.displayName, app.packageName) + app.aliases)
                        .any { normalize(it) == normalize(fastCommand.appName) }
                } == true
                // The parser's common action verbs are not an exhaustive vocabulary. Preserve
                // unfamiliar second actions too, while allowing a saved app named "Plants and Zombies".
                if (!wholeAppName && Regex("\\b(and|then|but)\\b|[,;]")
                        .containsMatchIn(fastCommand.appName)) {
                    return RouteDecision(SageRoute.DEEP_REASONING, rawText.trim())
                }
                if (!remembered?.startupProcedure.isNullOrBlank()) {
                    return RouteDecision(SageRoute.DEEP_REASONING, rawText.trim())
                }
            }
            return RouteDecision(SageRoute.FAST_DEVICE, executableText)
        }

        // Normalization is only for deterministic command matching. Sage's Brain must receive the
        // owner's actual wording, punctuation, capitalization, names, and code unchanged.
        return RouteDecision(SageRoute.DEEP_REASONING, rawText.trim())
    }

    private fun normalize(value: String): String = value
        .lowercase()
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
