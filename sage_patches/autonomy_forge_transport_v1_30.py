#!/usr/bin/env python3
"""Wire Sage-owned autonomy jobs to the paired Forge fixed outbox/result inbox.

This is additive to the 1.29 autonomy pivot. Clipboard handoff remains a fallback, but a
paired Forge becomes the primary bounded transport. No arbitrary command, path, or shell
surface is added to Commander.
"""
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
        raise SystemExit("usage: autonomy_forge_transport_v1_30.py <reconstructed-source>")
    root = Path(sys.argv[1])
    java = root / "app/src/main/java/com/pineapple/sage"
    client = java / "SageForgeClient.java"
    store = java / "SageAutonomyStore.java"
    activity = java / "SageAutonomyActivity.java"
    for required in (client, store, activity):
        if not required.is_file():
            raise SystemExit(f"missing reconstructed source: {required.name}")

    client_anchor = '''    void startSystemInfo(Callback callback){try{JSONObject approval=new JSONObject().put("surface","sage_commander").put("action","Read approved Dell system information");JSONObject body=new JSONObject().put("tool_id","system.info").put("input",new JSONObject()).put("owner_approved",true).put("approval_context",approval);authenticated("POST","/v1/jobs",body,callback);}catch(Exception e){fail(callback,e);}}\n'''
    client_add = client_anchor + '''    void startAutonomyDispatch(String sageJobId,String fingerprint,String order,Callback callback){try{JSONObject approval=new JSONObject().put("surface","sage_commander").put("action","Queue Sage-owned engineering job in paired Forge outbox");JSONObject input=new JSONObject().put("job_id",sageJobId).put("fingerprint",fingerprint).put("order",order);JSONObject body=new JSONObject().put("tool_id","developer.autonomy_dispatch").put("input",input).put("owner_approved",true).put("approval_context",approval);authenticated("POST","/v1/jobs",body,callback);}catch(Exception e){fail(callback,e);}}\n    void startAutonomyResult(String sageJobId,Callback callback){try{JSONObject approval=new JSONObject().put("surface","sage_commander").put("action","Read structured result for Sage-owned engineering job");JSONObject input=new JSONObject().put("job_id",sageJobId);JSONObject body=new JSONObject().put("tool_id","developer.autonomy_result").put("input",input).put("owner_approved",true).put("approval_context",approval);authenticated("POST","/v1/jobs",body,callback);}catch(Exception e){fail(callback,e);}}\n'''
    replace_once(client, client_anchor, client_add, "Forge autonomy client methods")

    store_anchor = '''    static JSONObject attachDelegateResult(Context context, String result) {\n'''
    store_methods = r'''    static JSONObject markForgeDispatched(Context context, JSONObject result) {
        JSONObject job = active(context);
        if (job == null || result == null) return job;
        try {
            if (!job.optString("job_id").equals(result.optString("job_id"))) {
                SageDiagnostics.recordError(context, "Forge autonomy dispatch returned a different Sage job ID");
                return job;
            }
            JSONArray artifacts = job.optJSONArray("artifacts");
            if (artifacts == null) artifacts = new JSONArray();
            artifacts.put(new JSONObject()
                    .put("type", "forge_dispatch")
                    .put("created_at", System.currentTimeMillis())
                    .put("order_sha256", result.optString("order_sha256"))
                    .put("outbox_json", result.optString("outbox_json"))
                    .put("project_head", result.optString("project_head"))
                    .put("project_branch", result.optString("project_branch")));
            job.put("artifacts", artifacts);
            job.put("state", "DELEGATED");
            job.put("delegate", "paired Forge autonomy outbox");
            job.put("next_action", "Forge has Sage's job. Check the paired Forge result inbox. If no meaningful result arrives within five minutes, change approach or surface the blocker.");
            progress(job, "FORGE_DISPATCHED", "Sage delivered her engineering order directly to the paired Forge fixed outbox.");
            save(context, job, false);
            SageDiagnostics.appendEvent(context, "AUTONOMY", "forge_dispatch id=" + job.optString("job_id") + " sha=" + result.optString("order_sha256"));
        } catch (Exception error) {
            SageDiagnostics.recordError(context, "Forge autonomy dispatch attach failed: " + error);
        }
        return job;
    }

    static JSONObject attachForgeResult(Context context, JSONObject envelope) {
        JSONObject job = active(context);
        if (job == null || envelope == null) return job;
        try {
            if (!job.optString("job_id").equals(envelope.optString("job_id"))) {
                SageDiagnostics.recordError(context, "Forge autonomy inbox returned a different Sage job ID");
                return job;
            }
            String status = envelope.optString("status");
            if ("waiting".equals(status)) {
                job.put("updated_at", System.currentTimeMillis());
                job.put("next_action", "Forge result is not ready yet. The five-minute rule remains active; do not reset the progress clock for waiting alone.");
                history(job, "FORGE_WAITING", "Checked the fixed Forge result inbox; no result is ready yet.");
                save(context, job, false);
                return job;
            }
            JSONObject result = envelope.optJSONObject("result");
            if (result == null) result = new JSONObject();
            JSONArray artifacts = job.optJSONArray("artifacts");
            if (artifacts == null) artifacts = new JSONArray();
            artifacts.put(new JSONObject()
                    .put("type", "forge_result")
                    .put("created_at", System.currentTimeMillis())
                    .put("result_sha256", envelope.optString("result_sha256"))
                    .put("result_file", envelope.optString("result_file"))
                    .put("detail", result.toString()));
            job.put("artifacts", artifacts);
            job.put("delegate", "paired Forge result inbox");
            if ("blocked".equals(status)) {
                job.put("attempts", job.optInt("attempts", 0) + 1);
                job.put("state", "READY_TO_DELEGATE");
                String blocker = result.optString("blocker", "Forge returned a blocker without detail.");
                job.put("next_action", "Forge returned a concrete blocker: " + blocker + " Change the approach or delegate; do not repeat the blocked attempt.");
                progress(job, "FORGE_BLOCKED", "Structured Forge result returned a blocker and forced a new approach.");
            } else if ("ready".equals(status)) {
                job.put("state", "VERIFYING");
                job.put("next_action", "Sage has reclaimed the structured Forge result. Verify commit/tests/APK continuity, then install over existing Sage and reproduce the original symptom on glass.");
                progress(job, "FORGE_RESULT_READY", "Structured Forge engineering evidence returned directly to Sage.");
            } else {
                SageDiagnostics.recordError(context, "Unknown Forge autonomy result status: " + status);
                return job;
            }
            save(context, job, false);
            SageDiagnostics.appendEvent(context, "AUTONOMY", "forge_result id=" + job.optString("job_id") + " status=" + status);
        } catch (Exception error) {
            SageDiagnostics.recordError(context, "Forge autonomy result attach failed: " + error);
        }
        return job;
    }

'''
    replace_once(store, store_anchor, store_methods + store_anchor, "autonomy Forge result state methods")

    replace_once(
        activity,
        'import android.graphics.Color;\nimport android.os.Bundle;\n',
        'import android.graphics.Color;\nimport android.os.Bundle;\nimport android.os.Handler;\nimport android.os.Looper;\n',
        "autonomy Forge Handler imports",
    )
    replace_once(
        activity,
        '''public final class SageAutonomyActivity extends Activity {\n    private TextView status;\n    private EditText result;\n''',
        '''public final class SageAutonomyActivity extends Activity {\n    private final Handler handler = new Handler(Looper.getMainLooper());\n    private TextView status;\n    private EditText result;\n    private SageForgeClient forge;\n    private boolean forgeBusy;\n''',
        "autonomy Forge fields",
    )
    replace_once(
        activity,
        '''        setTitle("Red Queen · Sage Autonomy");\n\n        ScrollView scroll = new ScrollView(this);''',
        '''        setTitle("Red Queen · Sage Autonomy");\n        forge = new SageForgeClient(this);\n\n        ScrollView scroll = new ScrollView(this);''',
        "autonomy Forge client init",
    )
    company_anchor = '''        Button company = button("Copy Sage's order for The Company");\n        company.setOnClickListener(v -> copyCompanyOrder());\n        root.addView(company);\n\n'''
    company_replacement = company_anchor + '''        Button sendForge = button("Send this job to paired Forge");\n        sendForge.setOnClickListener(v -> sendToForge());\n        root.addView(sendForge);\n\n        Button checkForge = button("Check paired Forge for result");\n        checkForge.setOnClickListener(v -> checkForgeResult());\n        root.addView(checkForge);\n\n'''
    replace_once(activity, company_anchor, company_replacement, "autonomy paired Forge buttons")

    refresh_anchor = '''    private void refresh() {\n        JSONObject job = SageAutonomyStore.active(this);\n        status.setText(SageAutonomyStore.display(job));\n    }\n\n'''
    helpers = r'''    private interface ForgeResultConsumer { void accept(JSONObject result); }

    private void sendToForge() {
        if (forgeBusy) { toast("Forge job already in progress."); return; }
        if (!SageForgeStore.isPaired(this)) { toast("Pair Sage with Forge first."); return; }
        JSONObject job = SageAutonomyStore.active(this);
        if (job == null) { job = SageAutonomyStore.observe(this); refresh(); }
        if (job == null) { toast("Sage could not open an autonomy job."); return; }
        final JSONObject owned = job;
        forgeBusy = true;
        status.setText(SageAutonomyStore.display(job) + "\n\nFORGE\nQueuing Sage's engineering order…");
        forge.startAutonomyDispatch(job.optString("job_id"), job.optString("fingerprint"),
                SageAutonomyStore.companyOrder(this), new SageForgeClient.Callback() {
            @Override public void complete(JSONObject value) {
                String forgeJob = value.optString("job_id");
                if (forgeJob.isEmpty()) { forgeFailure("Forge returned no transport job ID."); return; }
                pollForge(forgeJob, result -> {
                    forgeBusy = false;
                    if (!owned.optString("job_id").equals(result.optString("job_id"))) {
                        forgeFailure("Forge dispatch result did not match Sage's job."); return;
                    }
                    SageAutonomyStore.markForgeDispatched(SageAutonomyActivity.this, result);
                    refresh();
                    toast("Sage handed the job directly to paired Forge.");
                });
            }
            @Override public void failed(String detail) { forgeFailure("Forge dispatch failed: " + detail); }
        });
    }

    private void checkForgeResult() {
        if (forgeBusy) { toast("Forge job already in progress."); return; }
        if (!SageForgeStore.isPaired(this)) { toast("Pair Sage with Forge first."); return; }
        JSONObject job = SageAutonomyStore.active(this);
        if (job == null) { toast("There is no active Sage autonomy job."); return; }
        final String sageJobId = job.optString("job_id");
        forgeBusy = true;
        status.setText(SageAutonomyStore.display(job) + "\n\nFORGE\nChecking Sage's fixed result inbox…");
        forge.startAutonomyResult(sageJobId, new SageForgeClient.Callback() {
            @Override public void complete(JSONObject value) {
                String forgeJob = value.optString("job_id");
                if (forgeJob.isEmpty()) { forgeFailure("Forge returned no result-check job ID."); return; }
                pollForge(forgeJob, result -> {
                    forgeBusy = false;
                    SageAutonomyStore.attachForgeResult(SageAutonomyActivity.this, result);
                    refresh();
                    String state = result.optString("status");
                    if ("ready".equals(state)) toast("Forge result returned directly to Sage. Verification is next.");
                    else if ("blocked".equals(state)) toast("Forge returned a blocker. Sage changed the next move.");
                    else toast("No Forge result yet. The five-minute rule is still ticking.");
                });
            }
            @Override public void failed(String detail) { forgeFailure("Forge result check failed: " + detail); }
        });
    }

    private void pollForge(String forgeJobId, ForgeResultConsumer consumer) {
        forge.job(forgeJobId, new SageForgeClient.Callback() {
            @Override public void complete(JSONObject value) {
                String state = value.optString("status");
                if ("completed".equals(state)) {
                    JSONObject structured = value.optJSONObject("result");
                    consumer.accept(structured == null ? new JSONObject() : structured);
                } else if ("failed".equals(state) || "cancelled".equals(state) || "interrupted".equals(state)) {
                    forgeFailure("Forge transport job " + state + ": " + value.optString("error"));
                } else {
                    handler.postDelayed(() -> pollForge(forgeJobId, consumer), 750L);
                }
            }
            @Override public void failed(String detail) { forgeFailure("Forge status failed: " + detail); }
        });
    }

    private void forgeFailure(String detail) {
        forgeBusy = false;
        SageDiagnostics.recordError(this, detail);
        refresh();
        toast(detail);
    }

'''
    replace_once(activity, refresh_anchor, helpers + refresh_anchor, "autonomy paired Forge transport helpers")

    # The new transport remains owner-only because the activity still requires Red Queen.
    text = activity.read_text(encoding="utf-8")
    if "SageRedQueenSession.isUnlocked(this)" not in text:
        raise SystemExit("Forge autonomy transport would weaken Red Queen owner boundary")
    for forbidden in ("Runtime.getRuntime().exec", "ProcessBuilder", "java.lang.Process", "su -c", "adb shell"):
        if forbidden in text:
            raise SystemExit("unexpected execution primitive in Commander autonomy transport: " + forbidden)

    print("Applied Sage 1.30 paired-Forge autonomy dispatch and structured result return")


if __name__ == "__main__":
    main()
