#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("sage_build_131")
java = root / "app/src/main/java/com/pineapple/sage"
engine = (java / "SageCommandEngine.java").read_text(encoding="utf-8")
store = (java / "SageResponseRouteStore.java").read_text(encoding="utf-8")
gradle = (root / "app/build.gradle.kts").read_text(encoding="utf-8")

checks = {
    "package": 'applicationId = "com.pineapple.sagecommander.stable"' in gradle,
    "version": 'versionCode = 44' in gradle and 'versionName = "1.31.0"' in gradle,
    "which brain phrase": 'which brain answered that' in engine,
    "who phrase": 'who answered that' in engine,
    "where phrase": 'where did that answer come from' in engine,
    "spoken route": 'SageResponseRouteStore.spoken(context)' in engine,
    "preserve delivery": 'SageResponseRouteStore.preserveNextDelivery(context)' in engine,
    "preserve flag": 'preserve_once' in store,
    "preserve diagnostic": 'introspection_preserved=true' in store,
    "human response": 'That answer came through my ' in store,
    "empty response": "I don't have a completed response route to report yet." in store,
    "command lane order": engine.index('SageResponseRouteStore.spoken(context)') < engine.index('Result semanticResult = executeSemanticSlice'),
}

failed=[]
for label,ok in checks.items():
    print(("PASS" if ok else "FAIL")+" | "+label)
    if not ok: failed.append(label)
if failed:
    raise SystemExit("Sage 1.31 route introspection regression failed: "+", ".join(failed))
print("Sage 1.31 spoken route introspection regression passed")
