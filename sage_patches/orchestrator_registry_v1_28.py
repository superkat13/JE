#!/usr/bin/env python3
"""Add the trusted capability registry and full staged orchestrator trace."""

from pathlib import Path
import sys


REGISTRY = r'''package com.pineapple.sage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Compiled authority registry. Data files can describe agents but can never add executable tools. */
final class SageCapabilityRegistry {
    enum SupportState { ACTIVE, AVAILABLE, NEEDS_SETUP, UNSUPPORTED }

    static final class Entry {
        final String id;
        final String purpose;
        final String supportedIntents;
        final String voiceExamples;
        final String platform;
        final String inputs;
        final String outputs;
        final String permissions;
        final String risk;
        final String confirmation;
        final String timeout;
        final String cancellation;
        final String dataEgress;
        final String networkScope;
        final boolean redQueenRequired;
        final boolean forgeRequired;
        final String implementation;
        final SupportState supportState;

        Entry(String id, String purpose, String intents, String examples, String platform,
              String inputs, String outputs, String permissions, String risk,
              String confirmation, String timeout, String cancellation, String dataEgress,
              String networkScope, boolean redQueenRequired, boolean forgeRequired,
              String implementation, SupportState supportState) {
            this.id = id;
            this.purpose = purpose;
            this.supportedIntents = intents;
            this.voiceExamples = examples;
            this.platform = platform;
            this.inputs = inputs;
            this.outputs = outputs;
            this.permissions = permissions;
            this.risk = risk;
            this.confirmation = confirmation;
            this.timeout = timeout;
            this.cancellation = cancellation;
            this.dataEgress = dataEgress;
            this.networkScope = networkScope;
            this.redQueenRequired = redQueenRequired;
            this.forgeRequired = forgeRequired;
            this.implementation = implementation;
            this.supportState = supportState;
        }

        String summary() {
            return id + " — " + supportState.name() + "\n" + purpose
                    + "\nIntents: " + supportedIntents
                    + "\nVoice: " + voiceExamples
                    + "\nPlatform: " + platform
                    + "\nInputs: " + inputs + "\nOutputs: " + outputs
                    + "\nPermissions: " + permissions + "\nRisk: " + risk
                    + "\nConfirmation: " + confirmation + "\nTimeout: " + timeout
                    + "\nCancellation: " + cancellation + "\nData egress: " + dataEgress
                    + "\nNetwork scope: " + networkScope
                    + "\nRed Queen required: " + redQueenRequired
                    + "\nForge required: " + forgeRequired
                    + "\nImplementation: " + implementation;
        }
    }

    private static final List<Entry> COMPILED;
    static {
        List<Entry> values = new ArrayList<>();
        values.add(entry("android.command", "Allowlisted Android intents and semantic UI actions",
                "device_action", "Tap Play; Open Downloads; choose the second video", "Android",
                "final transcript + visible accessibility metadata", "verified Android action result",
                "accessibility only when direct APIs cannot complete the action", "medium",
                "only for destructive, ambiguous, or sensitive actions", "15 seconds",
                "cancel before dispatch", "none", "local device", false, false,
                "SageCommandEngine + SageAccessibilityService", SupportState.ACTIVE));
        values.add(entry("memory.standard", "Persistent owner facts, preferences, aliases, devices, routines, and projects",
                "memory", "Remember that; When I say X I mean Y; I prefer", "Android",
                "owner statement", "deduplicated versioned memory record", "none", "low",
                "none for ordinary memory; owner confirms deletion", "5 seconds", "not applicable",
                "none", "local device", false, false, "SageMemoryStore", SupportState.ACTIVE));
        values.add(entry("brain.local", "Private local GGUF reasoning with real generation metrics",
                "knowledge,conversation,model", "Sage test your brain", "Android arm64",
                "verified GGUF + prompt context", "generated text + timing + route evidence",
                "owner-selected model-file access", "low", "model download/import only", "30 seconds",
                "native atomic cancellation", "none", "local device", false, false,
                "SageBrainManager + llama.cpp", SupportState.AVAILABLE));
        values.add(entry("forge.approved_job", "Heavy private allowlisted work on the owner's Dell",
                "recovery,engineering", "Open Forge; run Dell system information", "Xubuntu/Xfce Dell",
                "schema-validated signed job", "progress, logs, structured result, artifacts",
                "pinned TLS pairing + explicit owner approval", "high", "always", "per tool policy",
                "remote cancel endpoint", "private LAN to paired Dell only", "private LAN",
                false, true, "SageForgeClient + sage_forge trusted registry", SupportState.NEEDS_SETUP));
        values.add(entry("package.inspect", "Static APK identity, manifest, signer, hashes, and safe installer handoff",
                "package,reverse_engineering", "Analyze this APK; inspect this package", "Android",
                "owner-selected APK URI", "identity and cryptographic report", "file read grant", "medium",
                "install handoff always", "60 seconds", "cancel hashing", "none", "local device",
                false, false, "SagePackageInspector", SupportState.ACTIVE));
        values.add(entry("file.inspect", "File type, size, hash, metadata, and safe preview",
                "file,forensics", "Inspect this file; hash this file", "Android",
                "owner-selected file URI", "SHA-256, size, type and report", "file read grant", "low",
                "export only", "60 seconds", "stream cancellation", "none", "local device",
                false, false, "SageFileHasherActivity", SupportState.ACTIVE));
        values.add(entry("media.session", "Inspect and control active MediaSession without screen tapping",
                "media", "Pause; next song; inspect active media", "Android",
                "active media session", "playback state and direct control result", "notification access",
                "low", "none", "10 seconds", "not applicable", "none", "local device",
                false, false, "SageMediaSessionBridge", SupportState.AVAILABLE));
        values.add(entry("network.private_lan", "Conservative owner-confirmed private subnet snapshot",
                "network", "Scan my network; what changed on my network", "Android/private LAN",
                "current private subnet, capped at /24", "reachability snapshot and changes",
                "Wi-Fi state + owner confirmation", "high", "always", "bounded per-host timeout",
                "immediate worker cancellation", "private LAN packets only", "RFC1918/link-local only",
                true, false, "SageNetworkScanner", SupportState.AVAILABLE));
        values.add(entry("creative.director", "Offline video ideas, prompts, shot plans, continuity, and music concepts",
                "creative", "Surprise me; video idea; cure my boredom", "Android",
                "current creative request + standard context", "one concise creative direction", "none", "low",
                "none", "5 seconds", "stop/another one", "none", "local device",
                false, false, "SageCreativeEngine", SupportState.ACTIVE));
        values.add(entry("repair.diagnose", "Sanitized evidence/theory separation and supervised repair packet",
                "recovery,quality", "Sage diagnose yourself; prepare a fix", "Android + optional Forge",
                "runtime diagnostics", "JSON + Markdown repair bundle", "owner export approval", "medium",
                "export/build/install always", "60 seconds", "cancel before export", "none unless approved",
                "local device or paired Forge", false, false, "SageRepairManager", SupportState.ACTIVE));
        values.add(entry("redqueen.authenticate", "Verify owner authority before exposing advanced workspace",
                "red_queen", "sage glitch; Red Queen mode", "Android",
                "device PIN/password/biometric confirmation", "short-lived process-local authorization",
                "secure device credential", "high", "always", "5-minute inactivity",
                "explicit lock/background/device lock", "none", "local device", false, false,
                "SageRedQueenActivity + SageRedQueenSession", SupportState.AVAILABLE));
        values.add(entry("osint.curated", "Curated public-source research with evidence summaries",
                "osint", "Check this public domain or username", "Forge/future",
                "owner-authorized public target", "deduplicated public evidence", "Red Queen + approval",
                "high", "always", "per workflow", "required", "public web only after approval",
                "public sources only", true, true, "not implemented", SupportState.UNSUPPORTED));
        values.add(entry("forensics.case", "Case/evidence IDs, hashes, notes, and chain-of-custody activity",
                "forensics", "Create a case; import this evidence", "future",
                "owner-authorized evidence", "case report", "Red Queen + explicit import", "high",
                "always", "per operation", "required", "none by default", "local evidence only",
                true, false, "not implemented", SupportState.UNSUPPORTED));
        COMPILED = Collections.unmodifiableList(values);
    }

    private SageCapabilityRegistry() {}

    static List<Entry> all() { return COMPILED; }

    static Entry select(String intent, boolean forgePaired) {
        String wanted = clean(intent);
        if (wanted.equals("recovery") && forgePaired) return find("forge.approved_job");
        for (Entry entry : COMPILED) {
            for (String supported : entry.supportedIntents.split(",")) {
                if (wanted.equals(clean(supported))) return entry;
            }
        }
        return find("brain.local");
    }

    static Entry find(String id) {
        for (Entry entry : COMPILED) if (entry.id.equals(id)) return entry;
        throw new IllegalArgumentException("unknown compiled capability: " + id);
    }

    private static Entry entry(String id, String purpose, String intents, String examples,
                               String platform, String inputs, String outputs, String permissions,
                               String risk, String confirmation, String timeout, String cancellation,
                               String dataEgress, String networkScope, boolean redQueenRequired,
                               boolean forgeRequired, String implementation, SupportState state) {
        return new Entry(id, purpose, intents, examples, platform, inputs, outputs, permissions,
                risk, confirmation, timeout, cancellation, dataEgress, networkScope,
                redQueenRequired, forgeRequired, implementation, state);
    }

    private static String clean(String value) {
        return value == null ? "" : value.toLowerCase(Locale.US).trim();
    }
}
'''

