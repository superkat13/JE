#!/usr/bin/env python3
"""Additive owner-experience layer for Sage 1.29.

This began as the bounded Monster Mode experiment. The useful pieces now become
normal Sage behavior so the owner does not have to select another Sage. Red Queen
remains the distinct hidden elevated workspace. The stabilized speech state machine,
Brain, router, vault, capability registry, package identity, and signer are preserved.
"""

from pathlib import Path
import re
import sys


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    path.write_text(text.replace(old, new, 1))


def regex_once(path: Path, pattern: str, replacement: str, label: str) -> None:
    text = path.read_text()
    updated, count = re.subn(pattern, replacement, text, count=1, flags=re.MULTILINE)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one regex match, found {count}")
    path.write_text(updated)


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: monster_mode_v1_29.py <reconstructed-source>")

    root = Path(sys.argv[1])
    java = root / "app/src/main/java/com/pineapple/sage"
    voice = java / "SageVoiceService.java"
    session = java / "SageRedQueenSession.java"
    redqueen = java / "SageRedQueenActivity.java"

    if not voice.is_file() or not session.is_file() or not redqueen.is_file():
        raise SystemExit("Owner experience layer requires Sage 1.29 voice and Red Queen sources")

    owner_experience = r'''package com.pineapple.sage;

import android.content.Context;

import java.util.ArrayList;

/**
 * Normal Sage owner experience.
 *
 * These helpers only relax Sage-owned recognition timing and improve diagnostics.
 * They grant no Android permission, root privilege, Red Queen authority, or
 * executable capability. Consequence boundaries remain in their existing layers.
 */
final class SageOwnerExperience {
    private SageOwnerExperience() {}

    static long commandMinimumMillis(long normal) {
        return Math.max(normal, 1700L);
    }

    static long completeSilenceMillis(long normal) {
        return Math.max(normal, 1550L);
    }

    static long possibleSilenceMillis(long normal) {
        return Math.max(normal, 1050L);
    }

    static void recordCandidates(Context context, String stage,
                                 ArrayList<String> choices, String selected) {
        StringBuilder detail = new StringBuilder();
        detail.append("stage=").append(clean(stage));
        detail.append(" selected=").append(clean(selected));
        detail.append(" alternatives=");
        if (choices == null || choices.isEmpty()) {
            detail.append("none");
        } else {
            int emitted = 0;
            for (String choice : choices) {
                if (choice == null || choice.trim().isEmpty()) continue;
                if (emitted++ > 0) detail.append(" || ");
                detail.append(clean(choice));
                if (emitted >= 5) break;
            }
            if (emitted == 0) detail.append("none");
        }
        SageDiagnostics.appendEvent(context, "VOICE CANDIDATES", detail.toString());
    }

    private static String clean(String value) {
        if (value == null) return "";
        String cleaned = value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
        return cleaned.length() <= 180 ? cleaned : cleaned.substring(0, 180);
    }
}
'''
    (java / "SageOwnerExperience.java").write_text(owner_experience)

    # Red Queen remains special, but one successful owner authentication should
    # remain useful while moving through its workspace.
    replace_once(
        session,
        "    private static final long SESSION_MS = 5L * 60L * 1000L;",
        "    private static final long SESSION_MS = 60L * 60L * 1000L;",
        "Red Queen owner-session duration",
    )

    replace_once(
        redqueen,
        '''    @Override protected void onStop() {
        handler.removeCallbacks(inactivityLock);
        if (!authenticating) SageRedQueenSession.lock(this, "app_backgrounded");
        super.onStop();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        SageRedQueenSession.lock(this, "workspace_closed");
        super.onDestroy();
    }''',
        '''    @Override protected void onStop() {
        handler.removeCallbacks(inactivityLock);
        super.onStop();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }''',
        "Red Queen background persistence",
    )

    replace_once(
        redqueen,
        "    private static final long INACTIVITY_MS = 5L * 60L * 1000L;",
        "    private static final long INACTIVITY_MS = 60L * 60L * 1000L;",
        "Red Queen workspace inactivity duration",
    )

    # Normal Sage gets the more forgiving recognition windows. This is additive:
    # the state machine, stale-callback gate, final latch, echo guard, and media
    # authorization policy remain untouched.
    regex_once(
        voice,
        r'recognizerIntent\.putExtra\(RecognizerIntent\.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,\s*([0-9]+L)\);',
        r'recognizerIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, SageOwnerExperience.commandMinimumMillis(\1));',
        "normal Sage minimum command window",
    )
    regex_once(
        voice,
        r'recognizerIntent\.putExtra\(RecognizerIntent\.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,\s*([0-9]+L)\);',
        r'recognizerIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SageOwnerExperience.completeSilenceMillis(\1));',
        "normal Sage complete-silence window",
    )
    regex_once(
        voice,
        r'recognizerIntent\.putExtra\(RecognizerIntent\.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,\s*([0-9]+L)\);',
        r'recognizerIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, SageOwnerExperience.possibleSilenceMillis(\1));',
        "normal Sage possible-silence window",
    )

    # Keep every existing final+partial alternative and expose what Android heard.
    # This hook lives inside an anonymous RecognitionListener, so use the outer
    # service context explicitly rather than the listener's `this`.
    regex_once(
        voice,
        r'(?P<indent>\s*)String candidate = chooseBestCandidate\((?P<choices>[^;\n]+)\);',
        r'\g<indent>String candidate = chooseBestCandidate(\g<choices>);\g<indent>SageOwnerExperience.recordCandidates(SageVoiceService.this, "final", \g<choices>, candidate);',
        "normal Sage alternate-candidate diagnostics",
    )

    print("Applied additive Sage 1.29 normal-owner experience and Red Queen session usability layer")


if __name__ == "__main__":
    main()
