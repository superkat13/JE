#!/usr/bin/env python3
"""Add a user-initiated, resumable and hash-verified sherpa model pack installer to Speech Lab."""
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
        raise SystemExit("usage: sherpa_model_pack_v1_31.py <reconstructed-source>")
    root = Path(sys.argv[1])
    java = root / "app/src/main/java/com/pineapple/sage"
    state = java / "SageSpeechBackendState.java"
    lab = java / "SageSpeechLabActivity.java"
    if not state.is_file() or not lab.is_file():
        raise SystemExit("Sage sherpa engine/Speech Lab source is missing")

    replace_once(state,
'''    static boolean sherpaModelPresent(Context context) {
        File dir = modelDirectory(context);
        return new File(dir, "tokens.txt").isFile()
                && new File(dir, "encoder-epoch-99-avg-1.int8.onnx").isFile()
                && new File(dir, "decoder-epoch-99-avg-1.onnx").isFile()
                && new File(dir, "joiner-epoch-99-avg-1.int8.onnx").isFile();
    }
''',
'''    static boolean sherpaModelPresent(Context context) {
        File dir = modelDirectory(context);
        return new File(dir, "verified.properties").isFile()
                && exactFile(dir, "tokens.txt", 5_048L)
                && exactFile(dir, "encoder-epoch-99-avg-1.int8.onnx", 42_845_182L)
                && exactFile(dir, "decoder-epoch-99-avg-1.onnx", 2_092_272L)
                && exactFile(dir, "joiner-epoch-99-avg-1.int8.onnx", 259_572L);
    }

    private static boolean exactFile(File dir, String name, long bytes) {
        File value = new File(dir, name);
        return value.isFile() && value.length() == bytes;
    }
''', "verified model marker and sizes")

    replace_once(state,
'''                + "\\nSherpa model pack present: " + yesNo(sherpaModelPresent(context))
                + "\\nSherpa route ready: " + yesNo(sherpaReady(context))
''',
'''                + "\\nVerified model pack present: " + yesNo(sherpaModelPresent(context))
                + "\\nVerified model bytes: 45,202,074"
                + "\\nSherpa route ready: " + yesNo(sherpaReady(context))
''', "model verification summary")

    (java / "SageSherpaModelManager.java").write_text(MODEL_MANAGER, encoding="utf-8")
    lab.write_text(SPEECH_LAB_WITH_MODEL, encoding="utf-8")

    combined = state.read_text(encoding="utf-8") + lab.read_text(encoding="utf-8") + (java / "SageSherpaModelManager.java").read_text(encoding="utf-8")
    required = (
        "verified.properties",
        "45,202,074",
        "Install local command speech (~45 MB)",
        "Verify local command speech",
        "Repair local command speech",
        "Cancel speech model download",
        "d42f2d9f7ca24806fb667456a18a9f1b60f70d16",
        "49e3c2646595fd907228b3c6787069658f67b17377c60aeb8619c4551b2316fb",
        "3810755ce7c3ab26b42a8bcf39d191308fa27fb0f53358823ba46141d03b7eb3",
        "45a7f940ecfb53d89fa270ad11b88b961e53a317203eb24b1c8e95ed208b0f30",
        "e085d73b593cf9b0707f370dbd656d58327d3fe36d80d849202ef81df02cb01e",
        "Range",
        "Apache-2.0",
    )
    for marker in required:
        if marker not in combined:
            raise SystemExit("missing sherpa model-pack marker: " + marker)
    if "/resolve/main/" in combined:
        raise SystemExit("sherpa model downloads must be pinned to the immutable model commit")
    print("Applied Sage 1.31 verified resumable sherpa model-pack installer")


