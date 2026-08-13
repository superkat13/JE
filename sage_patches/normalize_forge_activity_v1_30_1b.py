#!/usr/bin/env python3
from pathlib import Path
import re
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: normalize_forge_activity_v1_30_1b.py <reconstructed-source>")
path = Path(sys.argv[1]) / "app/src/main/java/com/pineapple/sage/SageForgeActivity.java"
text = path.read_text(encoding="utf-8")
pattern = r'run=button\("[^"\\]*(?:\\.[^"\\]*)*"\);run\.setOnClickListener\(v->confirmSystemInfo\(\)\);root\.addView\(run\);'
replacement = '        run=button("Approve Dell system-information job");run.setOnClickListener(v->confirmSystemInfo());root.addView(run);'
updated, count = re.subn(pattern, replacement, text, count=1)
if count != 1:
    raise SystemExit(f"Forge run-button normalization: expected one match, found {count}")
path.write_text(updated, encoding="utf-8")
print("Normalized the inherited Forge system-info button anchor with deterministic indentation")
