# Sage Commander 1.25.0

## 1.25.0 supervised self-repair foundation

- Adds the top-level Sage Workbench with modular Repair, Package, Home Lab / Network Map, Authority, and Device Tools areas.
- Inspects local APK package/version/minSdk/permissions/signing certificate/file digest, blocks identity and downgrade risks, supports an owner-approved exact identity allowlist, and delegates installation to Android's protected installer.
- Adds confirmed, cancellable, rate-limited local `/24` discovery with snapshot comparison, observed service visibility, owner labels, trust state, hidden infrastructure, and explicit uncertainty labels.
- Adds shared progress/cancellation/activity logging and owner confirmation contracts for sensitive operations.
- Adds real local file SHA-256, flashlight, battery/storage, calculator, Base64/hex/URL, JWT inspection-only, password-strength estimation, and Android settings shortcuts; QR scanning remains visibly disabled until a verified offline decoder is available.
- Adds local “diagnose yourself” and “diagnose and prepare a fix” commands.
- Produces an owner-reviewed ZIP containing constrained JSON, readable Markdown, and sanitized logs.
- Adds an Authority & Permissions dashboard that separates Active, Available, Needs setup, and Unsupported states.
- Adds explicit export approval, secret redaction, fixed repair classifications, and an operation allowlist.
- Adds a schema-constrained, artifact-only GitHub repair workflow with no automatic push, merge, release, or install.
- Extends recognition acceptance/retry diagnostics and memory recall diagnostics.
- Preserves the 1.24.2 overlay, speech, memory, appearance, identity, signing, Brain, wake, and tablet-control behavior.
- Advances the stable release to versionCode 37 / versionName 1.25.0.

## 1.24.2 tablet release candidate

- Decouples numbered overlays from activity resume and voice follow-up cancellation.
- Records timestamped overlay creation, ignored events, and every legitimate clear reason.
- Adds configurable 15s, 30s, 60s, 2m, and persistent overlay lifetimes.
- Prefers complete final speech results, logs recognition decisions, and retries once for incomplete or low-confidence recognition.
- Makes memory saves idempotent and persistent, adds one-shot follow-up and last-memory recall, and rejects duplicate acknowledgements.
- Adds persistent dark, dim, and true-black appearance modes, optional document-provider background images, and readable intensity control.
- Advances the stable package to versionCode 36 / versionName 1.24.2 without changing signing identity.

## Completed

- Preserves the permanent package identity and signing path.
- Advances the update identity to version 1.24.1 / versionCode 35.
- Renames the diagnostics section to **Sage Diagnostics and Continuity**.
- Includes saved Custom Wake Profiles in the shareable continuity report.
- States Sage's continuity invariant directly in the exported report.
- Keeps numbered accessibility overlays visible through same-app overlay-window churn.
- Runs the complete Sage 1.21, 1.22, and 1.23 regression suite.
- Reconstructs the source independently a second time and compares the resulting trees.
- Verifies ZIP integrity, 16 KiB alignment, signing certificate, package identity, and version twice after APK assembly.

## Preserved

- Existing settings and app data
- Sage Brain and its watchdog
- Custom Wake Profiles
- Complete-command recognition
- Red Queen mode
- Translation, saved lessons, media responses, and tablet controls

## Rollback

Sage Commander 1.23 remains the known-good previous release. Do not uninstall Sage before updating, because uninstalling removes Android app data. The 1.24.1 APK must match the permanent signing certificate before installation.
