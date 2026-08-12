#!/usr/bin/env python3
"""Add a bounded LAN Mapper over Sage's existing owner-confirmed private-LAN snapshot."""
from pathlib import Path
import sys

MAPPER = r'''package com.pineapple.sage;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class SageLanMapperActivity extends Activity {
    private LinearLayout hosts;
    private TextView status;
    private EditText selected;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("Sage LAN Mapper");
        setContentView(build());
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        if (hosts != null) render();
    }

    private View build() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(24));
        scroll.addView(root);
        root.addView(text("PRIVATE LAN MAP", 27));
        root.addView(text("Read-only map of Sage's most recent owner-approved private-LAN snapshot. Refresh discovery here, then select one saved host for bounded inspection.", 14));
        Button refresh = button("Refresh private-LAN snapshot");
        refresh.setOnClickListener(v -> startActivity(new Intent(this, SageNetworkActivity.class)));
        root.addView(refresh);
        Button rebuild = button("Rebuild map from saved snapshot");
        rebuild.setOnClickListener(v -> render());
        root.addView(rebuild);
        selected = new EditText(this);
        selected.setHint("Saved private IP");
        root.addView(selected);
        Button inspect = button("Inspect selected saved host");
        inspect.setOnClickListener(v -> inspectSelected());
        root.addView(inspect);
        status = text("", 13);
        root.addView(status);
        hosts = new LinearLayout(this);
        hosts.setOrientation(LinearLayout.VERTICAL);
        root.addView(hosts);
        SageAppearance.apply(this, scroll, root);
        return scroll;
    }

    private void render() {
        if (hosts == null) return;
        hosts.removeAllViews();
        JSONArray snapshot = SageNetworkStore.current(this);
        List<JSONObject> items = new ArrayList<>();
        for (int i = 0; i < snapshot.length(); i++) {
            JSONObject item = snapshot.optJSONObject(i);
            if (item != null && SageNetworkScanner.isPrivate(item.optString("ip") + "/32")) items.add(item);
        }
        Collections.sort(items, Comparator.comparing(o -> ipKey(o.optString("ip"))));
        status.setText(items.isEmpty()
                ? "No saved private-LAN hosts yet. Refresh the private-LAN snapshot first."
                : items.size() + " saved private-LAN host" + (items.size() == 1 ? "" : "s") + ". Tap a row to select it.");
        for (JSONObject item : items) {
            String ip = item.optString("ip", "").trim();
            String label = first(item, "name", "hostname", "host", "label");
            String mac = first(item, "mac", "mac_address", "hardware_address");
            String vendor = first(item, "vendor", "manufacturer");
            String state = first(item, "state", "status");
            StringBuilder detail = new StringBuilder(ip);
            if (!label.isEmpty() && !label.equals(ip)) detail.append("  •  ").append(label);
            if (!vendor.isEmpty()) detail.append("\nVendor: ").append(vendor);
            if (!mac.isEmpty()) detail.append("\nMAC: ").append(mac);
            if (!state.isEmpty()) detail.append("\nState: ").append(state);
            Button row = button(detail.toString());
            row.setOnClickListener(v -> { selected.setText(ip); Toast.makeText(this, "Selected " + ip, Toast.LENGTH_SHORT).show(); });
            hosts.addView(row);
        }
        SageDiagnostics.appendEvent(this, "LAN MAP", "rendered_saved_hosts=" + items.size());
    }

    private void inspectSelected() {
        String ip = selected.getText().toString().trim();
        if (!SageNetworkScanner.isPrivate(ip + "/32") || !saved(ip)) {
            Toast.makeText(this, "Choose an exact private IP from Sage's saved map first.", Toast.LENGTH_LONG).show();
            return;
        }
        Intent intent = new Intent(this, SageHostInspectorActivity.class);
        intent.putExtra("selected_private_ip", ip);
        startActivity(intent);
    }

    private boolean saved(String ip) {
        JSONArray snapshot = SageNetworkStore.current(this);
        for (int i = 0; i < snapshot.length(); i++) {
            JSONObject item = snapshot.optJSONObject(i);
            if (item != null && ip.equals(item.optString("ip"))) return true;
        }
        return false;
    }

    private static String first(JSONObject item, String... keys) {
        for (String key : keys) {
            String value = item.optString(key, "").trim();
            if (!value.isEmpty() && !"null".equalsIgnoreCase(value)) return value;
        }
        return "";
    }

    private static String ipKey(String ip) {
        String[] p = ip.split("\\.");
        if (p.length != 4) return ip;
        try {
            return String.format(java.util.Locale.US, "%03d.%03d.%03d.%03d", Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
        } catch (NumberFormatException ignored) { return ip; }
    }

    private Button button(String value) { Button b = new Button(this); b.setText(value); b.setAllCaps(false); return b; }
    private TextView text(String value, int size) { TextView t = new TextView(this); t.setText(value); t.setTextSize(size); return t; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
'''


