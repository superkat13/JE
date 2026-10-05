# Build 218 release preparation

Status: packaging validation; not the final combined update.

Base: PR #59 at 687033d3954d88b3f6b4f2eba5f5f1ef3c918dce. Includes the build 217 prerequisite, direct self-check, speech ownership fix from PR #60, recovery checkpoint preservation and selection fixes, Brain callback ownership, and the tool watchdog.

Pending: review and integrate OpenCode PR #61's completed speech lifecycle work, including the cancellation-before-capture fix. The currently published fea0abf revision is not included in this preparation branch.

## Release gates

- Integrate the completed speech revision and record its exact commit.
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
