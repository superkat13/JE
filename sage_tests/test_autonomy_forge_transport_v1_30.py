#!/usr/bin/env python3
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def get_build() -> Path:
    if len(sys.argv) > 1:
        candidate = Path(sys.argv[1])
        if candidate.is_dir():
            return candidate
    td = tempfile.mkdtemp(prefix="sage-130-test-")
    out = Path(td) / "sage"
    subprocess.run(["bash", str(ROOT / "sage_tools/reconstruct_v1_30.sh"), str(out)], cwd=ROOT, check=True)
    return out


def main() -> None:
    out = get_build()
    java = out / "app/src/main/java/com/pineapple/sage"
    gradle = (out / "app/build.gradle.kts").read_text(encoding="utf-8")
    client = (java / "SageForgeClient.java").read_text(encoding="utf-8")
    store = (java / "SageAutonomyStore.java").read_text(encoding="utf-8")
    activity = (java / "SageAutonomyActivity.java").read_text(encoding="utf-8")
    machine = (java / "SageConversationStateMachine.java").read_text(encoding="utf-8")
    redqueen = (java / "SageRedQueenActivity.java").read_text(encoding="utf-8")
    forge_transport = (ROOT / "sage_forge/autonomy_transport.py").read_text(encoding="utf-8")
    forge_init = (ROOT / "sage_forge/__init__.py").read_text(encoding="utf-8")

    checks = {
        "permanent package": 'applicationId = "com.pineapple.sagecommander.stable"' in gradle,
        "1.30 versionCode": "versionCode = 42" in gradle,
        "1.30 versionName": 'versionName = "1.30.0"' in gradle,
        "Commander dispatch tool": '"developer.autonomy_dispatch"' in client,
        "Commander result tool": '"developer.autonomy_result"' in client,
        "dispatch sends only job fingerprint and order": all(token in client for token in (
            '.put("job_id",sageJobId)', '.put("fingerprint",fingerprint)', '.put("order",order)')),
        "result request sends only Sage job ID": 'new JSONObject().put("job_id",sageJobId)' in client,
        "explicit Forge approval context retained": 'put("owner_approved",true)' in client and 'put("surface","sage_commander")' in client,
        "direct Forge send button": 'button("Send this job to paired Forge")' in activity,
        "direct Forge result button": 'button("Check paired Forge for result")' in activity,
        "paired trust required": "SageForgeStore.isPaired(this)" in activity,
        "Red Queen owner boundary retained": "SageRedQueenSession.isUnlocked(this)" in activity,
        "Forge transport result becomes autonomy evidence": "markForgeDispatched" in store and "attachForgeResult" in store,
        "waiting does not reset progress clock": 'history(job, "FORGE_WAITING"' in store and 'progress(job, "FORGE_WAITING"' not in store,
        "blocked Forge result changes approach": 'job.put("state", "READY_TO_DELEGATE")' in store and '"FORGE_BLOCKED"' in store,
        "ready Forge result reaches verification": 'job.put("state", "VERIFYING")' in store and '"FORGE_RESULT_READY"' in store,
        "glass verification still exists": "physicalResult" in store and "Glass PASS" in activity and "Glass FAIL" in activity,
        "five-minute rule still exists": "FIVE_MINUTES_MS = 5L * 60L * 1000L" in store and "enforceFiveMinuteRule" in store,
        "Red Queen still owns autonomy surface": 'functional(root, "Sage Autonomy"' in redqueen,
        "conversation machine retained": all(state in machine for state in (
            "COMMAND_LISTENING", "FINALIZING", "DISPATCHING", "SPEAKING", "ECHO_GUARD")),
        "Forge fixed outbox": '.sage" / "autonomy" / "outbox"' in forge_transport,
        "Forge fixed result inbox": '.sage" / "autonomy" / "results"' in forge_transport,
        "Forge does not accept arbitrary path": '"path"' not in forge_init.split('tool_id="developer.autonomy_dispatch"', 1)[1].split('tool_id="developer.autonomy_result"', 1)[0],
        "Forge tool IDs registered": 'tool_id="developer.autonomy_dispatch"' in forge_init and 'tool_id="developer.autonomy_result"' in forge_init,
    }

    forbidden_android = ("Runtime.getRuntime().exec", "ProcessBuilder", "su -c", "adb shell", "pm install", "fastboot")
    checks["Commander transport has no execution primitive"] = not any(token in activity + client for token in forbidden_android)
    forbidden_forge = ("subprocess", "os.system", "shell=True", "Popen(", "eval(", "exec(")
    checks["Forge transport is file handoff not execution"] = not any(token in forge_transport for token in forbidden_forge)

    failed = [name for name, ok in checks.items() if not ok]
    for name, ok in checks.items():
        print(("PASS" if ok else "FAIL") + " | " + name)
    if failed:
        raise SystemExit("Sage 1.30 Forge-autonomy regression failed: " + ", ".join(failed))
    print("Sage 1.30 direct paired-Forge autonomy transport regression passed")


if __name__ == "__main__":
    main()
