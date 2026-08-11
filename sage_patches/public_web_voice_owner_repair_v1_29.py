#!/usr/bin/env python3
"""Repair owner-tablet public-web routing and owner-aware transcript selection.

This patch is additive around the verified 1.29 architecture. The private-LAN inspector
stays private and exact-host-only. Normal URL handoff is not treated as private scanning.
Owner-defined harmless phrases can influence transcript selection, but never authority.
"""
from pathlib import Path
import re
import sys


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def replace_regex_once(path: Path, pattern: str, replacement: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    updated, count = re.subn(pattern, lambda _m: replacement, text, count=1, flags=re.DOTALL)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one regex match, found {count}")
    path.write_text(updated, encoding="utf-8")


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

    replace_once(
        command,
        '''        if (lower.contains(".onion") || lower.contains("dark web") || lower.startsWith("tor:")) {\n            return new Result("I will not open onion or dark-web links.");\n        }\n''',
        '',
        "remove app-created URL content ban",
    )

    replace_once(
        host_activity,
        'import android.os.Bundle;\n',
        'import android.os.Bundle;\nimport android.content.Intent;\nimport android.net.Uri;\n',
        "host public-web imports",
    )

    host_confirm = r'''    private void confirm(){String target=ip.getText().toString().trim();
        if(looksLikePublicWebsite(target)){openPublicWebsite(target);return;}
        if(!SageNetworkScanner.isPrivate(target+"/32")){Toast.makeText(this,"That is not a saved private-LAN IP. For a public site, enter its website address instead.",Toast.LENGTH_LONG).show();return;}
        if(SageOwnerAuthorityPolicy.verifiedOwnerMayProceedReadOnly(this,"selected private-LAN host inspection")){start(target);return;}
        SageConfirmation.require(this,"Inspect one saved private-LAN host",target,"INTERNET; exact host must already exist in Sage's saved snapshot","Private-LAN packets only","Cancel immediately; saved network snapshots are unchanged",()->start(target));}
    private boolean looksLikePublicWebsite(String value){String lower=value==null?"":value.trim().toLowerCase(java.util.Locale.US);if(lower.matches("[0-9.]+"))return false;return lower.startsWith("http://")||lower.startsWith("https://")||lower.startsWith("www.")||lower.contains(" dot com")||lower.contains(" dot org")||lower.contains(" dot net")||lower.contains(" dot io")||lower.contains(" dot ai")||lower.contains(" dot co")||lower.endsWith(".com")||lower.endsWith(".org")||lower.endsWith(".net")||lower.endsWith(".io")||lower.endsWith(".ai")||lower.endsWith(".co");}
    private void openPublicWebsite(String value){String url=value.trim().replace(" dot com",".com").replace(" dot org",".org").replace(" dot net",".net").replace(" dot io",".io").replace(" dot ai",".ai").replace(" dot co",".co");if(!url.matches("(?i)^https?://.*"))url="https://"+url;try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));SageDiagnostics.appendEvent(this,"PUBLIC WEB ROUTE","host-inspector input handed to browser url="+url);status.setText("Opening public website in your browser");}catch(Exception error){Toast.makeText(this,"No browser could open that address.",Toast.LENGTH_LONG).show();SageDiagnostics.recordError(this,"Public website handoff failed: "+error);}}
'''
    replace_regex_once(
        host_activity,
        r'    private void confirm\(\)\{.*?\n    private void start\(String target\)\{',
        host_confirm + '    private void start(String target){',
        "host public-web handoff",
    )

    replace_once(
        owner,
        '''    static String recoverCandidate(ArrayList<String> choices, String selected) {\n        String normalizedSelected = normalize(selected);\n        int selectedWords = wordCount(normalizedSelected);\n        if (choices == null || choices.isEmpty() || selectedWords < 1 || selectedWords > 2) {\n            return selected;\n        }\n\n        String best = selected;\n        int bestWords = selectedWords;\n        for (String choice : choices) {\n            String normalizedChoice = normalize(choice);\n            if (normalizedChoice.isEmpty() || normalizedChoice.equals(normalizedSelected)) continue;\n            if (!normalizedChoice.startsWith(normalizedSelected + " ")) continue;\n            int words = wordCount(normalizedChoice);\n            if (words <= bestWords || words > 12 || normalizedChoice.length() > 160) continue;\n            best = choice == null ? selected : choice.trim();\n            bestWords = words;\n        }\n        return best;\n    }\n''',
        '''    static String recoverCandidate(Context context, ArrayList<String> choices, String selected) {\n        String normalizedSelected = normalize(selected);\n        if (choices == null || choices.isEmpty()) return selected;\n\n        String ownerMatch = ownerDefinedAlternative(context, choices, normalizedSelected);\n        if (!ownerMatch.isEmpty()) return ownerMatch;\n\n        int selectedWords = wordCount(normalizedSelected);\n        if (selectedWords < 1 || selectedWords > 2) return selected;\n        String best = selected;\n        int bestWords = selectedWords;\n        for (String choice : choices) {\n            String normalizedChoice = normalize(choice);\n            if (normalizedChoice.isEmpty() || normalizedChoice.equals(normalizedSelected)) continue;\n            if (!normalizedChoice.startsWith(normalizedSelected + " ")) continue;\n            int words = wordCount(normalizedChoice);\n            if (words <= bestWords || words > 12 || normalizedChoice.length() > 160) continue;\n            best = choice == null ? selected : choice.trim();\n            bestWords = words;\n        }\n        return best;\n    }\n\n    private static String ownerDefinedAlternative(Context context, ArrayList<String> choices, String normalizedSelected) {\n        if (context == null || choices == null) return "";\n        for (String choice : choices) {\n            String normalized = normalize(choice);\n            if (normalized.isEmpty() || normalized.equals(normalizedSelected)) continue;\n            if (SageEasterEggStore.find(context, choice) != null || savedAlias(context, normalized)\n                    || explicitOwnerPersonalityPhrase(normalized)) {\n                return choice.trim();\n            }\n        }\n        return "";\n    }\n\n    private static boolean savedAlias(Context context, String normalized) {\n        java.util.Set<String> saved = context.getSharedPreferences("sage_state", Context.MODE_PRIVATE)\n                .getStringSet("phrase_aliases", java.util.Collections.emptySet());\n        if (saved == null) return false;\n        for (String entry : saved) {\n            int split = entry == null ? -1 : entry.indexOf('\\t');\n            if (split > 0 && normalized.equals(entry.substring(0, split))) return true;\n        }\n        return false;\n    }\n\n    private static boolean explicitOwnerPersonalityPhrase(String normalized) {\n        return normalized.equals("you can cuss around me")\n                || normalized.equals("you can swear around me")\n                || normalized.equals("cuss around me")\n                || normalized.equals("swear around me")\n                || normalized.equals("unfiltered mode")\n                || normalized.equals("casual mode")\n                || normalized.equals("clean mode")\n                || normalized.equals("red queen mode");\n    }\n''',
        "owner-aware candidate recovery",
    )
    replace_once(
        voice,
        'candidate = SageOwnerExperience.recoverCandidate(finalChoices, candidate);',
        'candidate = SageOwnerExperience.recoverCandidate(SageVoiceService.this, finalChoices, candidate);',
        "voice owner-aware recovery call",
    )
    replace_once(
        owner,
        '                        + " reason=prefix_fragment_alternate");',
        '                        + " reason=owner_defined_or_prefix_fragment_alternate");',
        "voice recovery diagnostic reason",
    )

    print("Applied public-web/private-LAN separation and owner-aware voice alternative recovery")


if __name__ == "__main__":
    main()
