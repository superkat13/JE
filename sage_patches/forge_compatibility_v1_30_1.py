#!/usr/bin/env python3
"""Finish Sage 1.30.1 with explicit paired-Forge capability negotiation.

The 1.30 transport stays bounded and owner-approved. This patch makes Commander prove
that the paired Dell is the expected Sage Forge service and advertises both autonomy tool
IDs before sending or checking a repair job. Older Forge installs fail clearly instead of
turning into a mysterious job-denied error.
"""
from pathlib import Path
import sys


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def replace_between(path: Path, start: str, end: str, replacement: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    first = text.find(start)
    if first < 0:
        raise SystemExit(f"{label}: start marker missing")
    second = text.find(end, first + len(start))
    if second < 0:
        raise SystemExit(f"{label}: end marker missing")
    if text.find(start, first + 1) >= 0:
        raise SystemExit(f"{label}: start marker is not unique")
    path.write_text(text[:first] + replacement + text[second:], encoding="utf-8")


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: forge_compatibility_v1_30_1.py <reconstructed-source>")
    root = Path(sys.argv[1])
    java = root / "app/src/main/java/com/pineapple/sage"
    client = java / "SageForgeClient.java"
    autonomy = java / "SageAutonomyActivity.java"
    forge_activity = java / "SageForgeActivity.java"
    for required in (client, autonomy, forge_activity):
        if not required.is_file():
            raise SystemExit(f"missing reconstructed source: {required.name}")

    result_anchor = '''    void startAutonomyResult(String sageJobId,Callback callback){try{JSONObject approval=new JSONObject().put("surface","sage_commander").put("action","Read structured result for Sage-owned engineering job");JSONObject input=new JSONObject().put("job_id",sageJobId);JSONObject body=new JSONObject().put("tool_id","developer.autonomy_result").put("input",input).put("owner_approved",true).put("approval_context",approval);authenticated("POST","/v1/jobs",body,callback);}catch(Exception e){fail(callback,e);}}\n'''
    client_add = result_anchor + '''    void health(Callback callback){send(SageForgeStore.url(context),SageForgeStore.pin(context),null,"GET","/v1/health",null,callback);}\n    void tools(Callback callback){authenticated("GET","/v1/tools",null,callback);}\n'''
    replace_once(client, result_anchor, client_add, "Forge health/tools client methods")

    send_start = '''    private void sendToForge() {\n'''
    check_start = '''    private void checkForgeResult() {\n'''
    send_replacement = r'''    private void sendToForge() {
        if (forgeBusy) { toast("Forge job already in progress."); return; }
        if (!SageForgeStore.isPaired(this)) { toast("Pair Sage with Forge first."); return; }
        JSONObject job = SageAutonomyStore.active(this);
        if (job == null) { job = SageAutonomyStore.observe(this); refresh(); }
        if (job == null) { toast("Sage could not open an autonomy job."); return; }
        final JSONObject owned = job;
        forgeBusy = true;
        status.setText(SageAutonomyStore.display(job) + "\n\nFORGE\nChecking paired Forge compatibility…");
        requireForgeAutonomyReady(() -> dispatchAutonomyToForge(owned));
    }

    private void dispatchAutonomyToForge(JSONObject owned) {
        status.setText(SageAutonomyStore.display(owned) + "\n\nFORGE\nCompatibility proven. Queuing Sage's engineering order…");
        forge.startAutonomyDispatch(owned.optString("job_id"), owned.optString("fingerprint"),
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
                    toast("Sage handed the job directly to compatible paired Forge.");
                });
            }
            @Override public void failed(String detail) { forgeFailure("Forge dispatch failed: " + detail); }
        });
    }

    private void requireForgeAutonomyReady(Runnable ready) {
        forge.health(new SageForgeClient.Callback() {
            @Override public void complete(JSONObject health) {
                if (!"sage-forge".equals(health.optString("service"))) {
                    forgeFailure("Paired endpoint is not Sage Forge.");
                    return;
                }
                final String version = health.optString("version", "unknown");
                forge.tools(new SageForgeClient.Callback() {
                    @Override public void complete(JSONObject value) {
                        org.json.JSONArray tools = value.optJSONArray("tools");
                        boolean dispatch = false;
                        boolean result = false;
                        if (tools != null) {
                            for (int i = 0; i < tools.length(); i++) {
                                JSONObject tool = tools.optJSONObject(i);
                                if (tool == null) continue;
                                String id = tool.optString("tool_id");
                                if ("developer.autonomy_dispatch".equals(id)) dispatch = true;
                                if ("developer.autonomy_result".equals(id)) result = true;
                            }
                        }
                        if (!dispatch || !result) {
                            forgeFailure("Paired Dell Forge " + version
                                    + " is online but does not advertise Sage autonomy transport. Update and restart Sage Forge 0.3.1 or newer on the Dell.");
                            return;
                        }
                        SageDiagnostics.appendEvent(SageAutonomyActivity.this, "FORGE",
                                "compatibility_ok version=" + version + " autonomy_tools=2");
                        ready.run();
                    }
                    @Override public void failed(String detail) {
                        forgeFailure("Forge tool compatibility check failed: " + detail);
                    }
                });
            }
            @Override public void failed(String detail) {
                forgeFailure("Forge health check failed: " + detail);
            }
        });
    }

'''
    replace_between(autonomy, send_start, check_start, send_replacement, "autonomy dispatch preflight")

    poll_start = '''    private void pollForge(String forgeJobId, ForgeResultConsumer consumer) {\n'''
    check_replacement = r'''    private void checkForgeResult() {
        if (forgeBusy) { toast("Forge job already in progress."); return; }
        if (!SageForgeStore.isPaired(this)) { toast("Pair Sage with Forge first."); return; }
        JSONObject job = SageAutonomyStore.active(this);
        if (job == null) { toast("There is no active Sage autonomy job."); return; }
        final String sageJobId = job.optString("job_id");
        forgeBusy = true;
        status.setText(SageAutonomyStore.display(job) + "\n\nFORGE\nChecking paired Forge compatibility…");
        requireForgeAutonomyReady(() -> beginForgeResultCheck(sageJobId));
    }

    private void beginForgeResultCheck(String sageJobId) {
        JSONObject job = SageAutonomyStore.active(this);
        status.setText(SageAutonomyStore.display(job) + "\n\nFORGE\nCompatibility proven. Checking Sage's fixed result inbox…");
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

'''
    replace_between(autonomy, check_start, poll_start, check_replacement, "autonomy result preflight")

    run_anchor = '''        run=button("Approve Dell system-information job");run.setOnClickListener(v->confirmSystemInfo());root.addView(run);'''
    run_add = run_anchor + '''Button compatibility=button("Check Forge autonomy compatibility");compatibility.setOnClickListener(v->checkAutonomyCompatibility());root.addView(compatibility);'''
    replace_once(forge_activity, run_anchor, run_add, "Forge compatibility button")

    system_anchor = '''    private void confirmSystemInfo(){'''
    forge_helper = r'''    private void checkAutonomyCompatibility(){
        if(!SageForgeStore.isPaired(this)){status.setText("Pair this tablet with Forge first.");return;}
        status.setText("Checking pinned Forge health and advertised autonomy tools…");
        client.health(new Callback(){public void complete(JSONObject health){
            if(!"sage-forge".equals(health.optString("service"))){status.setText("Compatibility failed: paired endpoint is not Sage Forge.");return;}
            final String version=health.optString("version","unknown");
            client.tools(new Callback(){public void complete(JSONObject value){
                org.json.JSONArray tools=value.optJSONArray("tools");boolean dispatch=false,resultReady=false;
                if(tools!=null)for(int i=0;i<tools.length();i++){JSONObject tool=tools.optJSONObject(i);if(tool==null)continue;String id=tool.optString("tool_id");if("developer.autonomy_dispatch".equals(id))dispatch=true;if("developer.autonomy_result".equals(id))resultReady=true;}
                if(dispatch&&resultReady){status.setText("Forge "+version+" · autonomy transport READY\nPinned TLS, paired trust, dispatch tool and result tool all verified.");SageDiagnostics.appendEvent(SageForgeActivity.this,"FORGE","manual_compatibility_ok version="+version);}
                else status.setText("Forge "+version+" is reachable, but autonomy transport is NOT READY. Update and restart Sage Forge 0.3.1 or newer on the Dell.");
            }public void failed(String detail){status.setText("Tool compatibility check failed: "+detail);}});
        }public void failed(String detail){status.setText("Forge health check failed: "+detail);}});
    }

'''
    replace_once(forge_activity, system_anchor, forge_helper + system_anchor, "Forge compatibility checker")

    text = autonomy.read_text(encoding="utf-8")
    required = (
        "requireForgeAutonomyReady", "developer.autonomy_dispatch", "developer.autonomy_result",
        "Update and restart Sage Forge 0.3.1 or newer", "SageRedQueenSession.isUnlocked(this)",
    )
    for marker in required:
        if marker not in text:
            raise SystemExit("missing final Forge compatibility marker: " + marker)
    for forbidden in ("Runtime.getRuntime().exec", "ProcessBuilder", "java.lang.Process", "su -c", "adb shell"):
        if forbidden in text:
            raise SystemExit("unexpected execution primitive in Commander final transport: " + forbidden)

    print("Applied Sage 1.30.1 Forge health/tool compatibility negotiation")


if __name__ == "__main__":
    main()
