#!/usr/bin/env python3
"""Expose Sage's existing Brain health state as one compact home-screen presence strip."""
from pathlib import Path
import re
import sys


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def sub_once(path: Path, pattern: str, replacement: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    updated, count = re.subn(pattern, replacement, text, count=1, flags=re.MULTILINE)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    path.write_text(updated, encoding="utf-8")


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: brain_presence_v1_31.py <reconstructed-source>")
    root = Path(sys.argv[1])
    java = root / "app/src/main/java/com/pineapple/sage"
    main_activity = java / "MainActivity.java"
    brain_health = java / "SageBrainHealth.java"
    for required in (main_activity, brain_health):
        if not required.is_file():
            raise SystemExit(f"missing reconstructed source: {required.name}")

    sub_once(
        main_activity,
        r'(?m)^(\s*private TextView brainStatusText;\s*)$',
        r'\1\n    private TextView brainPresenceText;',
        "Brain presence field",
    )

    subtitle_anchor = "        root.addView(subtitle, matchWrap());\n"
    subtitle_add = subtitle_anchor + r'''
        brainPresenceText = new TextView(this);
        brainPresenceText.setTextSize(14);
        brainPresenceText.setTextColor(Color.rgb(31, 41, 55));
        brainPresenceText.setPadding(dp(12), dp(10), dp(12), dp(10));
        brainPresenceText.setBackgroundColor(Color.rgb(238, 242, 255));
        brainPresenceText.setContentDescription("Sage Brain state and response route");
        brainPresenceText.setOnClickListener(v ->
                startActivity(new Intent(this, SageBrainTestActivity.class)));
        root.addView(brainPresenceText, spacedSmall());
        refreshBrainPresence();
'''
    replace_once(main_activity, subtitle_anchor, subtitle_add, "Home presence strip")

    sub_once(
        main_activity,
        r'(private\s+void\s+refreshBrainStatus\s*\(\s*\)\s*\{)',
        r'\1\n        refreshBrainPresence();',
        "Brain-status refresh hook",
    )

    helper_anchor = "    private LinearLayout categoryPanel(String title) {\n"
    helper = r'''    private void refreshBrainPresence() {
        if (brainPresenceText == null) return;
        String presence = SageBrainHealth.presence(this);
        brainPresenceText.setText(presence);
        brainPresenceText.setContentDescription(
                presence + ". Tap for Sage Brain test, benchmark, and details.");
    }

'''
    replace_once(main_activity, helper_anchor, helper + helper_anchor, "Presence refresh helper")

    health_anchor = "    static String report(Context c){\n"
    presence_method = r'''    static String presence(Context c){
        SharedPreferences p=prefs(c),s=snapshotPrefs(c);
        String raw=p.getString("status",Status.OFF.name());
        String route=p.getString("active_route","command engine");
        String label;
        if(Status.LOADING.name().equals(raw))label="Loading local Brain";
        else if(Status.LOCAL_READY.name().equals(raw))label="Ready locally";
        else if(Status.DELL_READY.name().equals(raw))label="Dell ready";
        else if(Status.THINKING_LOCAL.name().equals(raw))label="Thinking locally";
        else if(Status.THINKING_DELL.name().equals(raw))label="Thinking on Dell";
        else if(Status.FALLBACK_USED.name().equals(raw))label="Fallback used";
        else if(Status.TIMED_OUT.name().equals(raw))label="Timed out";
        else if(Status.CANCELLED.name().equals(raw))label="Cancelled";
        else if(Status.ERROR.name().equals(raw))label="Needs attention";
        else label="Commands ready · local Brain off";
        String outcome=s.getString("terminal_outcome","none");
        String stage=s.getString("native_stage","none");
        String tail="";
        if("ACTIVE".equals(outcome)&&!"none".equals(stage))tail=" · "+prettyStage(stage);
        else if("SUCCESS".equals(outcome))tail=" · last reply ready";
        else if("TIMED_OUT".equals(outcome))tail=" · last request timed out";
        else if("CANCELLED".equals(outcome))tail=" · last request cancelled";
        else if("ERROR".equals(outcome))tail=" · last request failed";
        return "Sage Brain · "+label+" · "+route+tail;
    }
    private static String prettyStage(String stage){
        String value=clean(stage).replace('_',' ');
        if(value.isEmpty()||"none".equals(value))return "working";
        return value;
    }
'''
    replace_once(brain_health, health_anchor, presence_method + health_anchor, "Brain presence formatter")

    main_text = main_activity.read_text(encoding="utf-8")
    health_text = brain_health.read_text(encoding="utf-8")
    required_main = (
        "brainPresenceText", "SageBrainHealth.presence(this)",
        "SageBrainTestActivity.class", "refreshBrainPresence();",
        "Sage spaces", "Brain & Memory",
    )
    for marker in required_main:
        if marker not in main_text:
            raise SystemExit("missing Brain presence UI marker: " + marker)
    for marker in (
        "Ready locally", "Thinking locally", "Thinking on Dell", "Fallback used",
        "Timed out", "Cancelled", "Needs attention", "last reply ready",
    ):
        if marker not in health_text:
            raise SystemExit("missing Brain presence state: " + marker)
    if 'makeButton("Brain presence' in main_text:
        raise SystemExit("Brain presence must remain a compact status strip, not another button")

    print("Applied Sage 1.31 Brain presence strip using existing health/route telemetry")


if __name__ == "__main__":
    main()
