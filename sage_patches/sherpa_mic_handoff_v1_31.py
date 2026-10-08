#!/usr/bin/env python3
"""Let the isolated sherpa A/B test borrow Sage's microphone and restore prior listening state."""
from pathlib import Path
import sys


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: sherpa_mic_handoff_v1_31.py <reconstructed-source>")
    lab = Path(sys.argv[1]) / "app/src/main/java/com/pineapple/sage/SageSpeechLabActivity.java"
    if not lab.is_file():
        raise SystemExit("Sage Speech Lab is missing")

    replace_once(lab,
'''    private SageSherpaMicTester micTester;
    private Button micAction;
    private TextView micStatus;
    private boolean repairRequested;
''',
'''    private SageSherpaMicTester micTester;
    private Button micAction;
    private TextView micStatus;
    private final android.os.Handler micHandoffHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean resumeVoiceAfterMic;
    private boolean destroyed;
    private boolean repairRequested;
''', "microphone handoff state")

    replace_once(lab,
'''        micStatus.setText("Opening the microphone for isolated local transcription…");
        micTester.start(new SageSherpaMicTester.Listener() {
''',
'''        resumeVoiceAfterMic = SageVoiceService.isRunning();
        if (resumeVoiceAfterMic) {
            micStatus.setText("Pausing Sage background listening so Speech Lab can borrow the microphone…");
            android.content.Intent stop = new android.content.Intent(this, SageVoiceService.class)
                    .setAction(SageVoiceService.ACTION_STOP);
            startService(stop);
            waitForVoiceMicRelease(0);
            refresh();
            return;
        }
        startIsolatedSherpaMic();
    }

    private void waitForVoiceMicRelease(int attempt) {
        if (destroyed) {
            restoreVoiceAfterMic();
            return;
        }
        if (!SageVoiceService.isRunning()) {
            micStatus.setText("Sage background listening paused. Starting isolated local transcription…");
            startIsolatedSherpaMic();
            return;
        }
        if (attempt >= 30) {
            micStatus.setText("Sage's background listener did not release the microphone within 3 seconds. Local STT test was not started.");
            restoreVoiceAfterMic();
            refresh();
            return;
        }
        micHandoffHandler.postDelayed(() -> waitForVoiceMicRelease(attempt + 1), 100L);
    }

    private void startIsolatedSherpaMic() {
        if (destroyed || micTester == null || micTester.isRunning()) return;
        micStatus.setText("Opening the microphone for isolated local transcription…");
        micTester.start(new SageSherpaMicTester.Listener() {
''', "borrow microphone before test")

    replace_once(lab,
'''            @Override public void onComplete(String text, long firstPartialLatencyMs,
                                             long totalLatencyMs, long audioMs) {
                runOnUiThread(() -> {
''',
'''            @Override public void onComplete(String text, long firstPartialLatencyMs,
                                             long totalLatencyMs, long audioMs) {
                restoreVoiceAfterMic();
                if (destroyed) return;
                runOnUiThread(() -> {
''', "restore listener after successful A/B")

    replace_once(lab,
'''            @Override public void onError(String detail) {
                runOnUiThread(() -> { micStatus.setText(detail); refresh(); });
            }
        });
        refresh();
    }

    private String filteredSpeechEvidence(String report) {
''',
'''            @Override public void onError(String detail) {
                restoreVoiceAfterMic();
                if (destroyed) return;
                runOnUiThread(() -> { micStatus.setText(detail); refresh(); });
            }
        });
        refresh();
    }

    private void restoreVoiceAfterMic() {
        if (!resumeVoiceAfterMic) return;
        resumeVoiceAfterMic = false;
        micHandoffHandler.post(() -> {
            if (SageVoiceService.isRunning()) return;
            android.content.Intent start = new android.content.Intent(
                    getApplicationContext(), SageVoiceService.class)
                    .setAction(SageVoiceService.ACTION_START);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                getApplicationContext().startForegroundService(start);
            } else {
                getApplicationContext().startService(start);
            }
            SageDiagnostics.appendEvent(getApplicationContext(), "SHERPA A/B",
                    "voice_service_restored=true route=speech_lab_only");
        });
    }

    private String filteredSpeechEvidence(String report) {
''', "restore service after mic callbacks")

    replace_once(lab,
'''    @Override protected void onDestroy() {
        if (micTester != null && micTester.isRunning()) micTester.stop();
        if (modelManager != null && modelManager.isRunning()) modelManager.cancel();
        super.onDestroy();
    }
''',
'''    @Override protected void onDestroy() {
        destroyed = true;
        micHandoffHandler.removeCallbacksAndMessages(null);
        if (micTester != null && micTester.isRunning()) {
            micTester.stop();
        } else {
            restoreVoiceAfterMic();
        }
        if (modelManager != null && modelManager.isRunning()) modelManager.cancel();
        super.onDestroy();
    }
''', "restore microphone ownership on activity exit")

    text = lab.read_text(encoding="utf-8")
    for marker in (
        "resumeVoiceAfterMic = SageVoiceService.isRunning()",
        "SageVoiceService.ACTION_STOP",
        "waitForVoiceMicRelease(0)",
        "attempt >= 30",
        "startIsolatedSherpaMic()",
        "restoreVoiceAfterMic()",
        "SageVoiceService.ACTION_START",
        "voice_service_restored=true route=speech_lab_only",
    ):
        if marker not in text:
            raise SystemExit("missing mic handoff marker: " + marker)
    print("Applied automatic Sage voice-service microphone handoff for sherpa A/B test")


if __name__ == "__main__":
    main()
