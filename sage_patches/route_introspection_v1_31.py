#!/usr/bin/env python3
"""Add a deterministic spoken explanation for Sage's last response route without erasing that evidence."""
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
        raise SystemExit("usage: route_introspection_v1_31.py <reconstructed-source>")
    root = Path(sys.argv[1])
    java = root / "app/src/main/java/com/pineapple/sage"
    store = java / "SageResponseRouteStore.java"
    engine = java / "SageCommandEngine.java"
    if not store.is_file() or not engine.is_file():
        raise SystemExit("response route or command engine source is missing")

    record_anchor = '''    static void record(Context context,String route,boolean matched){
        String clean=clean(route);
'''
    record_replacement = '''    static void record(Context context,String route,boolean matched){
        SharedPreferences prefs=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        if(prefs.getBoolean("preserve_once",false)){
            prefs.edit().remove("preserve_once").apply();
            SageDiagnostics.appendEvent(context,"RESPONSE ROUTE","introspection_preserved=true");
            return;
        }
        String clean=clean(route);
'''
    replace_once(store, record_anchor, record_replacement, "preserve last route during introspection")

    compact_anchor = '''    static String compact(Context context){
'''
    spoken = r'''    static void preserveNextDelivery(Context context){
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
                .putBoolean("preserve_once",true).apply();
    }

    static String spoken(Context context){
        SharedPreferences p=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        String route=p.getString("route","");
        if(route==null||route.trim().isEmpty())return "I don't have a completed response route to report yet.";
        String source=friendly(route);
        boolean matched=p.getBoolean("matched",true);
        return matched?"That answer came through my "+source+" route."
                :"That answer used my "+source+" route after the stronger route did not match.";
    }

'''
    replace_once(store, compact_anchor, spoken + compact_anchor, "spoken route explanation")

    engine_anchor = '''        Result semanticResult = executeSemanticSlice(raw, lower);
'''
    engine_add = r'''        if (lower.equals("which brain answered that")
                || lower.equals("who answered that")
                || lower.equals("what answered that")
                || lower.equals("where did that answer come from")
                || lower.equals("what route answered that")) {
            String routeAnswer = SageResponseRouteStore.spoken(context);
            SageResponseRouteStore.preserveNextDelivery(context);
            preferences.edit().putString("last_heard", raw).apply();
            return new Result(routeAnswer);
        }

'''
    replace_once(engine, engine_anchor, engine_add + engine_anchor, "route introspection command")

    store_text = store.read_text(encoding="utf-8")
    engine_text = engine.read_text(encoding="utf-8")
    for marker in (
        "preserveNextDelivery", "I don't have a completed response route to report yet.",
        "That answer came through my ", "introspection_preserved=true",
    ):
        if marker not in store_text:
            raise SystemExit("missing route introspection marker: " + marker)
    for phrase in (
        "which brain answered that", "who answered that", "where did that answer come from",
    ):
        if phrase not in engine_text:
            raise SystemExit("missing route question: " + phrase)
    if engine_text.index("SageResponseRouteStore.spoken(context)") > engine_text.index("Result semanticResult = executeSemanticSlice"):
        raise SystemExit("route introspection must stay in the deterministic command lane")
    print("Applied Sage 1.31 spoken response-route introspection without overwriting prior evidence")


if __name__ == "__main__":
    main()
