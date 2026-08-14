#!/usr/bin/env python3
"""Record Sage's already-selected response route and expose it through the compact presence strip."""
from pathlib import Path
import sys


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: response_route_v1_31.py <reconstructed-source>")
    root = Path(sys.argv[1])
    java = root / "app/src/main/java/com/pineapple/sage"
    service = java / "SageVoiceService.java"
    health = java / "SageBrainHealth.java"
    if not service.is_file() or not health.is_file():
        raise SystemExit("required reconstructed Sage Java source is missing")

    anchor = '''    private void deliverCommandResult(SageCommandEngine.Result result, String routeLabel) {\n        if (result.freshWakeAfterAction) {\n'''
    replacement = '''    private void deliverCommandResult(SageCommandEngine.Result result, String routeLabel) {\n        SageResponseRouteStore.record(this, routeLabel, result != null && result.matched);\n        if (result.freshWakeAfterAction) {\n'''
    replace_once(service, anchor, replacement, "voice response-route hook")

    presence_anchor = '''        return "Sage Brain · "+label+" · "+route+tail;\n'''
    presence_replacement = '''        String responseRoute=SageResponseRouteStore.compact(c);\n        return "Sage Brain · "+label+" · "+route+tail\n                +(responseRoute.isEmpty()?"":" · "+responseRoute);\n'''
    replace_once(health, presence_anchor, presence_replacement, "presence route summary")

    (java / "SageResponseRouteStore.java").write_text(r'''package com.pineapple.sage;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Locale;

/** Local evidence of the route Sage actually selected for the most recently delivered response. */
final class SageResponseRouteStore {
    private static final String PREFS="sage_response_route_v1";
    private SageResponseRouteStore(){}

    static void record(Context context,String route,boolean matched){
        String clean=clean(route);
        if(clean.isEmpty())clean="unspecified";
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
                .putString("route",clean).putBoolean("matched",matched)
                .putLong("at_ms",System.currentTimeMillis()).apply();
        SageDiagnostics.appendEvent(context,"RESPONSE ROUTE",
                "route="+clean+" matched="+matched);
    }

    static String compact(Context context){
        SharedPreferences p=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        String route=p.getString("route","");
        if(route==null||route.trim().isEmpty())return "";
        return "last answer: "+friendly(route)+(p.getBoolean("matched",true)?"":" (fallback/unmatched)");
    }

    static String diagnostic(Context context){
        SharedPreferences p=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        return "route="+p.getString("route","none")+" matched="+p.getBoolean("matched",false)
                +" at_ms="+p.getLong("at_ms",0L);
    }

    private static String friendly(String value){
        String lower=clean(value).toLowerCase(Locale.US);
        if(lower.contains("brain")||lower.contains("local"))return "tablet Brain";
        if(lower.contains("forge")||lower.contains("dell"))return "Dell Forge";
        if(lower.contains("fallback"))return "fallback";
        if(lower.contains("command")||lower.contains("deterministic")||lower.contains("built"))return "command engine";
        if(lower.contains("media"))return "media control";
        if(lower.contains("accessibility"))return "screen control";
        return clean(value).replace('_',' ');
    }

    private static String clean(String value){return value==null?"":value.replace('\n',' ').trim();}
}
''', encoding="utf-8")

    service_text = service.read_text(encoding="utf-8")
    health_text = health.read_text(encoding="utf-8")
    store_text = (java / "SageResponseRouteStore.java").read_text(encoding="utf-8")
    for marker in ("SageResponseRouteStore.record(this, routeLabel", "deliverCommandResult"):
        if marker not in service_text:
            raise SystemExit("missing voice route marker: " + marker)
    for marker in ("SageResponseRouteStore.compact(c)", "last answer:"):
        if marker not in health_text + store_text:
            raise SystemExit("missing presence route marker: " + marker)
    for forbidden in ("Runtime.getRuntime().exec", "ProcessBuilder", "java.lang.Process", "su -c", "adb shell"):
        if forbidden in store_text:
            raise SystemExit("unexpected execution primitive in route telemetry: " + forbidden)
    print("Applied Sage 1.31 truthful last-response route telemetry")


if __name__ == "__main__":
    main()
