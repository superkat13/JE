#!/usr/bin/env python3
from pathlib import Path
import sys


if len(sys.argv) != 2:
    raise SystemExit("usage: test_package_file_labs_v1_28.py <reconstructed-source>")

root=Path(sys.argv[1]);java=root/"app/src/main/java/com/pineapple/sage"
package=(java/"SagePackageInspector.java").read_text()
filelab=(java/"SageFileLabActivity.java").read_text()
command=(java/"SageCommandEngine.java").read_text()
toolbelt=(java/"SageToolbeltActivity.java").read_text()
manifest=(root/"app/src/main/AndroidManifest.xml").read_text()

checks={
    "package identity and versions": all(v in package for v in ("packageName","versionName","versionCode","minSdk","targetSdk")),
    "package permissions features components": all(v in package for v in ("GET_PERMISSIONS","GET_CONFIGURATIONS","components","exportedSurfaces")),
    "package signer and hashes": "Signer certificate SHA-256" in package and "File SHA-256" in package,
    "package zip inventory": all(v in package for v in ("resources.arsc","assets/","lib/","nativeLibraries")),
    "installed comparison and trust": all(v in package for v in ("installedComparison","signerMatch","downgrade","trusted_package_identities")),
    "safe installer gate retained": "safeForInstall" in package and "BLOCKED: signing certificate mismatch" in package,
    "file type validation": all(v in filelab for v in ("Declared MIME","Extension","Detected type","Type mismatch")),
    "file cryptographic and legacy hashes": all(v in filelab for v in ("SHA-256","SHA-1 (legacy","MD5 (legacy")),
    "file metadata and timestamps": "Last modified" in filelab and "COLUMN_LAST_MODIFIED" in filelab,
    "file strings entropy packing": all(v in filelab for v in ("Printable strings preview","Shannon entropy","Packing indicator")),
    "file duplicates and comparison": "Known duplicate SHA-256" in filelab and "FILE COMPARISON" in filelab,
    "safe preview only": "safeMime" in filelab and "previewSafe" in filelab and "never executes" in filelab,
    "exportable report and cancellation": "Export/share report" in filelab and "session.checkCancelled" in filelab,
    "voice route": "inspect this file" in command and "SageFileLabActivity.class" in command,
    "toolbelt route": "SageFileLabActivity.class" in toolbelt,
    "non-exported File Lab": '<activity android:name=".SageFileLabActivity" android:exported="false" />' in manifest,
}
failed=[k for k,v in checks.items() if not v]
for k,v in checks.items():print(f"{'PASS' if v else 'FAIL'}: {k}")
if failed:raise SystemExit("Package/File Lab failures: "+", ".join(failed))
