#!/usr/bin/env python3
"""Compile-safe correction for the bounded Shizuku UserService.

The power-tools generator imported android.os.Process and then used the simple name
Process for Runtime.exec(), which resolves to android.os.Process instead of
java.lang.Process. Keep the implementation unchanged while making the two meanings
explicit: android.os.Process.myUid() for identity, java.lang.Process for child
commands.
"""
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: shizuku_power_compile_fix_v1_29.py <reconstructed-project>")

root = Path(sys.argv[1])
path = root / "app/src/main/java/com/pineapple/sage/SageShizukuUserService.java"
text = path.read_text()

old_import = "import android.os.Process;\n\n"
if old_import not in text:
    raise SystemExit("expected android.os.Process import not found")
text = text.replace(old_import, "", 1)

old_uid = "@Override public int identityUid() { return Process.myUid(); }"
new_uid = "@Override public int identityUid() { return android.os.Process.myUid(); }"
if old_uid not in text:
    raise SystemExit("expected identityUid implementation not found")
text = text.replace(old_uid, new_uid, 1)

old_child = "Process process = null;"
new_child = "java.lang.Process process = null;"
if old_child not in text:
    raise SystemExit("expected child process declaration not found")
text = text.replace(old_child, new_child, 1)

path.write_text(text)
print("Applied Shizuku UserService Process namespace compile fix")
