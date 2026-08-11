#!/usr/bin/env python3
from pathlib import Path
import sys


def require(text: str, needle: str, label: str) -> None:
    if needle not in text:
        raise AssertionError(f"missing {label}: {needle}")


def forbid(text: str, needle: str, label: str) -> None:
    if needle in text:
        raise AssertionError(f"unexpected {label}: {needle}")


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: test_background_survival_v1_29.py <reconstructed-source>")
    root = Path(sys.argv[1])
    service_path = root / "app/src/main/java/com/pineapple/sage/SageVoiceService.java"
    manifest_path = root / "app/src/main/AndroidManifest.xml"
    main_path = root / "app/src/main/java/com/pineapple/sage/MainActivity.java"
    service = service_path.read_text()
    manifest = manifest_path.read_text()
    main = main_path.read_text()

    require(manifest, 'android.permission.FOREGROUND_SERVICE', "foreground service permission")
    require(manifest, 'android.permission.FOREGROUND_SERVICE_MICROPHONE', "microphone foreground-service permission")
    require(manifest, 'android.permission.WAKE_LOCK', "wake lock permission")
    require(manifest, 'android:name=".SageVoiceService"', "voice service declaration")
    require(manifest, 'android:foregroundServiceType="microphone"', "microphone service type")
    require(manifest, 'android:stopWithTask="false"', "task-independent service declaration")

    require(service, 'startForeground(NOTIFICATION_ID, buildNotification("Say Sage"))', "foreground promotion")
    require(service, 'return START_STICKY;', "sticky service restart")
    require(service, 'public void onTaskRemoved(Intent rootIntent)', "task-removal survival callback")
    require(service, 'PowerManager.PARTIAL_WAKE_LOCK', "partial CPU wake lock")
    require(service, 'backgroundWakeLock.acquire();', "wake lock acquisition")
    require(service, 'releaseBackgroundWakeLock();', "wake lock cleanup")
    require(service, 'startWakeListening(250L);', "wake listener re-arm after task removal")
    require(service, '"Sage is listening in the background"', "background foreground-notification state")
    require(service, '"BACKGROUND"', "background diagnostics")

    # Preserve the existing architecture instead of replacing voice with a new subsystem.
    require(service, 'SageConversationStateMachine', "existing conversation state machine")
    require(service, 'SpeechService wakeSpeechService', "existing Vosk wake service")
    require(service, 'SpeechRecognizer speechRecognizer', "existing Android command recognizer")
    require(main, 'startForegroundService(start);', "existing owner-start path")

    # Explicit Stop remains the only deliberate service shutdown path.
    require(service, 'if (ACTION_STOP.equals(action))', "explicit stop action")
    require(service, 'return START_NOT_STICKY;', "explicit stop non-restart")

    # No destructive migration or workaround was introduced.
    for bad in ('pm clear', 'clearApplicationUserData(', 'factory reset', 'uninstall'):
        forbid(service.lower(), bad.lower(), "destructive background workaround")

    print("Checkpoint 11 background survival regression: PASS")


if __name__ == "__main__":
    main()
