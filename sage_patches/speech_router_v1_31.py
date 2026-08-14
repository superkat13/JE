#!/usr/bin/env python3
"""Add an evidence-first speech backend seam and compact Speech Lab for Sage 1.31."""
from pathlib import Path
import sys


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: speech_router_v1_31.py <reconstructed-source>")
    root = Path(sys.argv[1])
    java = root / "app/src/main/java/com/pineapple/sage"
    main_activity = java / "MainActivity.java"
    manifest = root / "app/src/main/AndroidManifest.xml"
    if not main_activity.is_file() or not manifest.is_file():
        raise SystemExit("reconstructed Sage source is missing")

    replace_once(main_activity,
'''        Button voiceSettings = makeButton("Voice Studio — choose & preview Sage");
        voiceSettings.setOnClickListener(v -> openVoiceSettings());
        voicePanel.addView(voiceSettings, spacedSmall());

        TextView voiceHelp = new TextView(this);
''',
'''        Button voiceSettings = makeButton("Voice Studio — choose & preview Sage");
        voiceSettings.setOnClickListener(v -> openVoiceSettings());
        voicePanel.addView(voiceSettings, spacedSmall());

        Button speechLab = makeButton("Speech Lab — recognizer evidence");
        speechLab.setContentDescription("Open Sage Speech Lab and recognizer diagnostics");
        speechLab.setOnClickListener(v ->
                startActivity(new Intent(this, SageSpeechLabActivity.class)));
        voicePanel.addView(speechLab, spacedSmall());

        TextView voiceHelp = new TextView(this);
''', "Speech Lab entry")

    replace_once(manifest,
'''        <activity android:name=".SageVoiceSettingsActivity" android:exported="false" />
''',
'''        <activity android:name=".SageVoiceSettingsActivity" android:exported="false" />
        <activity android:name=".SageSpeechLabActivity" android:exported="false" />
''', "Speech Lab manifest entry")

    (java / "SageSpeechBackendState.java").write_text(SPEECH_BACKEND, encoding="utf-8")
    (java / "SageSpeechLabActivity.java").write_text(SPEECH_LAB, encoding="utf-8")

    combined = main_activity.read_text(encoding="utf-8") + manifest.read_text(encoding="utf-8")
    for marker in (
        "Speech Lab — recognizer evidence",
        "SageSpeechLabActivity.class",
        '.SageSpeechLabActivity',
    ):
        if marker not in combined:
            raise SystemExit("missing speech-router marker: " + marker)
    for path, markers in (
        (java / "SageSpeechBackendState.java", ("SHERPA_VERSION = \"1.13.4\"", "streaming-zipformer-en-20M-2023-02-17", "Android SpeechRecognizer")),
        (java / "SageSpeechLabActivity.java", ("A / B recognizer evidence", "Sherpa readiness", "Recent speech evidence")),
    ):
        text = path.read_text(encoding="utf-8")
        for marker in markers:
            if marker not in text:
                raise SystemExit(f"{path.name}: missing {marker}")
    print("Applied Sage 1.31 Speech Lab and truthful sherpa readiness seam")


SPEECH_BACKEND = r'''package com.pineapple.sage;

import android.content.Context;

import java.io.File;
import java.util.Locale;

/** Truthful command-STT backend readiness. Does not claim sherpa is active until engine and model exist. */
final class SageSpeechBackendState {
    static final String SHERPA_VERSION = "1.13.4";
    static final String MODEL_ID = "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17";
    static final String MODEL_LICENSE_NOTE = "Model license must be preserved with the downloaded pack.";

    private SageSpeechBackendState() { }

    static boolean sherpaNativePresent(Context context) {
        if (context == null || context.getApplicationInfo() == null
                || context.getApplicationInfo().nativeLibraryDir == null) return false;
        File nativeDir = new File(context.getApplicationInfo().nativeLibraryDir);
        return new File(nativeDir, "libsherpa-onnx-jni.so").isFile()
                && new File(nativeDir, "libonnxruntime.so").isFile();
    }

    static File modelDirectory(Context context) {
        return new File(new File(new File(context.getFilesDir(), "speech"), "sherpa"), MODEL_ID);
    }

    static boolean sherpaModelPresent(Context context) {
        File dir = modelDirectory(context);
        return new File(dir, "tokens.txt").isFile()
                && new File(dir, "encoder-epoch-99-avg-1.int8.onnx").isFile()
                && new File(dir, "decoder-epoch-99-avg-1.onnx").isFile()
                && new File(dir, "joiner-epoch-99-avg-1.int8.onnx").isFile();
    }

    static boolean sherpaReady(Context context) {
        return sherpaNativePresent(context) && sherpaModelPresent(context);
    }

    static String activeCommandBackend(Context context) {
        // The engine swap intentionally has not happened in this evidence-first slice.
        return "Android SpeechRecognizer (current command recognizer)";
    }

    static String plannedBackend(Context context) {
        return sherpaReady(context)
                ? "sherpa-onnx is physically ready for the next routing pass"
                : "sherpa-onnx is not active yet; Android remains the truthful fallback";
    }

    static String summary(Context context) {
        File modelDir = modelDirectory(context);
        return "Current command backend: " + activeCommandBackend(context)
                + "\nTarget local backend: sherpa-onnx " + SHERPA_VERSION
                + "\nTarget English streaming model: " + MODEL_ID
                + "\nSherpa native libraries present: " + yesNo(sherpaNativePresent(context))
                + "\nSherpa model pack present: " + yesNo(sherpaModelPresent(context))
                + "\nSherpa route ready: " + yesNo(sherpaReady(context))
                + "\nModel directory: " + modelDir.getAbsolutePath()
                + "\nState: " + plannedBackend(context)
                + "\n" + MODEL_LICENSE_NOTE;
    }

    private static String yesNo(boolean value) { return value ? "yes" : "no"; }
}
'''


