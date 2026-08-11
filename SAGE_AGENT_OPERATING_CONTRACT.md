# Sage Agent Operating Contract

This file governs any coding agent working on Sage Commander or Sage Forge.

## Non-negotiable release identity

- Android package: `com.pineapple.sagecommander.stable`
- Preserve the permanent signing identity.
- Preserve install-over-existing compatibility and saved app data.
- Do not require uninstall, factory reset, or data wipe unless the owner explicitly approves a separately justified device-level migration.

## Architecture contract

- One Sage. Do not create competing user-facing Sage personalities or assistants.
- Add capability; do not replace working architecture unless evidence proves the existing implementation is broken.
- Red Queen remains the hidden high-authority workspace, not a separate AI.
- Adobe/creative, coding, Forge, package, media, network, and other specialists stay behind Sage as internal routing targets.
- Preserve working wake profiles, memories, Brain, conversation state machine, Workbench, Toolbelt, Forge, accessibility controls, package tools, diagnostics, and existing user data.
- Do not weaken real consequence boundaries. Reduce nuisance friction only where actions are read-only, reversible, or already owner-authorized.

## Change discipline

1. Work only in an isolated branch/worktree.
2. Reproduce or prove the target problem before editing.
3. Make the smallest maintainable change that solves the stated task.
4. Do not modify unrelated files.
5. Do not redesign the speech state machine, Brain, signing, package identity, or persistence layers unless the task explicitly requires it and evidence proves the current implementation is faulty.
6. Add or update a deterministic regression for every behavior change.
7. Run the relevant regression immediately.
8. Run the full Sage regression gate before declaring the checkpoint complete.
9. A failed regression blocks completion. Repair and rerun until green.
10. Show the exact diff, tests run, failures encountered, and remaining risks.

## Secret and privilege boundary

Never print, upload, rewrite, or send to external model providers:

- signing keystore material
- tokens, passwords, API keys, cookies, session secrets, `.env` values
- private owner data
- device-owner provisioning secrets

Read-only inspection of paths or filenames is acceptable when needed, but redact secret values from reports.

Do not execute destructive or privilege-changing operations such as uninstall, wipe, factory reset, bootloader unlock, flash, root, `dd`, or package removal unless the owner explicitly assigned that exact task.

## Definition of done for a checkpoint

A checkpoint is complete only when:

- the target behavior is implemented,
- its dedicated regression passes,
- inherited regressions pass,
- deterministic reconstruction still passes,
- package/signing continuity is unchanged,
- Java/native compilation passes where applicable,
- unit tests and lint pass,
- the agent reports the exact commit SHA and files changed.

No intermediate APK should be presented as final. A final APK is built only after all checkpoints and the second full-system verification pass are green.
