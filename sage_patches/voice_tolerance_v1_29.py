#!/usr/bin/env python3
"""Checkpoint 3: additive voice tolerance around the stabilized recognizer.

This does not replace Sage's conversation state machine, wake authorization, echo
classification, duplicate-final latch, media boundary, or consequence policy. It only
recovers a longer recognition alternative when Android selected an obvious one/two-word
prefix fragment of that same alternative, then records the recovery in diagnostics.
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


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: voice_tolerance_v1_29.py <reconstructed-source>")

    root = Path(sys.argv[1])
    java = root / "app/src/main/java/com/pineapple/sage"
    owner = java / "SageOwnerExperience.java"
    voice = java / "SageVoiceService.java"
    machine = java / "SageConversationStateMachine.java"
    for required in (owner, voice, machine):
        if not required.is_file():
            raise SystemExit(f"Checkpoint 3 missing reconstructed source: {required.name}")

    owner_methods = r'''    static String recoverCandidate(ArrayList<String> choices, String selected) {
        String normalizedSelected = normalize(selected);
        int selectedWords = wordCount(normalizedSelected);
        if (choices == null || choices.isEmpty() || selectedWords < 1 || selectedWords > 2) {
            return selected;
        }

        String best = selected;
        int bestWords = selectedWords;
        for (String choice : choices) {
            String normalizedChoice = normalize(choice);
            if (normalizedChoice.isEmpty() || normalizedChoice.equals(normalizedSelected)) continue;
            if (!normalizedChoice.startsWith(normalizedSelected + " ")) continue;
            int words = wordCount(normalizedChoice);
            if (words <= bestWords || words > 12 || normalizedChoice.length() > 160) continue;
            best = choice == null ? selected : choice.trim();
            bestWords = words;
        }
        return best;
    }

    static void recordRecovery(Context context, String selected, String recovered) {
        String before = normalize(selected);
        String after = normalize(recovered);
        if (before.equals(after)) return;
        SageDiagnostics.appendEvent(context, "VOICE RECOVERY",
                "selected=" + clean(selected) + " recovered=" + clean(recovered)
                        + " reason=prefix_fragment_alternate");
    }

    private static int wordCount(String value) {
        if (value == null || value.isEmpty()) return 0;
        return value.split(" ").length;
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(java.util.Locale.US)
                .replaceAll("[^a-z0-9']+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

'''
    replace_once(
        owner,
        "    private static String clean(String value) {",
        owner_methods + "    private static String clean(String value) {",
        "owner voice-recovery helpers",
    )

    voice_text = voice.read_text()
    pattern = re.compile(
        r'(?P<indent>^[ \t]*)String candidate = chooseBestCandidate\((?P<choices>[^;\n]+)\);',
        re.MULTILINE,
    )
    match = pattern.search(voice_text)
    if match is None:
        raise SystemExit("Checkpoint 3 could not find the existing chooseBestCandidate final path")
    indent = match.group("indent")
    choices = match.group("choices")
    original = match.group(0)
    replacement = (
        original
        + "\n" + indent + "String originalCandidate = candidate;"
        + "\n" + indent + f"candidate = SageOwnerExperience.recoverCandidate({choices}, candidate);"
        + "\n" + indent + "SageOwnerExperience.recordRecovery(SageVoiceService.this, originalCandidate, candidate);"
    )
    voice_text = voice_text[:match.start()] + replacement + voice_text[match.end():]
    voice.write_text(voice_text)

    # Hard preservation guards use structural states/classes that actually exist in
    # the stabilized machine. Detailed stale/duplicate behavior remains covered by
    # the inherited executable regression suite and cannot be bypassed here.
    machine_text = machine.read_text()
    required_machine = (
        "COMMAND_LISTENING",
        "FINALIZING",
        "ECHO_GUARD",
        "SAGE_TTS",
        "ACTIVE_MEDIA",
        "COMMAND_FINAL",
        "dispatchCount",
    )
    missing = [token for token in required_machine if token not in machine_text]
    if missing:
        raise SystemExit("Checkpoint 3 would advance without preserved voice boundaries: " + ", ".join(missing))

    print("Applied Checkpoint 3: bounded alternate-candidate voice recovery around the existing state machine")


if __name__ == "__main__":
    main()
