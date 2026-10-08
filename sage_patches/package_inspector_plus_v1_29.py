#!/usr/bin/env python3
"""Add a read-only Package Inspector Plus to the proven Shizuku power surface.

Architecture inspired by the capability breadth of MuntashirAkon/AppManager, but this file
contains an original Sage implementation. No App Manager source is copied. Operations stay
typed and bounded: installed-package inventory plus deep inspection of one validated package.
"""
from pathlib import Path
import sys


def replace_once(path: Path, old: str, new: str, label: str):
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def main():
    if len(sys.argv) != 2:
        raise SystemExit("usage: package_inspector_plus_v1_29.py <reconstructed-source>")
    root = Path(sys.argv[1])
    java = root / "app/src/main/java/com/pineapple/sage"
    aidl = root / "app/src/main/aidl/com/pineapple/sage/ISageShizukuPower.aidl"
    service = java / "SageShizukuUserService.java"
    activity = java / "SageAuthorityBridgeActivity.java"
    for required in (aidl, service, activity):
        if not required.is_file():
            raise SystemExit("Package Inspector Plus missing dependency: " + str(required))

    replace_once(
        aidl,
        "    String authoritySnapshot();\n    String inspectPackage(String packageName);",
        "    String authoritySnapshot();\n    String listPackages();\n    String inspectPackage(String packageName);",
        "typed package inventory AIDL",
    )

    text = service.read_text(encoding="utf-8")
    anchor = '''    @Override public String inspectPackage(String packageName) {\n'''
    method = '''    @Override public String listPackages() {\n        StringBuilder out = new StringBuilder();\n        out.append("PACKAGE INSPECTOR PLUS\\n\\n");\n        out.append("Installed packages visible to Android shell authority.\\n");\n        out.append("Use one package name below in the inspector field.\\n\\n");\n        out.append(run("pm", "list", "packages", "-f", "-U"));\n        return bounded(out.toString());\n    }\n\n'''
    if anchor not in text: raise SystemExit("inspectPackage anchor missing")
    text = text.replace(anchor, method + anchor, 1)

    old_inspect = '''        StringBuilder out = new StringBuilder();\n        out.append("PACKAGE ").append(pkg).append('\\n');\n        append(out, "path", run("pm", "path", pkg));\n        append(out, "appops", run("appops", "get", pkg));\n        append(out, "package_dump", run("dumpsys", "package", pkg));\n        return bounded(out.toString());'''
    new_inspect = '''        StringBuilder out = new StringBuilder();\n        out.append("PACKAGE INSPECTOR PLUS\\n");\n        out.append("Target: ").append(pkg).append('\\n');\n        out.append("Authority: UID ").append(android.os.Process.myUid()).append(" (typed read-only inspection)\\n");\n        String paths = run("pm", "path", pkg);\n        append(out, "APK paths", paths);\n        String firstPath = firstPackagePath(paths);\n        if (firstPath != null) append(out, "APK SHA-256", run("sha256sum", firstPath));\n        append(out, "Package + components + permissions + signing", run("dumpsys", "package", pkg));\n        append(out, "App operations", run("appops", "get", pkg));\n        return bounded(out.toString());'''
    if old_inspect not in text: raise SystemExit("existing package inspection body missing")
    text = text.replace(old_inspect, new_inspect, 1)

    helper_anchor = '''    private static boolean protectedPackage(String pkg) {\n'''
    helper = '''    private static String firstPackagePath(String output) {\n        if (output == null) return null;\n        for (String line : output.split("\\\\n")) {\n            String value = line.trim();\n            if (value.startsWith("package:") && value.length() > 8) {\n                String path = value.substring(8).trim();\n                if (path.startsWith("/")) return path;\n            }\n        }\n        return null;\n    }\n\n'''
    if helper_anchor not in text: raise SystemExit("protected package anchor missing")
    text = text.replace(helper_anchor, helper + helper_anchor, 1)
    service.write_text(text, encoding="utf-8")

    ui = activity.read_text(encoding="utf-8")
    old_ui = '''        packageName = new EditText(this); packageName.setHint("Package, e.g. com.google.android.youtube"); root.addView(packageName);\n        Button inspect = button("Deep-inspect package with shell authority");\n        inspect.setOnClickListener(v -> runPower("package_inspection", service -> service.inspectPackage(packageName.getText().toString().trim()))); root.addView(inspect);\n'''
    new_ui = '''        Button packages = button("Package Inspector Plus — list installed packages");\n        packages.setOnClickListener(v -> runPower("package_inventory", service -> service.listPackages())); root.addView(packages);\n\n        packageName = new EditText(this); packageName.setHint("Package, e.g. com.google.android.youtube"); root.addView(packageName);\n        Button inspect = button("Package Inspector Plus — inspect selected package");\n        inspect.setOnClickListener(v -> runPower("package_inspector_plus", service -> service.inspectPackage(packageName.getText().toString().trim()))); root.addView(inspect);\n'''
    if old_ui not in ui: raise SystemExit("authority package UI block missing")
    ui = ui.replace(old_ui, new_ui, 1)
    activity.write_text(ui, encoding="utf-8")

    print("Applied read-only Package Inspector Plus with typed shell package inventory, APK hash, component/permission/signing dump, and app-ops inspection")

if __name__ == "__main__":
    main()
