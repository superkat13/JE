# Sage Commander Agent Contract

This repository contains Sage Commander. Treat the existing architecture and preserved user data as production assets.

## Non-negotiable product rules

- One Sage. Do not create additional user-facing Sage personalities or mode-selection flows.
- Preserve package identity: `com.pineapple.sagecommander.stable`.
- Preserve permanent signing continuity. Do not read, modify, regenerate, replace, expose, or copy signing material.
- Preserve existing app data and install-over-existing update compatibility.
- Do not require uninstall, factory reset, app-data clearing, or device wipe.
- Red Queen remains the hidden elevated workspace. Do not turn it into a second assistant.
- Adobe, coding, Forge, package, media, and other specialties stay behind Sage as internal capabilities.
- Prefer additive changes. Do not rewrite a working subsystem unless a reproducible failing test proves the subsystem itself must change.
- Preserve the stabilized voice/conversation state machine, wake profiles, memories, Brain, Workbench, Toolbelt, Accessibility, Forge, diagnostics, and existing working features.
- Do not weaken consequence boundaries merely to reduce prompts. Read-only and reversible actions may be streamlined; consequential actions must remain appropriately gated.

## Required engineering workflow

1. Work only in the current isolated Kilo worktree/branch.
2. Before editing, reproduce or establish the exact problem with evidence.
3. Implement the smallest maintainable fix.
4. Run the directly relevant regression test first.
5. Then run the applicable full Sage regression gate before declaring success.
6. If any previously green test fails, repair it before advancing.
7. Show the exact diff and remaining known problems.
8. Do not merge branches, push to another branch, tag a release, bump versions, or build/publish a final APK unless explicitly assigned that release task.
9. Do not change unrelated code.
10. Never claim physical-tablet behavior was proven by static CI. Clearly separate static proof from device proof.

## Sensitive material

Do not read or expose:

- `sage_signing/**`
- keystores, JKS/P12/PFX files, private keys, tokens, credentials, or secrets
- `.env` and `.env.*` except safe example/template files

If a task genuinely requires sensitive material, stop and report exactly why instead of attempting access.

## Current background-survival focus

The current preserved branch already contains an additive background-survival repair around the existing `SageVoiceService`. Do not replace the recognizer or voice state machine. Evaluate foreground-service lifecycle, microphone service type, `START_STICKY`, `onTaskRemoved`, wake-lock lifecycle, wake-listener re-arming, explicit Stop behavior, and Android background restrictions with evidence.

## Definition of done for an agent task

A task is done only when the requested behavior is implemented or conclusively investigated, relevant tests pass, regressions are checked, the diff is narrow, and the report separates confirmed evidence from assumptions.
