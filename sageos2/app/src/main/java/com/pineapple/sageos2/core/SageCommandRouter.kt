package com.pineapple.sageos2.core

import com.pineapple.sageos2.action.FastCommand
import com.pineapple.sageos2.action.FastCommandParser
import com.pineapple.sageos2.apps.EmptyOwnerAppProvider
import com.pineapple.sageos2.apps.OwnerAppProvider
import com.pineapple.sageos2.apps.OwnerAppResolver
import com.pineapple.sageos2.personal.EmptySagePersonalResponder
import com.pineapple.sageos2.personal.SagePersonalResolution
import com.pineapple.sageos2.personal.SagePersonalResponder

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
        val normalized = normalize(rawText)

        // The parser is the single source of truth for deterministic device commands. A phrase
        // must never bypass Sage's Brain unless the fast executor can actually carry it out.
        val fastCommand = fastCommands.parse(normalized)
        if (fastCommand != null) {
            if (fastCommand is FastCommand.OpenApp) {
                val remembered = appResolver.resolve(fastCommand.appName, ownerApps.snapshot())
                if (!remembered?.startupProcedure.isNullOrBlank()) {
                    return RouteDecision(SageRoute.DEEP_REASONING, rawText.trim())
                }
            }
            return RouteDecision(SageRoute.FAST_DEVICE, normalized)
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
