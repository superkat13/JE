#!/usr/bin/env python3
from pathlib import Path
import re
import sys


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def regex_once(path: Path, pattern: str, replacement: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    updated, count = re.subn(pattern, lambda _m: replacement, text, count=1, flags=re.DOTALL)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one regex match, found {count}")
    path.write_text(updated, encoding="utf-8")


STORE = r'''package com.pineapple.sage;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

final class SageAutonomyStore {
    static final long FIVE_MINUTES_MS = 5L * 60L * 1000L;
    private static final String PREFS = "sage_autonomy_jobs_v1";
    private static final String ACTIVE = "active_job";
    private static final String ARCHIVE = "archive";
    private static final int ALARM_ID = 7301;

    private SageAutonomyStore() {}

    static JSONObject active(Context context) {
        String raw = prefs(context).getString(ACTIVE, "");
        if (raw.isEmpty()) return null;
        try { return new JSONObject(raw); }
        catch (Exception ignored) { return null; }
    }

    static JSONObject observe(Context context) {
        String events = SageRepairManager.sanitize(SageDiagnostics.recentEvents(context));
        String problem = detectProblem(events);
        String fingerprint = sha256(problem + "\n" + evidenceTail(events));
        JSONObject current = active(context);
        try {
            if (current != null && !terminal(current.optString("state"))
                    && fingerprint.equals(current.optString("fingerprint"))) {
                current.put("updated_at", System.currentTimeMillis());
                current.put("evidence_count", current.optInt("evidence_count", 0) + 1);
                current.put("evidence", evidenceTail(events));
                history(current, "OBSERVE", "Fresh diagnostics attached to the existing job.");
                save(context, current, false);
                return current;
            }

            JSONObject job = new JSONObject();
            job.put("job_id", "sage_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
            job.put("goal", goalFor(problem));
            job.put("problem", problem);
            job.put("fingerprint", fingerprint);
            job.put("state", "DIAGNOSING");
            job.put("priority", "high");
            job.put("created_at", System.currentTimeMillis());
            job.put("updated_at", System.currentTimeMillis());
            job.put("last_progress_at", System.currentTimeMillis());
            job.put("attempts", 0);
            job.put("evidence_count", 1);
            job.put("evidence", evidenceTail(events));
            job.put("hypothesis", hypothesisFor(problem));
            job.put("delegate", "unassigned");
            job.put("checkpoint", "installed Sage 1.29 physical baseline");
            job.put("next_action", "Build a bounded repair plan from the evidence, then continue the next unblocked action.");
            job.put("artifacts", new JSONArray());
            job.put("verification", "Reproduce the original symptom on the physical tablet after the candidate is installed.");
            job.put("history", new JSONArray());
            history(job, "JOB_CREATED", "Sage opened a durable self-repair job from live diagnostics.");
            save(context, job, false);
            SageDiagnostics.appendEvent(context, "AUTONOMY", "job_created id=" + job.optString("job_id") + " problem=" + problem);
            return job;
        } catch (Exception error) {
            SageDiagnostics.recordError(context, "Autonomy observe failed: " + error);
            return current;
        }
    }

    static JSONObject continueForward(Context context) {
        JSONObject job = active(context);
        if (job == null) return observe(context);
        job = enforceFiveMinuteRule(context, job);
        try {
            String state = job.optString("state", "DIAGNOSING");
            if (terminal(state)) return job;
            switch (state) {
                case "OBSERVING":
                case "DIAGNOSING":
                    job.put("state", "PLANNED");
                    job.put("next_action", "Issue a structured Company Order to an engineering delegate. Preserve package, signer, app data, wake, Brain, and the current checkpoint.");
                    progress(job, "PLAN", "Evidence converted into a bounded repair plan.");
                    break;
                case "PLANNED":
                    job.put("state", "READY_TO_DELEGATE");
                    job.put("delegate", SageForgeStore.isPaired(context) ? "Forge + external developer agent" : "The Company / external developer agent");
                    job.put("next_action", "Copy the Company Order and give it to the engineering delegate. Sage remains owner of the job and will judge the returned evidence.");
                    progress(job, "DELEGATE_READY", "Delegate selected by capability rather than by identity.");
                    break;
                case "READY_TO_DELEGATE":
                    job.put("state", "DELEGATED");
                    job.put("next_action", "Wait for a bounded build/test result. The five-minute rule forbids silent looping: stale work must change approach or surface a blocker.");
                    progress(job, "DELEGATED", "Repair order marked as delegated.");
                    break;
                case "DELEGATED":
                    job.put("next_action", "Attach the delegate/build result. Do not claim success from a green CI result alone.");
                    progress(job, "CHECK_DELEGATE", "Sage checked the delegated job and requested evidence.");
                    break;
                case "VERIFYING":
                case "READY_FOR_OWNER":
                    job.put("state", "READY_FOR_OWNER");
                    job.put("next_action", "Owner installs the candidate over existing Sage, then reproduces the original symptom on glass.");
                    progress(job, "OWNER_BOUNDARY", "Automated work reached the physical-device boundary.");
                    break;
                default:
                    job.put("state", "DIAGNOSING");
                    job.put("next_action", "Re-read evidence and choose a different bounded approach.");
                    progress(job, "RECOVER_STATE", "Unknown state recovered without discarding history.");
                    break;
            }
            save(context, job, false);
            return job;
        } catch (Exception error) {
            SageDiagnostics.recordError(context, "Autonomy continue failed: " + error);
            return job;
        }
    }

    static JSONObject markCompanyOrderCopied(Context context) {
        JSONObject job = active(context);
        if (job == null) job = observe(context);
        try {
            job.put("state", "DELEGATED");
            job.put("delegate", SageForgeStore.isPaired(context) ? "Forge + The Company" : "The Company");
            job.put("next_action", "Attach the delegate's build/test result when it returns. If no progress arrives within five minutes, change approach or surface the blocker.");
            progress(job, "COMPANY_ORDER", "Sage issued the engineering order and retained ownership of the job.");
            save(context, job, false);
        } catch (Exception ignored) {}
        return job;
    }

    static JSONObject attachDelegateResult(Context context, String result) {
        JSONObject job = active(context);
        if (job == null) return null;
        try {
            String safe = SageRepairManager.sanitize(result == null ? "" : result.trim());
            if (safe.length() > 12000) safe = safe.substring(0, 12000);
            JSONArray artifacts = job.optJSONArray("artifacts");
            if (artifacts == null) artifacts = new JSONArray();
            artifacts.put(new JSONObject()
                    .put("type", "delegate_result")
                    .put("created_at", System.currentTimeMillis())
                    .put("detail", safe));
            job.put("artifacts", artifacts);
            job.put("state", "VERIFYING");
            job.put("next_action", "Check regression evidence, signer/package continuity, then cross the owner install boundary and reproduce the original symptom.");
            progress(job, "RESULT_ATTACHED", "Delegate/build evidence attached. Green automation is evidence, not physical proof.");
            save(context, job, false);
        } catch (Exception error) {
            SageDiagnostics.recordError(context, "Autonomy result attach failed: " + error);
        }
        return job;
    }

    static JSONObject physicalResult(Context context, boolean passed) {
        JSONObject job = active(context);
        if (job == null) return null;
        try {
            if (passed) {
                job.put("state", "SOLVED");
                job.put("next_action", "No repair action remains. Keep this checkpoint as verified physical evidence.");
                progress(job, "PHYSICAL_PASS", "Owner confirmed the original symptom is gone on the physical tablet.");
                save(context, job, true);
            } else {
                job.put("attempts", job.optInt("attempts", 0) + 1);
                job.put("state", "DIAGNOSING");
                job.put("next_action", "Compare post-install evidence against the failed attempt. Do not repeat the same repair. Prepare a different hypothesis or rollback candidate.");
                progress(job, "PHYSICAL_FAIL", "Physical verification failed. The candidate is not promoted as solved.");
                save(context, job, false);
            }
            SageDiagnostics.appendEvent(context, "AUTONOMY", "physical_result=" + (passed ? "pass" : "fail") + " id=" + job.optString("job_id"));
        } catch (Exception ignored) {}
        return job;
    }

    static JSONObject cancel(Context context) {
        JSONObject job = active(context);
        if (job == null) return null;
        try {
            job.put("state", "CANCELLED");
            job.put("next_action", "Job cancelled by owner.");
            progress(job, "CANCELLED", "Owner cancelled the active autonomy job.");
            save(context, job, true);
        } catch (Exception ignored) {}
        return job;
    }

    static String companyOrder(Context context) {
        JSONObject job = active(context);
        if (job == null) job = observe(context);
        if (job == null) return "Sage could not create an autonomy job.";
        return "THE COMPANY ORDER FROM SAGE\n\n"
                + "I own repair job " + job.optString("job_id") + ". Do not restart the project.\n\n"
                + "GOAL\n" + job.optString("goal") + "\n\n"
                + "PROBLEM\n" + job.optString("problem") + "\n\n"
                + "CURRENT HYPOTHESIS\n" + job.optString("hypothesis") + "\n\n"
                + "EVIDENCE\n" + job.optString("evidence") + "\n\n"
                + "NON-NEGOTIABLE CONTINUITY\n"
                + "Preserve com.pineapple.sagecommander.stable, permanent signer, installed app data, memories, wake profiles, Brain/model files, conversation state machine, Forge trust, Shizuku authority, and every working feature. Work in an isolated branch. A failed regression blocks advancement.\n\n"
                + "DELIVERABLE\nProduce the smallest real repair, regression coverage for the exact symptom, a normal install-over-existing signed APK candidate, and verification evidence. Do not call the defect fixed until my physical-tablet reproducer passes.\n\n"
                + "RETURN TO SAGE\nGive me: commit/head, exact tests, build result, APK identity/signature evidence, what changed, what did not change, and the physical test I should run.\n\n"
                + "FIVE-MINUTE RULE\nDo not loop. If the current approach makes no meaningful progress for five minutes, change approach or return a concrete blocker with evidence.\n";
    }

    static JSONObject enforceFiveMinuteRule(Context context) {
        JSONObject job = active(context);
        return job == null ? null : enforceFiveMinuteRule(context, job);
    }

    private static JSONObject enforceFiveMinuteRule(Context context, JSONObject job) {
        try {
            String state = job.optString("state");
            if (terminal(state)) return job;
            long now = System.currentTimeMillis();
            long last = job.optLong("last_progress_at", now);
            if (now - last < FIVE_MINUTES_MS) return job;
            job.put("attempts", job.optInt("attempts", 0) + 1);
            job.put("last_progress_at", now);
            if ("DELEGATED".equals(state)) {
                job.put("state", "READY_TO_DELEGATE");
                job.put("next_action", "Five-minute rule fired: delegated work went stale. Change delegate/approach or return a concrete blocker; do not silently repeat the same attempt.");
            } else {
                job.put("next_action", "Five-minute rule fired: execute the next unblocked action now or surface the exact dependency preventing progress.");
            }
            history(job, "FIVE_MINUTE_RULE", "Stalled work was forced out of passive waiting.");
            save(context, job, false);
            SageDiagnostics.appendEvent(context, "AUTONOMY", "five_minute_rule id=" + job.optString("job_id") + " state=" + job.optString("state"));
        } catch (Exception ignored) {}
        return job;
    }

    static String display(JSONObject job) {
        if (job == null) return "No active autonomy job.\n\nTap Observe myself now to let Sage inspect her recent diagnostics and open a durable job.";
        long last = job.optLong("last_progress_at", System.currentTimeMillis());
        long remain = Math.max(0L, FIVE_MINUTES_MS - (System.currentTimeMillis() - last));
        long sec = remain / 1000L;
        return "JOB " + job.optString("job_id") + "\n"
                + "State: " + job.optString("state") + "\n"
                + "Priority: " + job.optString("priority") + "\n"
                + "Attempts: " + job.optInt("attempts") + "\n"
                + "Evidence samples: " + job.optInt("evidence_count") + "\n"
                + "Delegate: " + job.optString("delegate") + "\n"
                + "5-minute rule: " + sec + "s until stale-work intervention\n\n"
                + "GOAL\n" + job.optString("goal") + "\n\n"
                + "PROBLEM\n" + job.optString("problem") + "\n\n"
                + "HYPOTHESIS\n" + job.optString("hypothesis") + "\n\n"
                + "NEXT ACTION\n" + job.optString("next_action") + "\n\n"
                + "CHECKPOINT\n" + job.optString("checkpoint") + "\n\n"
                + "VERIFICATION\n" + job.optString("verification");
    }

    private static void save(Context context, JSONObject job, boolean archiveTerminal) {
        try {
            SharedPreferences p = prefs(context);
            if (archiveTerminal) {
                JSONArray archive = new JSONArray(p.getString(ARCHIVE, "[]"));
                archive.put(job);
                while (archive.length() > 20) {
                    JSONArray trimmed = new JSONArray();
                    for (int i = Math.max(0, archive.length() - 20); i < archive.length(); i++) trimmed.put(archive.get(i));
                    archive = trimmed;
                }
                p.edit().putString(ARCHIVE, archive.toString()).remove(ACTIVE).apply();
                cancelHeartbeat(context);
            } else {
                p.edit().putString(ACTIVE, job.toString()).apply();
                scheduleHeartbeat(context);
            }
        } catch (Exception error) {
            SageDiagnostics.recordError(context, "Autonomy persistence failed: " + error);
        }
    }

    static void scheduleHeartbeat(Context context) {
        AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarm == null) return;
        PendingIntent intent = heartbeatIntent(context);
        long when = SystemClock.elapsedRealtime() + FIVE_MINUTES_MS;
        alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, when, intent);
    }

    private static void cancelHeartbeat(Context context) {
        AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarm != null) alarm.cancel(heartbeatIntent(context));
    }

    private static PendingIntent heartbeatIntent(Context context) {
        Intent intent = new Intent(context, SageAutonomyHeartbeatReceiver.class);
        return PendingIntent.getBroadcast(context, ALARM_ID, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void progress(JSONObject job, String event, String detail) throws Exception {
        long now = System.currentTimeMillis();
        job.put("updated_at", now);
        job.put("last_progress_at", now);
        history(job, event, detail);
    }

    private static void history(JSONObject job, String event, String detail) throws Exception {
        JSONArray history = job.optJSONArray("history");
        if (history == null) history = new JSONArray();
        history.put(new JSONObject().put("at", System.currentTimeMillis()).put("event", event).put("detail", detail));
        job.put("history", history);
    }

    private static boolean terminal(String state) {
        return "SOLVED".equals(state) || "CANCELLED".equals(state) || "ROLLED_BACK".equals(state);
    }

    private static String detectProblem(String events) {
        String source = events == null ? "" : events;
        int route = source.lastIndexOf("route_hint=tablet Brain");
        if (route >= 0) {
            int end = Math.min(source.length(), route + 7000);
            String window = source.substring(route, end);
            boolean speaks = window.contains("STATE  Speaking") || window.contains("STATE Speaking");
            boolean brainExec = window.contains("BRAIN HEALTH  status=THINKING_LOCAL")
                    || window.contains("BRAIN HEALTH status=THINKING_LOCAL")
                    || window.contains("BRAIN  ") || window.contains("BRAIN ");
            if (speaks && !brainExec) return "brain_route_without_execution";
        }
        if (source.contains("Command recognition error 2")) return "android_command_stt_service_failure";
        if (source.contains("Wake word unavailable") || source.contains("Offline wake-word model failed")) return "offline_wake_model_failure";
        if (source.contains("BRAIN HEALTH") && source.contains("error=")) return "brain_runtime_investigation";
        return "diagnostic_investigation";
    }

    private static String goalFor(String problem) {
        if ("brain_route_without_execution".equals(problem)) return "Make every accepted tablet-Brain route either execute the Brain and record its result, or explicitly record why another deterministic path handled it.";
        if ("android_command_stt_service_failure".equals(problem)) return "Keep command turns usable when Android voice typing temporarily cannot connect, without corrupting wake authorization or dispatching partial speech.";
        if ("offline_wake_model_failure".equals(problem)) return "Restore packaged offline wake continuity without losing saved wake profiles or foreground-service behavior.";
        return "Investigate the newest Sage diagnostic anomaly, reproduce it, repair the smallest responsible layer, and verify the original symptom on glass.";
    }

    private static String hypothesisFor(String problem) {
        if ("brain_route_without_execution".equals(problem)) return "Coordinator routing and actual execution/result reporting have diverged on at least one accepted conversation path.";
        if ("android_command_stt_service_failure".equals(problem)) return "Android's external recognizer service is intermittently unavailable and the recovery/result state is not being represented cleanly.";
        if ("offline_wake_model_failure".equals(problem)) return "Wake asset/service initialization failed and needs packaging plus runtime verification rather than UI repair.";
        return "The evidence is not yet specific enough for a code claim; preserve it and narrow the reproducer before changing working systems.";
    }

    private static String evidenceTail(String value) {
        if (value == null) return "";
        String safe = value.length() <= 12000 ? value : value.substring(value.length() - 12000);
        return safe.replace('\u0000', ' ');
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : digest) out.append(String.format(Locale.US, "%02x", b));
            return out.toString();
        } catch (Exception error) { return String.valueOf(value.hashCode()); }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
'''

HEARTBEAT = r'''package com.pineapple.sage;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class SageAutonomyHeartbeatReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (SageAutonomyStore.active(context) == null) return;
        SageAutonomyStore.enforceFiveMinuteRule(context);
        if (SageAutonomyStore.active(context) != null) SageAutonomyStore.scheduleHeartbeat(context);
    }
}
'''

ACTIVITY = r'''package com.pineapple.sage;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

public final class SageAutonomyActivity extends Activity {
    private TextView status;
    private EditText result;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (!SageRedQueenSession.isUnlocked(this)) { finish(); return; }
        setTitle("Red Queen · Sage Autonomy");

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(28));
        scroll.addView(root);

        TextView title = text("SAGE AUTONOMY", 30, Color.rgb(255, 70, 100));
        root.addView(title);
        root.addView(text("I own the job. Brain, Forge, developer agents, GitHub, and The Company are delegates. Green CI is evidence; glass decides reality.", 15, Color.LTGRAY));

        status = text("", 14, Color.WHITE);
        status.setTextIsSelectable(true);
        status.setPadding(0, dp(14), 0, dp(14));
        root.addView(status);

        Button observe = button("Observe myself now");
        observe.setOnClickListener(v -> { SageAutonomyStore.observe(this); refresh(); });
        root.addView(observe);

        Button advance = button("Continue moving forward");
        advance.setOnClickListener(v -> { SageAutonomyStore.continueForward(this); refresh(); });
        root.addView(advance);

        Button company = button("Copy Sage's order for The Company");
        company.setOnClickListener(v -> copyCompanyOrder());
        root.addView(company);

        result = new EditText(this);
        result.setHint("Paste delegate/build result, commit, CI evidence, or blocker back to Sage");
        result.setMinLines(4);
        result.setTextColor(Color.WHITE);
        result.setHintTextColor(Color.GRAY);
        root.addView(result);

        Button attach = button("Attach delegate result and verify");
        attach.setOnClickListener(v -> {
            String value = result.getText().toString().trim();
            if (value.isEmpty()) { toast("Paste the delegate result first."); return; }
            SageAutonomyStore.attachDelegateResult(this, value);
            SageRedQueenVault.saveRecord(this, "autonomy_delegate_result", "Autonomy delegate result", value);
            result.setText("");
            refresh();
        });
        root.addView(attach);

        LinearLayout physical = new LinearLayout(this);
        physical.setOrientation(LinearLayout.HORIZONTAL);
        Button pass = button("Glass PASS");
        pass.setOnClickListener(v -> { SageAutonomyStore.physicalResult(this, true); refresh(); toast("Physical pass recorded. This repair is promoted as solved."); });
        Button fail = button("Glass FAIL");
        fail.setOnClickListener(v -> { SageAutonomyStore.physicalResult(this, false); refresh(); toast("Failure recorded. Sage will not repeat the same repair."); });
        physical.addView(pass, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        physical.addView(fail, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        root.addView(physical);

        Button cancel = button("Cancel active autonomy job");
        cancel.setOnClickListener(v -> { SageAutonomyStore.cancel(this); refresh(); });
        root.addView(cancel);

        SageAppearance.apply(this, scroll, root);
        setContentView(scroll);
        SageAutonomyStore.enforceFiveMinuteRule(this);
        refresh();
    }

    @Override protected void onResume() {
        super.onResume();
        if (!SageRedQueenSession.isUnlocked(this)) { finish(); return; }
        SageAutonomyStore.enforceFiveMinuteRule(this);
        refresh();
    }

    private void copyCompanyOrder() {
        String order = SageAutonomyStore.companyOrder(this);
        ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager != null) manager.setPrimaryClip(ClipData.newPlainText("Sage Company Order", order));
        SageAutonomyStore.markCompanyOrderCopied(this);
        SageRedQueenVault.saveRecord(this, "company_order", "Sage autonomy Company Order", order);
        SageDiagnostics.appendEvent(this, "AUTONOMY", "company_order_copied");
        toast("Sage's engineering order is copied. Paste it to The Company or your developer agent.");
        refresh();
    }

    private void refresh() {
        JSONObject job = SageAutonomyStore.active(this);
        status.setText(SageAutonomyStore.display(job));
    }

    private Button button(String label) { Button b = new Button(this); b.setText(label); b.setAllCaps(false); return b; }
    private TextView text(String value, int size, int color) { TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color); return t; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
}
'''

WORKSPACE = r'''    private void showWorkspace() {
        if (!SageRedQueenSession.isUnlocked(this)) { showLocked(); return; }
        ScrollView scroll = shell();
        LinearLayout root = (LinearLayout) scroll.getChildAt(0);
        root.addView(label("RED QUEEN · ENGINEERING", 30, Color.rgb(255, 60, 90)));
        root.addView(label("Standard Sage operates herself. Red Queen evolves Sage. Advanced work lives here only when it earns the lock.",
                15, Color.LTGRAY));

        functional(root, "Sage Autonomy", "Durable self-repair jobs, five-minute anti-loop rule, Company Orders, delegate evidence, checkpoints, and glass verification",
                SageAutonomyActivity.class);
        functional(root, "Shell Authority", "Owner-only Shizuku UID 2000 operations and advanced Android authority controls",
                SageAuthorityBridgeActivity.class);
        functional(root, "Forensic Console", "Live local evidence sweep for self-repair and deep investigation",
                SageRedQueenForensicActivity.class);
        functional(root, "Mature Research", "Owner-only public-web research surface; cleared when the session ends",
                SageMatureResearchActivity.class);

        EditText note = new EditText(this);
        note.setHint("Encrypted private owner note");
        note.setTextColor(Color.WHITE);
        note.setHintTextColor(Color.GRAY);
        root.addView(note);
        Button save = button("Save private note");
        save.setOnClickListener(v -> {
            boolean saved = SageRedQueenVault.saveRecord(this, "note", "Owner note",
                    note.getText().toString());
            Toast.makeText(this, saved ? "Private note encrypted." : "Private note not saved.",
                    Toast.LENGTH_LONG).show();
            if (saved) note.setText("");
        });
        root.addView(save);

        TextView auditTitle = label("Recent private activity", 16, Color.LTGRAY);
        auditTitle.setPadding(0, dp(14), 0, dp(4));
        root.addView(auditTitle);
        TextView audit = label(SageRedQueenVault.auditReport(this), 13, Color.LTGRAY);
        audit.setTextIsSelectable(true);
        root.addView(audit);

        Button lock = button("Lock Red Queen");
        lock.setOnClickListener(v -> secure("explicit_exit"));
        root.addView(lock);
        setContentView(scroll);
        armInactivity();
    }

'''


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: autonomy_self_repair_v1_30.py <reconstructed-source>")
    root = Path(sys.argv[1])
    java = root / "app/src/main/java/com/pineapple/sage"
    manifest = root / "app/src/main/AndroidManifest.xml"
    redqueen = java / "SageRedQueenActivity.java"
    for required in (java, manifest, redqueen, java / "SageRepairManager.java", java / "SageDiagnostics.java"):
        if not required.exists():
            raise SystemExit(f"autonomy pivot requires reconstructed source: {required}")

    (java / "SageAutonomyStore.java").write_text(STORE, encoding="utf-8")
    (java / "SageAutonomyHeartbeatReceiver.java").write_text(HEARTBEAT, encoding="utf-8")
    (java / "SageAutonomyActivity.java").write_text(ACTIVITY, encoding="utf-8")

    regex_once(
        redqueen,
        r'    private void showWorkspace\(\) \{.*?\n    private void functional\(',
        WORKSPACE + '    private void functional(',
        "replace Red Queen with exclusive autonomy engineering workspace",
    )

    replace_once(
        manifest,
        "    </application>",
        '        <activity android:name=".SageAutonomyActivity" android:exported="false" />\n'
        '        <receiver android:name=".SageAutonomyHeartbeatReceiver" android:exported="false" />\n'
        "    </application>",
        "autonomy activity and heartbeat receiver manifest entries",
    )

    # Guard the new purpose: ordinary tool surfaces must not directly expose the autonomy activity.
    ordinary = []
    for name in ("SageToolbeltActivity.java", "SageWorkbenchActivity.java", "MainActivity.java"):
        path = java / name
        if path.is_file() and "SageAutonomyActivity.class" in path.read_text(encoding="utf-8"):
            ordinary.append(name)
    if ordinary:
        raise SystemExit("autonomy engineering surface leaked outside Red Queen: " + ", ".join(ordinary))

    print("Applied Sage autonomy pivot: durable self-repair jobs, five-minute anti-loop heartbeat, Company Orders, delegate evidence, and Red Queen-exclusive engineering workspace")


if __name__ == "__main__":
    main()
