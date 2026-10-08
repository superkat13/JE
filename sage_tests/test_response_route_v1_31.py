#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("sage_build_131")
JAVA = ROOT / "app/src/main/java/com/pineapple/sage"
SERVICE = JAVA / "SageVoiceService.java"
HEALTH = JAVA / "SageBrainHealth.java"
STORE = JAVA / "SageResponseRouteStore.java"
GRADLE = ROOT / "app/build.gradle.kts"

for path in (SERVICE, HEALTH, STORE, GRADLE):
    if not path.is_file():
        raise SystemExit(f"missing reconstructed file: {path}")

service = SERVICE.read_text(encoding="utf-8")
health = HEALTH.read_text(encoding="utf-8")
store = STORE.read_text(encoding="utf-8")
gradle = GRADLE.read_text(encoding="utf-8")

checks = [
    ('permanent package', 'applicationId = "com.pineapple.sagecommander.stable"' in gradle),
    ('1.31 identity', 'versionCode = 44' in gradle and 'versionName = "1.31.0"' in gradle),
    ('delivery hook records selected route', 'SageResponseRouteStore.record(this, routeLabel, result != null && result.matched);' in service),
    ('existing delivery path retained', 'private void deliverCommandResult(SageCommandEngine.Result result, String routeLabel)' in service),
    ('presence consumes response route', 'SageResponseRouteStore.compact(c)' in health),
    ('route evidence is local preferences', 'sage_response_route_v1' in store and 'SharedPreferences' in store),
    ('route evidence records timestamp', 'at_ms' in store and 'System.currentTimeMillis()' in store),
    ('route evidence records matched state', 'putBoolean("matched",matched)' in store),
    ('diagnostic event exists', 'RESPONSE ROUTE' in store),
    ('command engine mapping', 'return "command engine"' in store),
    ('tablet Brain mapping', 'return "tablet Brain"' in store),
    ('Dell Forge mapping', 'return "Dell Forge"' in store),
    ('media mapping', 'return "media control"' in store),
    ('screen-control mapping', 'return "screen control"' in store),
    ('response body is not persisted', 'result.message' not in store),
]

failed = []
for label, ok in checks:
    print(('PASS' if ok else 'FAIL') + ' | ' + label)
    if not ok:
        failed.append(label)

if failed:
    raise SystemExit('Sage 1.31 response-route regression failed: ' + ', '.join(failed))
print('Sage 1.31 truthful response-route regression passed')
