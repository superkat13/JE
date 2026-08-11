#!/usr/bin/env python3
"""Repair two owner-tablet defects without replacing working Sage architecture.

1. Public website requests must never fall into the private-LAN host-inspection refusal path.
   The selected-host inspector remains private-IP-only, but a typed public URL/domain is
   handed to Android's browser instead of producing a misleading SecurityException.
2. Recognition alternatives that exactly match owner-created Easter eggs, learned command
   phrases, or a small set of explicit owner-personality controls can outrank a near-sounding
   generic first candidate. Existing wake, state-machine, confidence, echo, and media gates
   remain untouched.
"""
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
        raise SystemExit("usage: public_web_voice_owner_repair_v1_29.py <reconstructed-source>")
    root = Path(sys.argv[1])
    java = root / "app/src/main/java/com/pineapple/sage"
    command = java / "SageCommandEngine.java"
    host_activity = java / "SageHostInspectorActivity.java"
    owner = java / "SageOwnerExperience.java"
    voice = java / "SageVoiceService.java"
    for required in (command, host_activity, owner, voice):
        if not required.is_file():
            raise SystemExit(f"missing reconstructed source: {required.name}")

    # Normal Sage should not manufacture a browsing ban. Android/the installed browser owns
    # URL capability. This does not alter the private-LAN scanner/inspector scope.
    replace_once(
        command,
        '''        if (lower.contains(".onion") || lower.contains("dark web") || lower.startsWith("tor:")) {\n            return new Result("I will not open onion or dark-web links.");\n        }\n''',
        '',
        "remove app-created URL content ban",
    )

    # If the owner types a website into the narrowly named host inspector, route it to the
    # browser rather than attempting/scolding with the private-host boundary.
    replace_once(
        host_activity,
        '''import android.os.Bundle;\n''',
        '''import android.os.Bundle;\nimport android.content.Intent;\nimport android.net.Uri;\n''',
        "host public-web imports",
    )
    replace_once(
        host_activity,
        '''    private void confirm(){String target=ip.getText().toString().trim();if(!SageNetworkScanner.isPrivate(target+"/32")){Toast.makeText(this,"Only an exact private IPv4 host is allowed.",Toast.LENGTH_LONG).show();return;}\n        SageConfirmation.require(this,"Inspect one saved private-LAN host",target,"INTERNET; exact host must already exist in Sage's saved snapshot","Private-LAN packets only","Cancel immediately; saved network snapshots are unchanged",()->start(target));}\n''',
        '''    private void confirm(){String target=ip.getText().toString().trim();\n        if(looksLikePublicWebsite(target)){openPublicWebsite(target);return;}\n        if(!SageNetworkScanner.isPrivate(target+"/32")){Toast.makeText(this,"That is not a saved private-LAN IP. For a public site, enter its website address instead.",Toast.LENGTH_LONG).show();return;}\n        SageConfirmation.require(this,"Inspect one saved private-LAN host",target,"INTERNET; exact host must already exist in Sage's saved snapshot","Private-LAN packets only","Cancel immediately; saved network snapshots are unchanged",()->start(target));}\n    private boolean looksLikePublicWebsite(String value){String lower=value==null?"":value.trim().toLowerCase(java.util.Locale.US);return lower.startsWith("http://")||lower.startsWith("https://")||lower.startsWith("www.")||lower.matches(".*\\\\.[a-z]{2,}([/:?#].*)?$")||lower.matches(".* dot (com|org|net|io|ai|co)(/.*)?$");}\n    private void openPublicWebsite(String value){String url=value.trim().replace(" dot com",".com").replace(" dot org",".org").replace(" dot net",".net").replace(" dot io",".io").replace(" dot ai",".ai").replace(" dot co",".co");if(!url.matches("(?i)^https?://.*"))url="https://"+url;try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));SageDiagnostics.appendEvent(this,"PUBLIC WEB ROUTE","host-inspector input handed to browser url="+url);status.setText("Opening public website in your browser");}catch(Exception error){Toast.makeText(this,"No browser could open that address.",Toast.LENGTH_LONG).show();SageDiagnostics.recordError(this,"Public website handoff failed: "+error);}}\n''',
        "host public-web handoff",
    )

    # Make owner personalization useful to recognition selection. Exact owner-created phrases
    # are stronger evidence than a generic same-length homophone candidate.
    replace_once(
        owner,
        '''    static String recoverCandidate(ArrayList<String> choices, String selected) {\n        String normalizedSelected = normalize(selected);\n        int selectedWords = wordCount(normalizedSelected);\n        if (choices == null || choices.isEmpty() || selectedWords < 1 || selectedWords > 2) {\n            return selected;\n        }\n\n        String best = selected;\n        int bestWords = selectedWords;\n        for (String choice : choices) {\n            String normalizedChoice = normalize(choice);\n            if (normalizedChoice.isEmpty() || normalizedChoice.equals(normalizedSelected)) continue;\n            if (!normalizedChoice.startsWith(normalizedSelected + " ")) continue;\n            int words = wordCount(normalizedChoice);\n            if (words <= bestWords || words > 12 || normalizedChoice.length() > 160) continue;\n            best = choice == null ? selected : choice.trim();\n            bestWords = words;\n        }\n        return best;\n    }\n''',
        '''    static String recoverCandidate(Context context, ArrayList<String> choices, String selected) {\n        String normalizedSelected = normalize(selected);\n        if (choices == null || choices.isEmpty()) return selected;\n\n        // Owner-defined exact phrases are authoritative recognition evidence. This is only\n        // transcript selection; it grants no permission or execution authority.\n        String ownerMatch = ownerDefinedAlternative(context, choices, normalizedSelected);\n        if (!ownerMatch.isEmpty()) return ownerMatch;\n\n        int selectedWords = wordCount(normalizedSelected);\n        if (selectedWords < 1 || selectedWords > 2) return selected;\n        String best = selected;\n        int bestWords = selectedWords;\n        for (String choice : choices) {\n            String normalizedChoice = normalize(choice);\n            if (normalizedChoice.isEmpty() || normalizedChoice.equals(normalizedSelected)) continue;\n            if (!normalizedChoice.startsWith(normalizedSelected + " ")) continue;\n            int words = wordCount(normalizedChoice);\n            if (words <= bestWords || words > 12 || normalizedChoice.length() > 160) continue;\n            best = choice == null ? selected : choice.trim();\n            bestWords = words;\n        }\n        return best;\n    }\n\n    private static String ownerDefinedAlternative(Context context, ArrayList<String> choices, String normalizedSelected) {\n        if (context == null || choices == null) return "";\n        for (String choice : choices) {\n            String normalized = normalize(choice);\n            if (normalized.isEmpty() || normalized.equals(normalizedSelected)) continue;\n            if (SageEasterEggStore.find(context, choice) != null || savedAlias(context, normalized)\n                    || explicitOwnerPersonalityPhrase(normalized)) {\n                return choice.trim();\n            }\n        }\n        return "";\n    }\n\n    private static boolean savedAlias(Context context, String normalized) {\n        java.util.Set<String> saved = context.getSharedPreferences("sage_state", Context.MODE_PRIVATE)\n                .getStringSet("phrase_aliases", java.util.Collections.emptySet());\n        if (saved == null) return false;\n        for (String entry : saved) {\n            int split = entry == null ? -1 : entry.indexOf('\\t');\n            if (split > 0 && normalized.equals(entry.substring(0, split))) return true;\n        }\n        return false;\n    }\n\n    private static boolean explicitOwnerPersonalityPhrase(String normalized) {\n        return normalized.equals("you can cuss around me")\n                || normalized.equals("you can swear around me")\n                || normalized.equals("cuss around me")\n                || normalized.equals("swear around me")\n                || normalized.equals("unfiltered mode")\n                || normalized.equals("casual mode")\n                || normalized.equals("clean mode")\n                || normalized.equals("red queen mode");\n    }\n''',
        "owner-aware candidate recovery",
    )
    replace_once(
        voice,
        '''candidate = SageOwnerExperience.recoverCandidate(finalChoices, candidate);''',
        '''candidate = SageOwnerExperience.recoverCandidate(SageVoiceService.this, finalChoices, candidate);''',
        "voice owner-aware recovery call",
    )

    # Upgrade diagnostics so future misses tell us WHY an alternate won.
    replace_once(
        owner,
        '''                        + " reason=prefix_fragment_alternate");''',
        '''                        + " reason=owner_defined_or_prefix_alternate");''',
        "voice recovery diagnostic reason",
    )

    print("Applied public-web/private-LAN separation and owner-aware voice alternative recovery")


if __name__ == "__main__":
    main()
