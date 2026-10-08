#!/usr/bin/env python3
"""Checkpoint 4: natural requests route to internal specialists behind one Sage.

This is additive. It refines the existing coordinator intent before the compiled
capability registry selects a tool. It does not create user-facing modes, grant
permissions, bypass Red Queen, or execute commands directly.
"""
from pathlib import Path
import re
import sys


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: internal_specialist_routing_v1_29.py <reconstructed-source>")

    root = Path(sys.argv[1])
    java = root / "app/src/main/java/com/pineapple/sage"
    coordinator = java / "SageIntentCoordinator.java"
    registry = java / "SageCapabilityRegistry.java"
    if not coordinator.is_file() or not registry.is_file():
        raise SystemExit("Checkpoint 4 requires the existing coordinator and compiled capability registry")

    router = r'''package com.pineapple.sage;

import java.util.Locale;

/**
 * Internal specialist routing for the single Sage identity.
 *
 * This class only refines an intent label. It cannot execute a tool, grant authority,
 * unlock Red Queen, request a permission, open a socket, run shell code, or change a
 * package. The existing compiled capability registry and consequence policies remain
 * the execution boundary.
 */
final class SageInternalSpecialistRouter {
    private SageInternalSpecialistRouter() {}

    static String refineIntent(String request, String existingIntent, boolean forgePaired) {
        String text = normalize(request);
        String current = normalize(existingIntent);

        // Preserve already-specific high-value intents chosen by the existing parser.
        if (isSpecific(current)) return current;

        // Creative software is an implementation detail behind normal Sage.
        if (containsAny(text, "adobe", "firefly", "photoshop", "premiere", "after effects",
                "video edit", "edit this video", "transition", "shot list", "storyboard",
                "prompt for", "image prompt", "video prompt")) {
            return "creative";
        }

        // Engineering requests stay one-Sage. When Forge is paired the existing
        // recovery capability can select Dell execution; otherwise it remains local/fallback.
        if (containsAny(text, "code", "coding", "github", "repository", "repo", "compile",
                "build error", "gradle", "java error", "android project", "fix this bug",
                "debug this", "source code", "apk build")) {
            return "recovery";
        }

        if (containsAny(text, "apk", "package", "signer", "certificate", "manifest")) {
            return "package";
        }
        if (containsAny(text, "hash this file", "inspect this file", "file metadata", "mime type")) {
            return "file";
        }
        if (containsAny(text, "playback", "pause music", "next song", "active media", "media session")) {
            return "media";
        }
        if (containsAny(text, "network", "subnet", "private lan", "ip address")) {
            return "network";
        }
        if (containsAny(text, "remember", "my preference", "when i say", "my device")) {
            return "memory";
        }

        return current.isEmpty() ? "knowledge" : current;
    }

    static String specialistLabel(String intent, boolean forgePaired) {
        String value = normalize(intent);
        switch (value) {
            case "creative": return "creative.director";
            case "package": return "package.inspect";
            case "file": return "file.inspect";
            case "media": return "media.session";
            case "network": return "network.private_lan";
            case "memory": return "memory.standard";
            case "recovery": return forgePaired ? "forge.approved_job" : "repair.diagnose";
            default: return "brain.local";
        }
    }

    private static boolean isSpecific(String intent) {
        return intent.equals("package") || intent.equals("file") || intent.equals("network")
                || intent.equals("osint") || intent.equals("forensics")
                || intent.equals("reverse_engineering") || intent.equals("automation")
                || intent.equals("red_queen") || intent.equals("recovery")
                || intent.equals("creative") || intent.equals("media") || intent.equals("memory");
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.US).replaceAll("\\s+", " ").trim();
    }
}
'''
    (java / "SageInternalSpecialistRouter.java").write_text(router)

    text = coordinator.read_text()
    pattern = re.compile(r'(?P<indent>^[ \t]*)String intent = intentFor\((?P<arg>[^;\n]+)\);', re.MULTILINE)
    match = pattern.search(text)
    if match is None:
        raise SystemExit("Checkpoint 4 could not locate the existing intent selection")
    indent = match.group("indent")
    original = match.group(0)
    insertion = (original
        + "\n" + indent + "intent = SageInternalSpecialistRouter.refineIntent(execution, intent, forgePaired);"
        + "\n" + indent + "String internalSpecialist = SageInternalSpecialistRouter.specialistLabel(intent, forgePaired);")
    text = text[:match.start()] + insertion + text[match.end():]

    # Add the hidden routing choice to diagnostics without changing execution.
    trace_pattern = ' + " entities=" + entities + " " + riskDecision'
    if trace_pattern not in text:
        raise SystemExit("Checkpoint 4 could not locate existing orchestrator diagnostics")
    text = text.replace(trace_pattern,
        ' + " entities=" + entities + " internal_specialist=" + internalSpecialist + " " + riskDecision', 1)
    coordinator.write_text(text)

    # Guard the consequence boundary explicitly.
    registry_text = registry.read_text()
    required = ("redQueenRequired", "confirmation", "SupportState", "SageCapabilityRegistry")
    missing = [token for token in required if token not in registry_text]
    if missing:
        raise SystemExit("Checkpoint 4 would advance without compiled capability boundaries: " + ", ".join(missing))

    print("Applied Checkpoint 4: internal specialist routing behind one Sage")


if __name__ == "__main__":
    main()
