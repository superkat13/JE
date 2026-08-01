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


shell = source("SageWorkbenchActivity.java")
require(shell, ["SAGE WORKBENCH", "Repair Center", "Package Center",
                "Home Lab & Network Map", "Authority & Permissions", "Device Tools"],
        "workbench shell")

operation = source("SageOperation.java")
require(operation, ["onProgress", "onComplete", "onError", "cancel()",
                    "AtomicBoolean", "elapsed_ms=", "SageDiagnostics"],
        "shared operation framework")

confirmation = source("SageConfirmation.java")
require(confirmation, ["Exact action:", "Target:", "Permissions involved:",
                       "Data leaving device:", "Reversibility:", "Approve"],
        "shared confirmations")

inspector = source("SagePackageInspector.java")
require(inspector, ["getPackageArchiveInfo", "GET_PERMISSIONS", "signerSha256",
                    "fileSha256", "Minimum Android API", "trusted_package_identities",
                    "BLOCKED: signing certificate mismatch", "BLOCKED: candidate is older",
                    "safeForInstall", "SHA-256"], "package inspection")

package_ui = source("SagePackageCenterActivity.java")
require(package_ui, ["ACTION_OPEN_DOCUMENT", "Approve installer handoff", "SageConfirmation.require",
                     "ACTION_VIEW", "FLAG_GRANT_READ_URI_PERMISSION", "package_history",
                     "Android installer unavailable", "Add exact package + signer identity",
                     "allowlist_add"], "safe installer handoff")
assert "pm install" not in package_ui and "Runtime.exec" not in package_ui

scanner = source("SageNetworkScanner.java")
require(scanner, ["newFixedThreadPool(WORKERS)", "isReachable(REACHABILITY_TIMEOUT_MS)",
                  "InetAddress.getByName(host)", "a == 10", "a == 172", "b >= 16",
                  "a == 192", "b == 168", "public ranges are refused", "shutdownNow"],
        "conservative local scanner")
network = source("SageNetworkActivity.java")
require(network, ["Confirm and scan displayed local subnet", "Cancel active network snapshot",
                  "SageConfirmation.require", "Nothing; results stay in Sage local storage",
                  "Changes since previous snapshot", "UNKNOWN", "identity_confidence",
                  "Edit saved device", "Trusted", "Hide known infrastructure",
                  "Save device label"],
        "network UI and map")
assert "scan(this" not in network.split("confirmScan()", 1)[0], "scan must not start before confirmation"

store = source("SageNetworkStore.java")
require(store, ["network_previous", "network_current", "trusted", "hidden", "owner_name",
                "New devices:", "Missing devices:"], "network snapshots")

device = source("SageDeviceToolsActivity.java")
require(device, ["Toggle flashlight", "Base64 encode locally", "Hex encode locally",
                 "URL encode locally", "Calculate basic arithmetic", "Battery:", "Storage:",
                 "Open QR scanner", "Choose file and calculate SHA-256",
                 "Decode JWT header and payload only", "Signature not verified; cracking is not supported",
                 "Estimate password strength locally"], "device tools")
assert "ScriptEngine" not in device and "Runtime.exec" not in device

commands = source("SageCommandEngine.java")
require(commands, ["open the workbench", "inspect this apk", "install this package",
                   "scan my network", "show my network map", "what changed on my network",
                   "hash this file", "SageNetworkActivity.EXTRA_MODE"], "voice routing")

manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text()
require(manifest, [".SageWorkbenchActivity", ".SagePackageCenterActivity", ".SageNetworkActivity",
                   ".SageDeviceToolsActivity", "ACCESS_NETWORK_STATE", "ACCESS_WIFI_STATE",
                   "REQUEST_INSTALL_PACKAGES"], "manifest")
for forbidden in ("BIND_DEVICE_ADMIN", "MANAGE_EXTERNAL_STORAGE"):
    assert forbidden not in manifest

gradle = (ROOT / "app/build.gradle.kts").read_text()
require(gradle, ['applicationId = "com.pineapple.sagecommander.stable"',
                 "versionCode = 40", 'versionName = "1.28.0"', "sagePermanentSigning"],
        "release continuity")

print("Sage Commander 1.25.0 Workbench regressions passed")
