#!/usr/bin/env python3
from pathlib import Path
import sys


def require(condition, label):
    if not condition:
        raise SystemExit("FAIL | " + label)
    print("PASS | " + label)


def main():
    if len(sys.argv) != 2:
        raise SystemExit("usage: test_red_queen_mature_research_v1_29.py <reconstructed-source>")
    root = Path(sys.argv[1])
    java = root / "app/src/main/java/com/pineapple/sage"
    activity = (java / "SageMatureResearchActivity.java").read_text(encoding="utf-8")
    redqueen = (java / "SageRedQueenActivity.java").read_text(encoding="utf-8")
    manifest = (root / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")

    require("SageRedQueenSession.isUnlocked(this)" in activity, "Red Queen session required")
    require("MATURE RESEARCH" in activity, "mature research surface exists")
    require("WebView" in activity and "setJavaScriptEnabled(true)" in activity, "real embedded public web surface")
    require("https://www.google.com/search?q=" in activity, "real web search route")
    require("setAllowFileAccess(false)" in activity and "setAllowContentAccess(false)" in activity,
            "local file/content schemes not exposed through mature browser")
    require('"https".equals(scheme)' in activity and '"http".equals(scheme)' in activity,
            "public http/https navigation only")
    require("clearHistory()" in activity and "clearCache(true)" in activity, "session browsing data cleared")
    require('functional(root, "Mature Research"' in redqueen, "Red Queen exposes mature research")
    require('android:name=".SageMatureResearchActivity" android:exported="false"' in manifest,
            "mature activity hidden from other apps")
    require("com.pineapple.sagecommander.stable" in (root / "app/build.gradle.kts").read_text(encoding="utf-8"),
            "package identity preserved")
    print("Red Queen mature research regression passed")


if __name__ == "__main__":
    main()