MODEL_MANAGER = r'''package com.pineapple.sage;

import android.content.Context;
import android.os.StatFs;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owner-triggered local command-speech model installer with resume, hashes and atomic activation. */
final class SageSherpaModelManager {
    interface Listener {
        void onProgress(int percent, String detail);
        void onComplete(String detail);
        void onError(String detail);
    }

    private static final String MODEL_COMMIT = "d42f2d9f7ca24806fb667456a18a9f1b60f70d16";
    private static final String MODEL_BASE =
            "https://huggingface.co/csukuangfj/"
                    + "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17/resolve/"
                    + MODEL_COMMIT + "/";
    private static final long TOTAL_BYTES = 45_202_074L;
    private static final long STORAGE_HEADROOM = 64L * 1024L * 1024L;
    private static final int CONNECT_TIMEOUT_MS = 20_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    private static final FileSpec[] FILES = {
            new FileSpec("tokens.txt", 5_048L,
                    "49e3c2646595fd907228b3c6787069658f67b17377c60aeb8619c4551b2316fb"),
            new FileSpec("encoder-epoch-99-avg-1.int8.onnx", 42_845_182L,
                    "3810755ce7c3ab26b42a8bcf39d191308fa27fb0f53358823ba46141d03b7eb3"),
            new FileSpec("decoder-epoch-99-avg-1.onnx", 2_092_272L,
                    "45a7f940ecfb53d89fa270ad11b88b961e53a317203eb24b1c8e95ed208b0f30"),
            new FileSpec("joiner-epoch-99-avg-1.int8.onnx", 259_572L,
                    "e085d73b593cf9b0707f370dbd656d58327d3fe36d80d849202ef81df02cb01e")
    };

    private static final String MODEL_NOTICE =
            "Sage local command speech model\n"
                    + "Model: sherpa-onnx-streaming-zipformer-en-20M-2023-02-17\n"
                    + "Immutable source commit: " + MODEL_COMMIT + "\n"
                    + "Source: csukuangfj on Hugging Face\n"
                    + "License metadata: Apache-2.0\n"
                    + "Exported from desh2608/icefall-asr-librispeech-pruned-transducer-"
                    + "stateless7-streaming-small.\n"
                    + "This notice is installed beside the verified model files and should be preserved.\n";

    private final Context context;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    private volatile boolean running;

    SageSherpaModelManager(Context context) {
        this.context = context.getApplicationContext();
    }

    boolean isRunning() { return running; }
    boolean isInstalled() { return SageSpeechBackendState.sherpaModelPresent(context); }

    void installAsync(Listener listener) {
        if (running) return;
        running = true;
        cancelRequested.set(false);
        worker.execute(() -> {
            try {
                install(listener);
                running = false;
                if (listener != null) listener.onComplete(
                        "Local command speech model verified and activated. Android STT remains the active command backend until the separate routing pass is approved.");
            } catch (CancelledException cancelled) {
                running = false;
                if (listener != null) listener.onError(
                        "Speech model download cancelled. Verified partial bytes were kept for resume.");
            } catch (Throwable problem) {
                running = false;
                String message = "Speech model install failed: " + safeProblem(problem);
                SageDiagnostics.recordError(context, message);
                if (listener != null) listener.onError(message);
            }
        });
    }

    void verifyAsync(Listener listener) {
        if (running) return;
        running = true;
        cancelRequested.set(false);
        worker.execute(() -> {
            try {
                verifyInstalled(listener);
                running = false;
                if (listener != null) listener.onComplete(
                        "Local command speech model verification passed: all four pinned files match size and SHA-256.");
            } catch (Throwable problem) {
                running = false;
                String message = "Speech model verification failed: " + safeProblem(problem);
                SageDiagnostics.recordError(context, message);
                if (listener != null) listener.onError(message);
            }
        });
    }

    void cancel() { cancelRequested.set(true); }

    private void install(Listener listener) throws Exception {
        File target = SageSpeechBackendState.modelDirectory(context);
        File parent = target.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IOException("Android could not create Sage's private speech directory");
        }
        File staging = new File(parent, "." + SageSpeechBackendState.MODEL_ID + ".staging");
        if (!staging.isDirectory() && !staging.mkdirs()) {
            throw new IOException("Android could not create the resumable speech staging directory");
        }
        long remaining = remainingBytes(staging);
        StatFs stat = new StatFs(context.getFilesDir().getAbsolutePath());
        long free = stat.getAvailableBytes();
        if (free < remaining + STORAGE_HEADROOM) {
            throw new IOException("Need about " + formatBytes(remaining + STORAGE_HEADROOM)
                    + " free for verified local command speech; available " + formatBytes(free));
        }

        long done = 0L;
        for (FileSpec spec : FILES) {
            checkCancelled();
            File completed = new File(staging, spec.name);
            if (isExact(completed, spec)) {
                done += spec.bytes;
                progress(listener, done, "Verified " + spec.name);
                continue;
            }
            if (completed.exists() && !completed.delete()) {
                throw new IOException("Could not replace stale staged file " + spec.name);
            }
            downloadOne(staging, spec, done, listener);
            done += spec.bytes;
            progress(listener, done, "Verified " + spec.name);
        }
        writeText(new File(staging, "MODEL-NOTICE.txt"), MODEL_NOTICE);
        writeVerificationMarker(staging);
        activate(staging, target);
        SageDiagnostics.appendEvent(context, "SPEECH MODEL",
                "verified=true model=" + SageSpeechBackendState.MODEL_ID
                        + " bytes=" + TOTAL_BYTES + " commit=" + MODEL_COMMIT);
    }

    private void verifyInstalled(Listener listener) throws Exception {
        File dir = SageSpeechBackendState.modelDirectory(context);
        File marker = new File(dir, "verified.properties");
        if (!marker.isFile()) throw new IOException("verified model marker is missing; repair is required");
        long done = 0L;
        for (FileSpec spec : FILES) {
            checkCancelled();
            File file = new File(dir, spec.name);
            if (!isExact(file, spec)) {
                throw new IOException(spec.name + " does not match Sage's pinned size/SHA-256; repair is required");
            }
            done += spec.bytes;
            progress(listener, done, "Verified " + spec.name);
        }
        SageDiagnostics.appendEvent(context, "SPEECH MODEL",
                "verification=true model=" + SageSpeechBackendState.MODEL_ID
                        + " bytes=" + TOTAL_BYTES);
    }

    private void downloadOne(File staging, FileSpec spec, long doneBefore,
                             Listener listener) throws Exception {
        File part = new File(staging, spec.name + ".part");
        if (part.length() > spec.bytes && !part.delete()) {
            throw new IOException("Could not reset oversized partial file " + spec.name);
        }
        long existing = part.isFile() ? part.length() : 0L;
        URL url = new URL(MODEL_BASE + spec.name + "?download=true");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept-Encoding", "identity");
        if (existing > 0L) connection.setRequestProperty("Range", "bytes=" + existing + "-");
        int response = connection.getResponseCode();
        boolean append = existing > 0L && response == 206;
        if (response < 200 || response >= 300) {
            connection.disconnect();
            throw new IOException("HTTP " + response + " while downloading " + spec.name);
        }
        if (existing > 0L && !append) {
            existing = 0L;
            if (part.exists() && !part.delete()) {
                connection.disconnect();
                throw new IOException("Could not reset non-resumable partial file " + spec.name);
            }
        }
        try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
             FileOutputStream output = new FileOutputStream(part, append)) {
            byte[] buffer = new byte[64 * 1024];
            long written = existing;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                checkCancelled();
                if (read == 0) continue;
                output.write(buffer, 0, read);
                written += read;
                if (written > spec.bytes) {
                    throw new IOException(spec.name + " exceeded the pinned byte size");
                }
                progress(listener, doneBefore + written, "Downloading " + spec.name);
            }
            output.getFD().sync();
        } finally {
            connection.disconnect();
        }
        if (!isExact(part, spec)) {
            throw new IOException(spec.name + " failed pinned size/SHA-256 verification");
        }
        File completed = new File(staging, spec.name);
        if (completed.exists() && !completed.delete()) {
            throw new IOException("Could not replace staged " + spec.name);
        }
        if (!part.renameTo(completed)) {
            throw new IOException("Could not promote verified " + spec.name);
        }
    }

    private long remainingBytes(File staging) {
        long remaining = 0L;
        for (FileSpec spec : FILES) {
            File completed = new File(staging, spec.name);
            File part = new File(staging, spec.name + ".part");
            long have = completed.isFile() ? Math.min(completed.length(), spec.bytes)
                    : part.isFile() ? Math.min(part.length(), spec.bytes) : 0L;
            remaining += Math.max(0L, spec.bytes - have);
        }
        return remaining;
    }

    private void activate(File staging, File target) throws IOException {
        File parent = target.getParentFile();
        if (parent == null) throw new IOException("Speech model parent directory is unavailable");
        File backup = new File(parent, "." + SageSpeechBackendState.MODEL_ID + ".backup");
        deleteTree(backup);
        boolean hadTarget = target.exists();
        if (hadTarget && !target.renameTo(backup)) {
            throw new IOException("Could not move the previous speech model aside for atomic activation");
        }
        if (!staging.renameTo(target)) {
            if (hadTarget) backup.renameTo(target);
            throw new IOException("Could not atomically activate the verified speech model");
        }
        deleteTree(backup);
    }

    private void writeVerificationMarker(File staging) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("model_id", SageSpeechBackendState.MODEL_ID);
        properties.setProperty("source_commit", MODEL_COMMIT);
        properties.setProperty("total_bytes", Long.toString(TOTAL_BYTES));
        properties.setProperty("license", "Apache-2.0");
        for (FileSpec spec : FILES) {
            properties.setProperty(spec.name + ".bytes", Long.toString(spec.bytes));
            properties.setProperty(spec.name + ".sha256", spec.sha256);
        }
        File marker = new File(staging, "verified.properties");
        try (FileOutputStream output = new FileOutputStream(marker, false)) {
            properties.store(output, "Sage verified local command speech model");
            output.getFD().sync();
        }
    }

    private boolean isExact(File file, FileSpec spec) throws Exception {
        return file.isFile() && file.length() == spec.bytes
                && spec.sha256.equalsIgnoreCase(sha256(file));
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        StringBuilder value = new StringBuilder(64);
        for (byte b : digest.digest()) value.append(String.format(Locale.US, "%02x", b & 0xff));
        return value.toString();
    }

    private void progress(Listener listener, long bytes, String detail) {
        if (listener == null) return;
        int percent = (int) Math.max(0L, Math.min(100L, bytes * 100L / TOTAL_BYTES));
        listener.onProgress(percent, detail + " • " + percent + "%");
    }

    private void checkCancelled() throws CancelledException {
        if (cancelRequested.get()) throw new CancelledException();
    }

    private static void writeText(File file, String value) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
    }

    private static void deleteTree(File value) throws IOException {
        if (value == null || !value.exists()) return;
        if (value.isDirectory()) {
            File[] children = value.listFiles();
            if (children != null) for (File child : children) deleteTree(child);
        }
        if (!value.delete()) throw new IOException("Could not remove " + value.getName());
    }

    private static String formatBytes(long value) {
        if (value >= 1024L * 1024L) return String.format(Locale.US, "%.1f MB", value / (1024d * 1024d));
        if (value >= 1024L) return String.format(Locale.US, "%.1f KB", value / 1024d);
        return value + " bytes";
    }

    private static String safeProblem(Throwable problem) {
        String message = problem == null ? "unknown error" : problem.getMessage();
        return message == null || message.trim().isEmpty()
                ? problem.getClass().getSimpleName() : message.replace('\n', ' ').trim();
    }

    private static final class FileSpec {
        final String name;
        final long bytes;
        final String sha256;
        FileSpec(String name, long bytes, String sha256) {
            this.name = name;
            this.bytes = bytes;
            this.sha256 = sha256;
        }
    }

    private static final class CancelledException extends Exception { }
}
'''