REGISTRY_ACTIVITY = r'''package com.pineapple.sage;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class SageRegistryActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("Sage Capability Registry");
        setContentView(build());
    }

    private View build() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(24));
        scroll.addView(root);
        root.addView(text("TRUSTED CAPABILITY REGISTRY", 27));
        root.addView(text("Only compiled implementations can execute. Downloaded definitions and agent files cannot grant authority, shell access, permissions, or network scope.", 14));
        for (SageCapabilityRegistry.Entry entry : SageCapabilityRegistry.all()) {
            TextView card = text(entry.summary(), 13);
            card.setTextIsSelectable(true);
            card.setPadding(dp(10), dp(12), dp(10), dp(12));
            root.addView(card);
        }
        SageAppearance.apply(this, scroll, root);
        return scroll;
    }

    private TextView text(String value, int size) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        return text;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
'''


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    value = path.read_text()
    count = value.count(old)
    if count != 1:
        raise SystemExit(f"expected one {label}, found {count}")
    path.write_text(value.replace(old, new, 1))


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: orchestrator_registry_v1_28.py <reconstructed-source>")
    root = Path(sys.argv[1])
    java = root / "app/src/main/java/com/pineapple/sage"
    (java / "SageCapabilityRegistry.java").write_text(REGISTRY)
    (java / "SageRegistryActivity.java").write_text(REGISTRY_ACTIVITY)

    coordinator = java / "SageIntentCoordinator.java"
    replace_once(coordinator,
        '''        FORGE_MANAGER
    }''',
        '''        FORGE_MANAGER,
        SAFETY_GUARD,
        NETWORK_SCOUT,
        OSINT_RESEARCHER,
        REVERSE_ENGINEERING_ANALYST,
        FORENSICS_ANALYST,
        AUTOMATION_MANAGER
    }''', "specialist registry")
    replace_once(coordinator,
        '''        final String verification;
        final Specialist specialist;''',
        '''        final String verification;
        final String entities;
        final String capabilityId;
        final String riskDecision;
        final String orchestrationTrace;
        final Specialist specialist;''', "plan fields")
    replace_once(coordinator,
        '''             String tool, String routeHint, String verification, Specialist specialist,
             float confidence, boolean contextualFollowUp, boolean correction) {''',
        '''             String tool, String routeHint, String verification, String entities,
             String capabilityId, String riskDecision, String orchestrationTrace,
             Specialist specialist, float confidence, boolean contextualFollowUp,
             boolean correction) {''', "plan constructor")
    replace_once(coordinator,
        '''            this.verification = clean(verification);
            this.specialist = specialist;''',
        '''            this.verification = clean(verification);
            this.entities = clean(entities);
            this.capabilityId = clean(capabilityId);
            this.riskDecision = clean(riskDecision);
            this.orchestrationTrace = clean(orchestrationTrace);
            this.specialist = specialist;''', "plan assignments")
    replace_once(coordinator,
        '''        String tool = toolFor(intent);
        String route = routeFor(intent, brainAvailable, forgePaired);
        String verification = verificationFor(intent);''',
        '''        SageCapabilityRegistry.Entry capability =
                SageCapabilityRegistry.select(intent, forgePaired);
        String tool = toolFor(intent);
        String route = routeFor(intent, brainAvailable, forgePaired);
        String verification = verificationFor(intent);
        String entities = entitiesFor(execution);
        String riskDecision = "risk=" + capability.risk
                + " permission=" + capability.permissions
                + " confirmation=" + capability.confirmation;
        String orchestrationTrace = "request → intent → entities → context → memory → plan → "
                + "capability selection → risk/permission check → execute → verify → concise result";''',
        "capability selection")
    replace_once(coordinator,
        '''        Plan plan = new Plan(cleaned, execution, goal, intent, tool, route, verification,
                specialist, confidence, followUp, correction);''',
        '''        Plan plan = new Plan(cleaned, execution, goal, intent, tool, route, verification,
                entities, capability.id, riskDecision, orchestrationTrace,
                specialist, confidence, followUp, correction);''', "plan creation")
    replace_once(coordinator,
        '''                        + " tool=" + tool + " route_hint=" + route
                        + " confidence="''',
        '''                        + " tool=" + tool + " route_hint=" + route
                        + " entities=" + entities + " " + riskDecision
                        + " trace=" + orchestrationTrace + " confidence="''', "trace logging")
    replace_once(coordinator,
        '''        if (containsAny(value, "apk", "package", "signer", "certificate")) return "package";
        if (containsAny(value, "forge", "dell", "job", "repair", "diagnose")) return "recovery";''',
        '''        if (containsAny(value, "apk", "package", "signer", "certificate")) return "package";
        if (containsAny(value, "file", "mime", "hash", "metadata")) return "file";
        if (containsAny(value, "network", "subnet", "lan", "ip address")) return "network";
        if (containsAny(value, "osint", "public records", "public username")) return "osint";
        if (containsAny(value, "forensic", "evidence", "chain of custody")) return "forensics";
        if (containsAny(value, "reverse engineer", "static analysis")) return "reverse_engineering";
        if (containsAny(value, "automation", "routine trigger")) return "automation";
        if (containsAny(value, "red queen", "sage glitch")) return "red_queen";
        if (containsAny(value, "forge", "dell", "job", "repair", "diagnose")) return "recovery";''',
        "expanded intents")
    replace_once(coordinator,
        '''            case "package": return Specialist.PACKAGE_INSPECTOR;
            case "recovery": return normalize(request).contains("forge")''',
        '''            case "package": return Specialist.PACKAGE_INSPECTOR;
            case "file": return Specialist.FORENSICS_ANALYST;
            case "network": return Specialist.NETWORK_SCOUT;
            case "osint": return Specialist.OSINT_RESEARCHER;
            case "reverse_engineering": return Specialist.REVERSE_ENGINEERING_ANALYST;
            case "forensics": return Specialist.FORENSICS_ANALYST;
            case "automation": return Specialist.AUTOMATION_MANAGER;
            case "red_queen": return Specialist.SAFETY_GUARD;
            case "recovery": return normalize(request).contains("forge")''',
        "expanded specialists")
    replace_once(coordinator,
        '''        if (intent.equals("recovery") && forgePaired) return "Dell Forge";''',
        '''        if (intent.equals("recovery") && forgePaired) return "Dell Forge";
        if (intent.equals("osint") || intent.equals("forensics")
                || intent.equals("reverse_engineering") || intent.equals("automation")) {
            return "fallback";
        }''', "unsupported route guard")
    replace_once(coordinator,
        '''    private static boolean isFollowUp(String value) {''',
        '''    private static String entitiesFor(String request) {
        String value = clean(request);
        java.util.ArrayList<String> entities = new java.util.ArrayList<>();
        java.util.regex.Matcher packageName = java.util.regex.Pattern
                .compile("[a-zA-Z][a-zA-Z0-9_]*(?:[.][a-zA-Z0-9_]+){2,}").matcher(value);
        if (packageName.find()) entities.add("package=" + packageName.group());
        if (normalize(value).contains("this apk")) entities.add("selected_apk");
        if (normalize(value).contains("this file")) entities.add("selected_file");
        if (normalize(value).contains("my dell")) entities.add("device=dell");
        if (normalize(value).contains("youtube")) entities.add("app=youtube");
        return entities.isEmpty() ? "none" : String.join(",", entities);
    }

    private static boolean isFollowUp(String value) {''', "entity extractor")

    manifest = root / "app/src/main/AndroidManifest.xml"
    replace_once(manifest,
        '        <activity android:name=".SageRedQueenActivity" android:exported="false" />',
        '        <activity android:name=".SageRedQueenActivity" android:exported="false" />\n'
        '        <activity android:name=".SageRegistryActivity" android:exported="false" />',
        "registry manifest")

    workbench = java / "SageWorkbenchActivity.java"
    replace_once(workbench,
        'card(r,"Red Queen Mode","Owner-authenticated black/crimson workspace with encrypted private storage and audited advanced tools",SageRedQueenActivity.class);',
        'card(r,"Red Queen Mode","Owner-authenticated black/crimson workspace with encrypted private storage and audited advanced tools",SageRedQueenActivity.class);'
        'card(r,"Capability Registry","Compiled tools, permissions, risk, confirmation, timeouts, cancellation, egress, scope, and support state",SageRegistryActivity.class);',
        "Workbench registry card")

    redqueen = java / "SageRedQueenActivity.java"
    replace_once(redqueen,
        '        deferred(root, "Tool Registry", "Deferred UI; only compiled allowlisted tools currently execute.");',
        '        functional(root, "Tool Registry", "Compiled capability declarations and support states", SageRegistryActivity.class);',
        "Red Queen registry card")

    authority = java / "SageAuthority.java"
    replace_once(authority,
        '''        result.add(roleCapability(context, "default_assistant", "Default assistant",''',
        '''        android.app.KeyguardManager keyguard = (android.app.KeyguardManager)
                context.getSystemService(Context.KEYGUARD_SERVICE);
        boolean secureCredential = keyguard != null && keyguard.isDeviceSecure();
        boolean redQueen = secureCredential && SageRedQueenSession.isUnlocked(context);
        result.add(new Capability(
                "red_queen_authority", "Red Queen owner authority",
                redQueen ? State.ACTIVE : secureCredential ? State.AVAILABLE : State.NEEDS_SETUP,
                redQueen ? "Owner authentication is active for this short session."
                        : secureCredential ? "Available after owner credential authentication."
                        : "Set a secure Android PIN, password, or biometric first.",
                secureCredential ? new Intent(context, SageRedQueenActivity.class)
                        : new Intent(Settings.ACTION_SECURITY_SETTINGS)
        ));
        boolean forgePaired = SageForgeStore.isPaired(context);
        result.add(new Capability(
                "forge_trust", "Sage Forge trust",
                forgePaired ? State.ACTIVE : State.NEEDS_SETUP,
                forgePaired ? "A pinned-TLS Dell pairing is active."
                        : "Pair the owner's Xubuntu/Xfce Dell with explicit approval.",
                new Intent(context, SageForgeActivity.class)
        ));
        SageBrainManager brain = SageBrainManager.get(context);
        result.add(new Capability(
                "tablet_brain", "Tablet Brain model",
                brain.isReady() ? State.ACTIVE : brain.canAnswer() ? State.AVAILABLE : State.NEEDS_SETUP,
                brain.isReady() ? "A verified local model is loaded."
                        : brain.canAnswer() ? "A verified model is configured and can be loaded."
                        : "Import and enable a verified GGUF model.",
                new Intent(context, SageBrainTestActivity.class)
        ));
        result.add(new Capability(
                "cloud_model_provider", "Approved cloud model provider",
                State.UNSUPPORTED,
                "No cloud provider is configured; Sage will not claim cloud authority.",
                null
        ));

        result.add(roleCapability(context, "default_assistant", "Default assistant",''',
        "authority expansion")
    print("Applied Sage 1.28 orchestrator and trusted capability registry")


if __name__ == "__main__":
    main()