def replace_once(path: Path, old: str, new: str, label: str):
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def main():
    if len(sys.argv) != 2:
        raise SystemExit("usage: lan_mapper_v1_29.py <reconstructed-source>")
    root = Path(sys.argv[1])
    java = root / "app/src/main/java/com/pineapple/sage"
    host = java / "SageHostInspectorActivity.java"
    manifest = root / "app/src/main/AndroidManifest.xml"
    toolbelt = java / "SageToolbeltActivity.java"
    redqueen = java / "SageRedQueenActivity.java"
    command = java / "SageCommandEngine.java"
    for required in (host, manifest, toolbelt, redqueen, command):
        if not required.is_file(): raise SystemExit("LAN Mapper missing dependency: " + str(required))

    (java / "SageLanMapperActivity.java").write_text(MAPPER, encoding="utf-8")

    replace_once(manifest,
        '        <activity android:name=".SageHostInspectorActivity" android:exported="false" />',
        '        <activity android:name=".SageHostInspectorActivity" android:exported="false" />\n        <activity android:name=".SageLanMapperActivity" android:exported="false" />',
        "LAN Mapper manifest")

    replace_once(toolbelt,
        '''        card(root, "Selected Host Inspector",\n                "Confirm one saved private-LAN host for conservative ports, reverse DNS, latency, TLS, and HTTP-header evidence.",\n                SageHostInspectorActivity.class);''',
        '''        card(root, "LAN Mapper",\n                "Map the saved private-LAN snapshot and inspect a selected host from one surface.",\n                SageLanMapperActivity.class);''',
        "Toolbelt network consolidation")

    replace_once(redqueen,
        '''        functional(root, "Network Lab", "Private-LAN investigation using Sage's saved host evidence",\n                SageNetworkActivity.class);''',
        '''        functional(root, "Network Lab", "Private-LAN map, refresh, selection, and bounded host inspection",\n                SageLanMapperActivity.class);''',
        "Red Queen Network Lab upgrade")

    replace_once(command,
        '''        if (isAny(lower, "inspect selected host", "inspect this network host", "check this private ip")) {\n            return openWorkbench(SageHostInspectorActivity.class, null);\n        }''',
        '''        if (isAny(lower, "show lan mapper", "open lan mapper", "map my lan", "show private network map")) {\n            return openWorkbench(SageLanMapperActivity.class, null);\n        }\n        if (isAny(lower, "inspect selected host", "inspect this network host", "check this private ip")) {\n            return openWorkbench(SageHostInspectorActivity.class, null);\n        }''',
        "LAN Mapper voice route")

    host_text = host.read_text(encoding="utf-8")
    old = '@Override public void onCreate(Bundle state){super.onCreate(state);setTitle("Sage Selected Host Inspector");setContentView(build());}'
    new = '@Override public void onCreate(Bundle state){super.onCreate(state);setTitle("Sage Selected Host Inspector");setContentView(build());String chosen=getIntent().getStringExtra("selected_private_ip");if(chosen!=null&&SageNetworkScanner.isPrivate(chosen+"/32"))ip.setText(chosen);}'
    if host_text.count(old) != 1: raise SystemExit("Selected Host prefill anchor missing")
    host.write_text(host_text.replace(old, new, 1), encoding="utf-8")
    print("Applied bounded LAN Mapper and consolidated existing network entry points")

if __name__ == "__main__": main()
