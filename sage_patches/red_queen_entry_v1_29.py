#!/usr/bin/env python3
"""Checkpoint 6: restore Red Queen as the spoken hidden doorway, not another Sage.

Exact spoken trigger opens the existing Red Queen workspace. A saved custom media
response for the phrase still plays during entry. Existing verified owner authority
is reused instead of forcing another credential prompt on every screen entry.
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

    text = voice.read_text()
    declaration = text.find("SageMediaResponseStore.Entry voiceResponse")
    if declaration < 0:
        raise SystemExit("spoken Red Queen dispatch: custom voice-response declaration not found")
    if_start = text.find("if (voiceResponse != null)", declaration)
    if if_start < 0:
        raise SystemExit("spoken Red Queen dispatch: custom voice-response branch not found")
    play_at = text.find("playVoiceResponse(voiceResponse);", if_start)
    return_at = text.find("return;", play_at)
    if play_at < 0 or return_at < 0:
        raise SystemExit("spoken Red Queen dispatch: custom voice-response branch is malformed")

    # Saved Red Queen audio/video remains first-class. Open the workspace immediately
    # after playback begins, rather than swallowing the trigger as a plain Easter egg.
    after_play = play_at + len("playVoiceResponse(voiceResponse);")
    text = text[:after_play] + '''
            if (isRedQueenSpokenTrigger(cleaned)) {
                SageDiagnostics.appendEvent(this, "RED QUEEN ENTRY",
                        "spoken_trigger=true response=custom_media existing_session="
                                + SageRedQueenSession.isUnlocked(this));
                handler.postDelayed(this::openRedQueenWorkspace, 250L);
            }''' + text[after_play:]

    # If no saved custom media exists, catch the same exact trigger before normal
    # translation/personality/Brain routing and give Sage a default line.
    easter_anchor = "SageEasterEggStore.Entry easterEgg = SageEasterEggStore.find(this, cleaned);"
    anchor_at = text.find(easter_anchor)
    if anchor_at < 0:
        raise SystemExit("spoken Red Queen dispatch: personality routing anchor not found")
    line_start = text.rfind("\n", 0, anchor_at) + 1
    indent = text[line_start:anchor_at]
    fallback = (
        indent + "if (isRedQueenSpokenTrigger(cleaned)) {\n"
        + indent + "    broadcastLine(\"Sage\", \"Well then. Off with the training wheels.\");\n"
        + indent + "    speak(\"Well then. Off with the training wheels.\");\n"
        + indent + "    SageDiagnostics.appendEvent(this, \"RED QUEEN ENTRY\",\n"
        + indent + "            \"spoken_trigger=true response=tts existing_session=\"\n"
        + indent + "                    + SageRedQueenSession.isUnlocked(this));\n"
        + indent + "    handler.postDelayed(this::openRedQueenWorkspace, 250L);\n"
        + indent + "    return;\n"
        + indent + "}\n"
    )
    text = text[:line_start] + fallback + text[line_start:]
    voice.write_text(text)

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

    session_text = session.read_text()
    guards = ("isDeviceLocked()", "canAttempt", "recordFailure", "unlockedUntilMs")
    missing = [token for token in guards if token not in session_text]
    if missing:
        raise SystemExit("Checkpoint 6 would weaken Red Queen authentication: " + ", ".join(missing))

    print("Applied Checkpoint 6: spoken Red Queen doorway with reusable verified owner session")


if __name__ == "__main__":
    main()
