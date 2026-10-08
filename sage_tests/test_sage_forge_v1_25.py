from pathlib import Path
import sys


ROOT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("sage_build")
JAVA = ROOT / "app/src/main/java/com/pineapple/sage"


def source(name):
    path = JAVA / name
    assert path.is_file(), f"missing {name}"
    return path.read_text()


def require(value, markers, label):
    missing = [marker for marker in markers if marker not in value]
    assert not missing, f"{label} missing {missing}"


store = source("SageForgeStore.java")
require(store, ["AndroidKeyStore", "AES/GCM/NoPadding", "protected_token", "deleteEntry",
                "certificate SHA-256 must contain exactly 64", "last_result", "active_job"],
        "protected pairing store")
assert "putString(\"protected_token\",token)" not in store

client = source("SageForgeClient.java")
require(client, ["HttpsURLConnection", "PinTrust", "TLS", "SHA-256", "SageToken ",
                 "X-Sage-Timestamp", "X-Sage-Nonce", "owner_approved", "system.info",
                 "/v1/jobs/", "/cancel", "/v1/devices/current/revoke",
                 "Forge certificate pin mismatch", "setConnectTimeout", "setReadTimeout"],
        "pinned TLS Forge client")
for forbidden in ("Runtime.getRuntime", "ProcessBuilder", "http://"):
    assert forbidden not in client
assert "setHostnameVerifier" not in client, "Forge must retain normal HTTPS hostname verification"

activity = source("SageForgeActivity.java")
require(activity, ["Review and pair this tablet", "SageConfirmation.require",
                   "Approve Dell system-information job", "Cancel active Forge job",
                   "Structured Forge activity log", "Stored structured Dell result",
                   "Revoke this Dell pairing", "Trust was not claimed revoked",
                   "handler.postDelayed", "SageDiagnostics"], "Forge UI vertical slice")
assert "mock" not in activity.lower() and "placeholder" not in activity.lower()

workbench = source("SageWorkbenchActivity.java")
require(workbench, ["Sage Forge", "run approved jobs", "SageForgeActivity.class"],
        "Workbench Forge entry")

commands = source("SageCommandEngine.java")
require(commands, ["open sage forge", "ask forge for system information",
                   "SageForgeActivity.class"], "Forge voice entry")

manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text()
require(manifest, [".SageForgeActivity", "android.permission.INTERNET"], "Forge manifest")

gradle = (ROOT / "app/build.gradle.kts").read_text()
require(gradle, ['applicationId = "com.pineapple.sagecommander.stable"',
                 "versionCode = 40", 'versionName = "1.28.0"'], "release identity")

repair_workflow = (ROOT.parent / ".github/workflows/sage-approved-repair.yml").read_text()
require(repair_workflow, ["workbench_v1_25 sage_forge_v1_25",
                          "sage_forge.tests.test_forge"],
        "approved-repair reconstruction")

print("Sage Commander 1.25.0 Forge regressions passed")
