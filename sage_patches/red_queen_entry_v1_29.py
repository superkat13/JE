#!/usr/bin/env python3
"""Checkpoint 6: restore Red Queen as the spoken hidden doorway, not another Sage.

Exact spoken trigger opens the existing Red Queen workspace. A saved custom media
response for the phrase still plays first/alongside entry. Existing verified owner
authority is reused instead of forcing another credential prompt on every screen entry.
"""
from pathlib import Path
import sys


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    path.write_text(text.replace(old, new, 1))


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: red_queen_entry_v1_29.py <reconstructed-source>")

    root = Path(sys.argv[1])
    java = root / "app/src/main/java/com/pineapple/sage"
    voice = java / "SageVoiceService.java"
    activity = java / "SageRedQueenActivity.java"
    session = java / "SageRedQueenSession.java"
    for required in (voice, activity, session):
        if not required.is_file():
            raise SystemExit(f"Checkpoint 6 missing reconstructed source: {required.name}")

    replace_once(
        activity,
        '''        setTitle("Red Queen Mode");
        showLocked();
        if (state == null) authenticate();''',
        '''        setTitle("Red Queen Mode");
        if (SageRedQueenSession.isUnlocked(this)) {
            showWorkspace();
        } else {
            showLocked();
            if (state == null) authenticate();
        }''',
        "reuse existing Red Queen owner session",
    )

    media_block = '''        SageMediaResponseStore.Entry voiceResponse =
                SageMediaResponseStore.find(preferences, cleaned);
        if (voiceResponse != null) {
            playVoiceResponse(voiceResponse);
            return;
        }'''
    replacement = '''        SageMediaResponseStore.Entry voiceResponse =
                SageMediaResponseStore.find(preferences, cleaned);
        if (isRedQueenSpokenTrigger(cleaned)) {
            if (voiceResponse != null) {
                playVoiceResponse(voiceResponse);
            } else {
                broadcastLine("Sage", "Well then. Off with the training wheels.");
                speak("Well then. Off with the training wheels.");
            }
            SageDiagnostics.appendEvent(this, "RED QUEEN ENTRY",
                    "spoken_trigger=true existing_session=" + SageRedQueenSession.isUnlocked(this));
            handler.postDelayed(this::openRedQueenWorkspace, 250L);
            return;
        }
        if (voiceResponse != null) {
            playVoiceResponse(voiceResponse);
            return;
        }'''
    replace_once(voice, media_block, replacement, "spoken Red Queen dispatch")

    method_anchor = '''    private void playVoiceResponse(SageMediaResponseStore.Entry response) {'''
    methods = '''    private boolean isRedQueenSpokenTrigger(String spoken) {
        if (spoken == null) return false;
        String value = spoken.toLowerCase(java.util.Locale.US)
                .replaceAll("[^a-z0-9 ]+", " ").replaceAll("\\s+", " ").trim();
        return value.equals("red queen mode") || value.equals("sage glitch");
    }

    private void openRedQueenWorkspace() {
        try {
            Intent intent = new Intent(this, SageRedQueenActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
        } catch (RuntimeException error) {
            SageDiagnostics.recordError(this, "Red Queen entry failed: "
                    + error.getClass().getSimpleName());
        }
    }

'''
    replace_once(voice, method_anchor, methods + method_anchor, "Red Queen entry helpers")

    # Preserve hard owner-auth boundaries.
    session_text = session.read_text()
    guards = ("isDeviceLocked()", "canAttempt", "recordFailure", "unlockedUntilMs")
    missing = [token for token in guards if token not in session_text]
    if missing:
        raise SystemExit("Checkpoint 6 would weaken Red Queen authentication: " + ", ".join(missing))

    print("Applied Checkpoint 6: spoken Red Queen doorway with reusable verified owner session")


if __name__ == "__main__":
    main()