SPEECH_LAB_WITH_MODEL = r'''package com.pineapple.sage;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/** Speech evidence plus owner-triggered verified local STT model management. */
public class SageSpeechLabActivity extends Activity {
    private TextView backend;
    private TextView evidence;
    private TextView modelStatus;
    private ProgressBar modelProgress;
    private Button modelAction;
    private Button cancelModel;
    private SageSherpaModelManager modelManager;
    private boolean repairRequested;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("Sage Speech Lab");
        modelManager = new SageSherpaModelManager(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(24, 24, 24, 36);
        scroll.addView(content);

        TextView intro = text(18f);
        intro.setText("A / B recognizer evidence\nA is Sage's current Android command recognizer. B is the sherpa-onnx local candidate. Sage will not label B active until both the native engine and the pinned model pack are physically present and verified.");
        content.addView(intro, matchWrap());

        TextView heading = text(21f);
        heading.setText("Sherpa readiness");
        content.addView(heading, matchWrap());
        backend = text(15f);
        backend.setTextIsSelectable(true);
        content.addView(backend, matchWrap());

        modelAction = button("");
        modelAction.setOnClickListener(v -> runModelAction());
        content.addView(modelAction, matchWrap());
        modelProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        modelProgress.setMax(100);
        modelProgress.setVisibility(View.GONE);
        content.addView(modelProgress, matchWrap());
        modelStatus = text(14f);
        modelStatus.setTextIsSelectable(true);
        content.addView(modelStatus, matchWrap());
        cancelModel = button("Cancel speech model download");
        cancelModel.setVisibility(View.GONE);
        cancelModel.setOnClickListener(v -> {
            modelManager.cancel();
            modelStatus.setText("Cancel requested. Partial model bytes stay in Sage's private staging folder so the next install can resume.");
        });
        content.addView(cancelModel, matchWrap());

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
        if (modelManager == null || modelAction == null) return;
        boolean running = modelManager.isRunning();
        modelAction.setEnabled(!running);
        if (running) {
            modelAction.setText("Installing local command speech…");
            modelProgress.setVisibility(View.VISIBLE);
            cancelModel.setVisibility(View.VISIBLE);
        } else {
            modelProgress.setVisibility(View.GONE);
            cancelModel.setVisibility(View.GONE);
            if (repairRequested) modelAction.setText("Repair local command speech");
            else if (modelManager.isInstalled()) modelAction.setText("Verify local command speech");
            else modelAction.setText("Install local command speech (~45 MB)");
            if (modelStatus.getText() == null || modelStatus.getText().length() == 0) {
                modelStatus.setText(modelManager.isInstalled()
                        ? "The four-file local speech pack is installed. Verify it any time before the command-routing pass."
                        : "No verified local command-speech model pack is installed. Download starts only when you press Install.");
            }
        }
    }

    private void runModelAction() {
        if (modelManager == null || modelManager.isRunning()) return;
        SageSherpaModelManager.Listener listener = new SageSherpaModelManager.Listener() {
            @Override public void onProgress(int percent, String detail) {
                runOnUiThread(() -> {
                    modelProgress.setVisibility(View.VISIBLE);
                    modelProgress.setProgress(percent);
                    modelStatus.setText(detail);
                });
            }
            @Override public void onComplete(String detail) {
                runOnUiThread(() -> {
                    repairRequested = false;
                    modelProgress.setProgress(100);
                    modelStatus.setText(detail);
                    refresh();
                });
            }
            @Override public void onError(String detail) {
                runOnUiThread(() -> {
                    repairRequested = detail != null
                            && detail.toLowerCase(Locale.US).contains("verification failed");
                    modelStatus.setText(detail == null ? "Speech model operation failed." : detail);
                    refresh();
                });
            }
        };
        modelProgress.setProgress(0);
        modelProgress.setVisibility(View.VISIBLE);
        cancelModel.setVisibility(View.VISIBLE);
        if (repairRequested || !modelManager.isInstalled()) modelManager.installAsync(listener);
        else modelManager.verifyAsync(listener);
        refresh();
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
                    || lower.contains("confirmation_required") || lower.contains("low_confidence")
                    || lower.contains("speech model")) {
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
                + "\n\nModel manager\n" + modelStatus.getText()
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

    @Override protected void onDestroy() {
        if (modelManager != null && modelManager.isRunning()) modelManager.cancel();
        super.onDestroy();
    }
}
'''


if __name__ == "__main__":
    main()