SPEECH_LAB = r'''package com.pineapple.sage;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/** Compact A/B speech evidence surface before Sage replaces Android command STT with sherpa-onnx. */
public class SageSpeechLabActivity extends Activity {
    private TextView backend;
    private TextView evidence;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("Sage Speech Lab");
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(24, 24, 24, 36);
        scroll.addView(content);

        TextView intro = text(18f);
        intro.setText("A / B recognizer evidence\nA is Sage's current Android command recognizer. B is the sherpa-onnx local candidate. Sage will not label B active until both the native engine and verified model pack actually exist.");
        content.addView(intro, matchWrap());

        TextView heading = text(21f);
        heading.setText("Sherpa readiness");
        content.addView(heading, matchWrap());
        backend = text(15f);
        backend.setTextIsSelectable(true);
        content.addView(backend, matchWrap());

        Button refresh = button("Refresh speech evidence");
        refresh.setOnClickListener(v -> refresh());
        content.addView(refresh, matchWrap());
        Button copy = button("Copy speech snapshot");
        copy.setOnClickListener(v -> copySnapshot());
        content.addView(copy, matchWrap());

        TextView evidenceHeading = text(21f);
        evidenceHeading.setText("Recent speech evidence");
        content.addView(evidenceHeading, matchWrap());
        evidence = text(13f);
        evidence.setTextIsSelectable(true);
        content.addView(evidence, matchWrap());
        setContentView(scroll);
        refresh();
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        backend.setText(SageSpeechBackendState.summary(this));
        evidence.setText(filteredSpeechEvidence(SageDiagnostics.buildReport(this)));
    }

    private String filteredSpeechEvidence(String report) {
        if (report == null || report.isEmpty()) return "No speech evidence recorded yet.";
        StringBuilder out = new StringBuilder();
        int kept = 0;
        String[] lines = report.split("\\n");
        for (String line : lines) {
            String lower = line.toLowerCase(Locale.US);
            if (lower.contains(" speech ") || lower.contains(" wake ")
                    || lower.contains(" command ") || lower.contains("listener:")
                    || lower.contains("last heard:") || lower.contains("latency_ms=")
                    || lower.contains("confidence=") || lower.contains("partial")
                    || lower.contains("final") || lower.contains("echo_classification=")
                    || lower.contains("confirmation_required") || lower.contains("low_confidence")) {
                if (out.length() > 0) out.append('\n');
                out.append(line);
                kept++;
            }
        }
        return kept == 0 ? "No wake/command transcript evidence recorded yet." : out.toString();
    }

    private void copySnapshot() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            Toast.makeText(this, "Android clipboard is unavailable.", Toast.LENGTH_LONG).show();
            return;
        }
        String text = "Sage Speech Lab\n\n" + SageSpeechBackendState.summary(this)
                + "\n\nRecent speech evidence\n" + evidence.getText();
        clipboard.setPrimaryClip(ClipData.newPlainText("Sage Speech Lab", text));
        Toast.makeText(this, "Speech snapshot copied.", Toast.LENGTH_SHORT).show();
    }

    private TextView text(float size) {
        TextView value = new TextView(this);
        value.setTextSize(size);
        value.setTextColor(Color.rgb(31, 41, 55));
        value.setPadding(6, 12, 6, 6);
        return value;
    }

    private Button button(String label) {
        Button value = new Button(this);
        value.setText(label);
        value.setAllCaps(false);
        value.setMinHeight(56);
        return value;
    }

    private LinearLayout.LayoutParams matchWrap() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 8, 0, 0);
        return params;
    }
}
'''


if __name__ == "__main__":
    main()
