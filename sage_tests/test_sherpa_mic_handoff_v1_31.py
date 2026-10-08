#!/usr/bin/env python3
from pathlib import Path
import sys


def require(text: str, needle: str, label: str) -> None:
    if needle not in text:
        raise SystemExit(f"missing {label}: {needle}")


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: test_sherpa_mic_handoff_v1_31.py <reconstructed-source>")
    lab = (Path(sys.argv[1]) / "app/src/main/java/com/pineapple/sage/SageSpeechLabActivity.java").read_text(encoding="utf-8")
    require(lab, 'resumeVoiceAfterMic = SageVoiceService.isRunning()', "preserve prior listener state")
    require(lab, 'setAction(SageVoiceService.ACTION_STOP)', "clean voice service stop contract")
    require(lab, 'waitForVoiceMicRelease(0)', "asynchronous mic release wait")
    require(lab, 'attempt >= 30', "bounded 3-second release wait")
    require(lab, 'startIsolatedSherpaMic()', "isolated test after release")
    require(lab, 'restoreVoiceAfterMic();', "listener restoration")
    require(lab, 'setAction(SageVoiceService.ACTION_START)', "clean voice service restart contract")
    require(lab, 'startForegroundService(start)', "Android O+ restart")
    require(lab, 'voice_service_restored=true route=speech_lab_only', "restoration diagnostics")
    require(lab, 'if (!resumeVoiceAfterMic) return;', "do not start listener if it was originally off")
    if 'dispatchTypedCommand' in lab:
        raise SystemExit("Speech Lab A/B handoff must not dispatch a command")
    print("Sage 1.31 Speech Lab microphone handoff regression passed")


if __name__ == "__main__":
    main()
