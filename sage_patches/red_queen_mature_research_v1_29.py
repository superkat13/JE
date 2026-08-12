#!/usr/bin/env python3
"""Add a real owner-only mature-audience research browser inside Red Queen.

The surface is intentionally hidden behind the existing Red Queen session. It does not
create a second Sage mode, does not change normal browsing, and does not add a new
content filter. Only public http/https navigation is accepted by this activity.
"""
from pathlib import Path
import sys

ACTIVITY = r'''package com.pineapple.sage;

import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class SageMatureResearchActivity extends Activity {
    private EditText query;
    private WebView web;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (!SageRedQueenSession.isUnlocked(this)) { finish(); return; }
        setTitle("Red Queen · Mature Research");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(14), dp(14), dp(14));
        root.setBackgroundColor(Color.rgb(8, 8, 10));

        TextView title = new TextView(this);
        title.setText("MATURE RESEARCH");
        title.setTextSize(25);
        title.setTextColor(Color.rgb(255, 70, 85));
        root.addView(title);

        TextView detail = new TextView(this);
        detail.setText("Owner-only public-web research for adult topics. This surface uses Sage's existing Red Queen lock and keeps normal Sage unchanged.");
        detail.setTextColor(Color.LTGRAY);
        detail.setTextSize(14);
        root.addView(detail);

        query = new EditText(this);
        query.setHint("Search or enter a public website");
        query.setSingleLine(true);
        query.setTextColor(Color.WHITE);
        query.setHintTextColor(Color.GRAY);
        root.addView(query);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        Button go = button("Go");
        Button back = button("Back");
        Button clear = button("Clear");
        controls.addView(go, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        controls.addView(back, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        controls.addView(clear, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        root.addView(controls);

        web = new WebView(this);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri == null ? "" : String.valueOf(uri.getScheme()).toLowerCase(Locale.US);
                if (!"https".equals(scheme) && !"http".equals(scheme)) {
                    Toast.makeText(SageMatureResearchActivity.this, "Only public web links open here.", Toast.LENGTH_SHORT).show();
                    return true;
                }
                return false;
            }
        });
        root.addView(web, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        go.setOnClickListener(v -> navigate(query.getText().toString()));
        query.setOnEditorActionListener((v, actionId, event) -> { navigate(query.getText().toString()); return true; });
        back.setOnClickListener(v -> { if (web.canGoBack()) web.goBack(); else finish(); });
        clear.setOnClickListener(v -> {
            web.loadUrl("about:blank");
            web.clearHistory();
            web.clearCache(true);
            query.setText("");
            SageDiagnostics.appendEvent(this, "MATURE RESEARCH", "session cleared");
        });

        setContentView(root);
        SageDiagnostics.appendEvent(this, "MATURE RESEARCH", "owner surface opened");
    }

    private void navigate(String raw) {
        if (!SageRedQueenSession.isUnlocked(this)) { finish(); return; }
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return;
        Uri candidate = Uri.parse(value.matches("(?i)^https?://.*") ? value : "");
        if (candidate.getScheme() != null) {
            String scheme = candidate.getScheme().toLowerCase(Locale.US);
            if ("https".equals(scheme) || "http".equals(scheme)) {
                web.loadUrl(candidate.toString());
                SageDiagnostics.appendEvent(this, "MATURE RESEARCH", "public url opened host=" + candidate.getHost());
                return;
            }
        }
        String encoded = URLEncoder.encode(value, StandardCharsets.UTF_8);
        web.loadUrl("https://www.google.com/search?q=" + encoded);
        SageDiagnostics.appendEvent(this, "MATURE RESEARCH", "public search submitted");
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && web != null && web.canGoBack()) { web.goBack(); return true; }
        return super.onKeyDown(keyCode, event);
    }

    @Override protected void onDestroy() {
        if (web != null) {
            web.stopLoading();
            web.loadUrl("about:blank");
            web.clearHistory();
            web.clearCache(true);
            web.destroy();
        }
        super.onDestroy();
    }

    private Button button(String text) { Button b = new Button(this); b.setText(text); b.setAllCaps(false); return b; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
'''


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: red_queen_mature_research_v1_29.py <reconstructed-source>")
    root = Path(sys.argv[1])
    java = root / "app/src/main/java/com/pineapple/sage"
    redqueen = java / "SageRedQueenActivity.java"
    manifest = root / "app/src/main/AndroidManifest.xml"
    if not redqueen.is_file() or not manifest.is_file():
        raise SystemExit("mature research requires reconstructed Sage 1.29 source")

    (java / "SageMatureResearchActivity.java").write_text(ACTIVITY, encoding="utf-8")
    replace_once(
        redqueen,
        '''        functional(root, "Authority Bridge", "Optional Shizuku/Sui bridge: real ADB-shell authority without root, or root-backed identity later",\n                SageAuthorityBridgeActivity.class);''',
        '''        functional(root, "Authority Bridge", "Optional Shizuku/Sui bridge: real ADB-shell authority without root, or root-backed identity later",\n                SageAuthorityBridgeActivity.class);\n        functional(root, "Mature Research", "Owner-only public-web research for mature topics; hidden with Red Queen and cleared on exit",\n                SageMatureResearchActivity.class);''',
        "Red Queen mature research entry",
    )
    replace_once(
        manifest,
        '        <activity android:name=".SageAuthorityBridgeActivity" android:exported="false" />',
        '        <activity android:name=".SageAuthorityBridgeActivity" android:exported="false" />\n        <activity android:name=".SageMatureResearchActivity" android:exported="false" />',
        "private mature research manifest activity",
    )
    print("Applied owner-only Red Queen mature research surface")


if __name__ == "__main__":
    main()
