#!/usr/bin/env python3
"""Checkpoint 11: keep the existing Sage voice service alive while the UI is backgrounded.

This is deliberately additive. It does not replace the recognizer or conversation state machine.
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
        raise SystemExit("usage: background_survival_v1_29.py <reconstructed-source>")
    root = Path(sys.argv[1])
    service = root / "app/src/main/java/com/pineapple/sage/SageVoiceService.java"
    manifest = root / "app/src/main/AndroidManifest.xml"
    if not service.is_file() or not manifest.is_file():
        raise SystemExit("Checkpoint 11 requires reconstructed Sage source")

    replace_once(
        manifest,
        '    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />',
        '    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />\n'
        '    <uses-permission android:name="android.permission.WAKE_LOCK" />',
        "wake-lock permission",
    )

    replace_once(
        service,
        'import android.os.Looper;\n',
        'import android.os.Looper;\nimport android.os.PowerManager;\n',
        "PowerManager import",
    )

    replace_once(
        service,
        '    private SharedPreferences preferences;\n',
        '    private SharedPreferences preferences;\n'
        '    private PowerManager.WakeLock backgroundWakeLock;\n',
        "wake-lock field",
    )

    replace_once(
        service,
        '        preferences = getSharedPreferences("sage_state", Context.MODE_PRIVATE);\n'
        '        createNotificationChannel();',
        '        preferences = getSharedPreferences("sage_state", Context.MODE_PRIVATE);\n'
        '        acquireBackgroundWakeLock("service_create");\n'
        '        createNotificationChannel();',
        "acquire wake lock on create",
    )

    replace_once(
        service,
        '        startForeground(NOTIFICATION_ID, buildNotification("Say Sage"));\n'
        '        stopRequested = false;\n',
        '        startForeground(NOTIFICATION_ID, buildNotification("Say Sage"));\n'
        '        stopRequested = false;\n'
        '        acquireBackgroundWakeLock("start_command");\n'
        '        SageDiagnostics.appendEvent(this, "BACKGROUND",\n'
        '                "foreground service active action=" + action + " startId=" + startId);\n',
        "foreground start diagnostics",
    )

    bind_anchor = '''    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
'''
    bind_replacement = '''    @Override
    public void onTaskRemoved(Intent rootIntent) {
        SageDiagnostics.appendEvent(this, "BACKGROUND",
                "task removed; voice service remains active stopRequested=" + stopRequested
                        + " wakeListening=" + wakeListening
                        + " commandListening=" + commandListening
                        + " speaking=" + speaking);
        if (!stopRequested) {
            startForeground(NOTIFICATION_ID, buildNotification("Sage is listening in the background"));
            acquireBackgroundWakeLock("task_removed");
            if (!wakeListening && !commandListening && !commandStartPending && !speaking) {
                startWakeListening(250L);
            }
        }
        super.onTaskRemoved(rootIntent);
    }

    private void acquireBackgroundWakeLock(String reason) {
        if (backgroundWakeLock != null && backgroundWakeLock.isHeld()) {
            return;
        }
        PowerManager manager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (manager == null) {
            SageDiagnostics.appendEvent(this, "BACKGROUND", "wake lock unavailable reason=" + reason);
            return;
        }
        backgroundWakeLock = manager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                getPackageName() + ":SageVoiceService"
        );
        backgroundWakeLock.setReferenceCounted(false);
        backgroundWakeLock.acquire();
        SageDiagnostics.appendEvent(this, "BACKGROUND", "partial wake lock acquired reason=" + reason);
    }

    private void releaseBackgroundWakeLock() {
        if (backgroundWakeLock != null && backgroundWakeLock.isHeld()) {
            backgroundWakeLock.release();
            SageDiagnostics.appendEvent(this, "BACKGROUND", "partial wake lock released");
        }
        backgroundWakeLock = null;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
'''
    replace_once(service, bind_anchor, bind_replacement, "task removal/background helpers")

    replace_once(
        service,
        '        broadcastStatus("Stopped");\n'
        '        super.onDestroy();',
        '        releaseBackgroundWakeLock();\n'
        '        broadcastStatus("Stopped");\n'
        '        SageDiagnostics.appendEvent(this, "BACKGROUND", "voice service destroyed");\n'
        '        super.onDestroy();',
        "wake lock release on destroy",
    )

    print("Applied Checkpoint 11: foreground voice-service background survival + CPU wake continuity")


if __name__ == "__main__":
    main()
