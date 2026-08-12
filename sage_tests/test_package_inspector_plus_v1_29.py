#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("sage_build_129")
java = root / "app/src/main/java/com/pineapple/sage"
aidl_path = root / "app/src/main/aidl/com/pineapple/sage/ISageShizukuPower.aidl"
service_path = java / "SageShizukuUserService.java"
activity_path = java / "SageAuthorityBridgeActivity.java"
for path in (aidl_path, service_path, activity_path):
    assert path.is_file(), f"missing reconstructed Package Inspector dependency: {path}"

aidl = aidl_path.read_text(encoding="utf-8")
service = service_path.read_text(encoding="utf-8")
activity = activity_path.read_text(encoding="utf-8")

# Typed, bounded package read operations only.
for required in ("String listPackages();", "String inspectPackage(String packageName);"):
    assert required in aidl, f"missing typed package inspector AIDL: {required}"
for forbidden in ("runCommand(String", "shell(String", "exec(String", "uninstallPackage", "clearPackageData", "grantPermission", "revokePermission"):
    assert forbidden not in aidl, f"generic/mutating AIDL escaped into Package Inspector: {forbidden}"

for required in (
    'run("pm", "list", "packages", "-f", "-U")',
    'run("pm", "path", pkg)',
    'run("sha256sum", firstPath)',
    'run("dumpsys", "package", pkg)',
    'run("appops", "get", pkg)',
    'PACKAGE.matcher(pkg).matches()',
    'return bounded(out.toString())',
):
    assert required in service, f"missing Package Inspector operation: {required}"

# No destructive package-manager operations in the read-only slice.
for forbidden in (
    'run("pm", "uninstall"',
    'run("pm", "clear"',
    'run("pm", "disable-user"',
    'run("pm", "enable"',
    'run("pm", "grant"',
    'run("pm", "revoke"',
    'run("appops", "set"',
):
    assert forbidden not in service, f"destructive operation found in read-only Package Inspector: {forbidden}"

# UI stays behind Red Queen and exposes inventory + selected inspection through typed service calls.
for required in (
    "SageRedQueenSession.isUnlocked(this)",
    'Package Inspector Plus — list installed packages',
    'service -> service.listPackages()',
    'Package Inspector Plus — inspect selected package',
    'service -> service.inspectPackage(packageName.getText().toString().trim())',
):
    assert required in activity, f"Package Inspector UI/guard missing: {required}"

# Existing high-consequence action remains separately confirmed; Package Inspector itself does not add mutation.
assert 'new AlertDialog.Builder(this).setTitle("Force-stop app?")' in activity
assert 'applicationId = "com.pineapple.sagecommander.stable"' in (root / "app/build.gradle.kts").read_text(encoding="utf-8")
print("Package Inspector Plus read-only capability regression passed")
