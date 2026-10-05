# Build 218 release preparation

Status: combined candidate awaiting Android and signing verification.

Base: PR #59 at 687033d3954d88b3f6b4f2eba5f5f1ef3c918dce. Includes the build 217 prerequisite, direct self-check, speech ownership fix from PR #60, recovery checkpoint preservation and selection fixes, Brain callback ownership, and the tool watchdog.

Integrated OpenCode PR #61 at c75d3a742c78d503684782b322cff83b420fa355, including per-turn capture claims, the mixed 20-turn lifecycle test and refusal diagnostic coverage. Added the new capture-claim test suite to the selected lab runner. Full combined CI and device acceptance remain pending.

## Release gates

- Speech revision integrated above; retain that source provenance.
- Run full Android verification and signed-candidate workflow on the final combined source.
- Verify package com.pineapple.sagecommander.stable, versionCode 218, versionName 2.0.0, arm64-v8a payload and the existing Android 13 signing rotation lineage.
- Retain the APK SHA-256, source commit, test results and signing verification report with delivery.
- Deliver SageOS-2.0.0-build-218.apk as an in-place update. Do not request uninstall or clearing owner data.

## Short tablet acceptance

1. Install over the existing Sage installation when the final candidate is delivered.
2. In text, ask `check yourself`, then send a separate ordinary request.
3. Press Talk, cancel, and immediately press Talk again; confirm the second turn responds. Repeat once after backgrounding.
4. Export one diagnostic report if a step fails.

Automated tests and signing checks do not establish physical microphone behavior or private-model performance. No tablet acceptance has been performed for build 218.
