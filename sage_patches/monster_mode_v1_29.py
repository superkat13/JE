#!/usr/bin/env python3
"""Additive owner-control Monster Mode for Sage 1.29.

This patch deliberately layers on top of the verified 1.29 reconstruction. It does
not replace the speech state machine, command router, Brain, Red Queen vault, or
existing capability registry.
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
        raise SystemExit("Monster Mode requires the reconstructed Sage 1.29 voice and Red Queen sources")

    monster = r'''package com.pineapple.sage;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;

/**
 * Additive owner-control profile. Monster Mode never grants Android permissions or
 * executable authority by itself; it only relaxes Sage-owned convenience limits
 * while a verified Red Queen owner session is active.
 */
final class SageMonsterMode {
    private static final String PREFS = "sage_monster_mode";
    private static final String ENABLED = "enabled";

    private SageMonsterMode() {}

    static boolean isEnabled(Context context) {
        return context != null
                && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .getBoolean(ENABLED, false)
                && SageRedQueenSession.isUnlocked(context);
    }

    static boolean isStoredEnabled(Context context) {
        return context != null
                && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .getBoolean(ENABLED, false);
    }

    static boolean setEnabled(Context context, boolean enabled) {
        if (enabled && !SageRedQueenSession.isUnlocked(context)) return false;
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        preferences.edit().putBoolean(ENABLED, enabled).apply();
        SageDiagnostics.appendEvent(context, "MONSTER MODE", enabled ? "enabled by verified owner" : "disabled by owner");
        SageRedQueenVault.appendAudit(context, "monster_mode", enabled ? "enabled" : "disabled");
        return true;
    }

    static long commandMinimumMillis(Context context, long normal) {
        return isEnabled(context) ? Math.max(normal, 1700L) : normal;
    }

    static long completeSilenceMillis(Context context, long normal) {
        return isEnabled(context) ? Math.max(normal, 1550L) : normal;
    }

    static long possibleSilenceMillis(Context context, long normal) {
        return isEnabled(context) ? Math.max(normal, 1050L) : normal;
    }

    static void recordCandidates(Context context, String stage,
                                 ArrayList<String> choices, String selected) {
        if (!isEnabled(context)) return;
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
    (java / "SageMonsterMode.java").write_text(monster)

    # Owner authority: remove the arbitrary five-minute ceiling while preserving
    # process-local authority and the existing device-lock check.
    replace_once(
        session,
        "    private static final long SESSION_MS = 5L * 60L * 1000L;",
        "    private static final long SESSION_MS = 60L * 60L * 1000L;",
        "Red Queen owner-session duration",
    )

    # App switching should not revoke verified owner authority. Process death still
    # clears the process-local session and the existing isUnlocked() device-lock
    # boundary remains intact.
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

    # Make the activity inactivity timer agree with the process-local owner session.
    replace_once(
        redqueen,
        "    private static final long INACTIVITY_MS = 5L * 60L * 1000L;",
        "    private static final long INACTIVITY_MS = 60L * 60L * 1000L;",
        "Red Queen workspace inactivity duration",
    )

    replace_once(
        redqueen,
        '''        root.addView(label("RED QUEEN MODE", 30, Color.rgb(255, 60, 75)));
        root.addView(label("Sage under verified owner authority. Android permissions and each sensitive operation remain separately controlled.",
                15, Color.LTGRAY));''',
        '''        root.addView(label("RED QUEEN MODE", 30, Color.rgb(255, 60, 75)));
        root.addView(label("Sage under verified owner authority. Android permissions and irreversible external actions keep their real platform boundary; Sage-owned convenience limits can be relaxed here.",
                15, Color.LTGRAY));
        Button monster = button(SageMonsterMode.isStoredEnabled(this)
                ? "Monster Mode: ON" : "Monster Mode: OFF");
        monster.setOnClickListener(v -> {
            boolean next = !SageMonsterMode.isStoredEnabled(this);
            if (SageMonsterMode.setEnabled(this, next)) {
                Toast.makeText(this, next
                        ? "Monster Mode enabled. Sage has more room to breathe."
                        : "Monster Mode disabled.", Toast.LENGTH_LONG).show();
                showWorkspace();
            } else {
                Toast.makeText(this, "Authenticate owner before enabling Monster Mode.",
                        Toast.LENGTH_LONG).show();
            }
        });
        root.addView(monster);''',
        "Monster Mode owner control",
    )

    # Speech tolerance is conditional. Normal Sage keeps the exact verified values.
    regex_once(
        voice,
        r'recognizerIntent\.putExtra\(RecognizerIntent\.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,\s*([0-9]+L)\);',
        r'recognizerIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, SageMonsterMode.commandMinimumMillis(this, \1));',
        "Monster Mode minimum command window",
    )
    regex_once(
        voice,
        r'recognizerIntent\.putExtra\(RecognizerIntent\.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,\s*([0-9]+L)\);',
        r'recognizerIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SageMonsterMode.completeSilenceMillis(this, \1));',
        "Monster Mode complete-silence window",
    )
    regex_once(
        voice,
        r'recognizerIntent\.putExtra\(RecognizerIntent\.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,\s*([0-9]+L)\);',
        r'recognizerIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, SageMonsterMode.possibleSilenceMillis(this, \1));',
        "Monster Mode possible-silence window",
    )

    # Preserve all final + partial alternatives and expose what the recognizer saw.
    replace_once(
        voice,
        "                    String candidate = chooseBestCandidate(combinedChoices);",
        '''                    String candidate = chooseBestCandidate(combinedChoices);
                    SageMonsterMode.recordCandidates(this, "final", combinedChoices, candidate);''',
        "Monster Mode alternate-candidate diagnostics",
    )

    # Add richer diagnostics at the selector without changing its normal ranking or
    # echo rejection. This deliberately avoids bypassing the stabilized echo guard.
    marker = '''        return best;
    }

    private int scoreCommandCandidate(String choice, int index) {'''
    replacement = '''        if (SageMonsterMode.isEnabled(this)) {
            SageDiagnostics.appendEvent(this, "VOICE SELECTION",
                    "selected=" + best + " score=" + bestScore
                            + " follow_up=" + (commandEngine != null && commandEngine.isAwaitingFollowUp()));
        }
        return best;
    }

    private int scoreCommandCandidate(String choice, int index) {'''
    replace_once(voice, marker, replacement, "Monster Mode voice selection diagnostics")

    print("Applied additive Sage 1.29 Monster Mode owner-control and voice-tolerance layer")


if __name__ == "__main__":
    main()
