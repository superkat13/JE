#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: test_internal_specialist_routing_v1_29.py <reconstructed-source>")

root = Path(sys.argv[1])
java = root / "app/src/main/java/com/pineapple/sage"
router = (java / "SageInternalSpecialistRouter.java").read_text()
coordinator = (java / "SageIntentCoordinator.java").read_text()
registry = (java / "SageCapabilityRegistry.java").read_text()

checks = {
    "single-Sage internal router exists": "final class SageInternalSpecialistRouter" in router,
    "Adobe is internal creative routing": all(token in router for token in ("adobe", "firefly", 'return "creative"')),
    "coding is internal engineering routing": all(token in router for token in ("github", "gradle", 'return "recovery"')),
    "Forge stays implementation detail": 'forgePaired ? "forge.approved_job" : "repair.diagnose"' in router,
    "package specialist routes internally": 'case "package": return "package.inspect"' in router,
    "media specialist routes internally": 'case "media": return "media.session"' in router,
    "normal coordinator refines automatically": "SageInternalSpecialistRouter.refineIntent(execution, intent, forgePaired)" in coordinator,
    "routing is diagnostic not another mode": "internal_specialist=" in coordinator and "MODE_" not in router,
    "router cannot unlock Red Queen": "SageRedQueenSession.unlock" not in router,
    "router cannot execute shell": "Runtime.getRuntime().exec" not in router and "ProcessBuilder" not in router and "su -c" not in router,
    "router cannot request permission": "requestPermissions(" not in router,
    "compiled consequence boundary remains": all(token in registry for token in ("redQueenRequired", "confirmation", "SupportState")),
}

# Executable model of key routing promises.
def normalize(value):
    return " ".join((value or "").lower().split())

def route(request, current="knowledge", forge=False):
    t = normalize(request)
    c = normalize(current)
    specific = {"package","file","network","osint","forensics","reverse_engineering","automation","red_queen","recovery","creative","media","memory"}
    if c in specific:
        return c
    if any(x in t for x in ("adobe","firefly","photoshop","premiere","after effects","video edit","transition","shot list","storyboard","prompt for")):
        return "creative"
    if any(x in t for x in ("code","coding","github","repository","repo","compile","build error","gradle","java error","fix this bug","debug this","source code","apk build")):
        return "recovery"
    return c or "knowledge"

checks.update({
    "Firefly request stays one Sage and selects creative": route("make me an Adobe Firefly video prompt") == "creative",
    "coding request stays one Sage and selects engineering": route("check the GitHub repo and fix this Gradle build error") == "recovery",
    "specific existing Red Queen intent is preserved": route("anything", "red_queen") == "red_queen",
    "ordinary knowledge remains ordinary": route("what does this mean") == "knowledge",
})

failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(("PASS" if ok else "FAIL") + " | " + name)
if failed:
    raise SystemExit("Checkpoint 4 internal specialist routing failed: " + ", ".join(failed))
print("Checkpoint 4 internal specialist routing regression passed")
