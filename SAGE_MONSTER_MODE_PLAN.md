# Sage Monster Mode additive owner-control upgrade

This branch is intentionally additive. It starts from the verified Sage 1.29 recovery head and preserves the working speech state machine, package identity, permanent signing identity, saved app data, wake profiles, Brain, Workbench, Toolbelt, Forge architecture, Red Queen vault, and existing tests.

## Rules

- Add capability. Remove unnecessary limits.
- Do not replace working architecture unless evidence proves it is broken.
- Preserve the Sage 1.26 speech-state machine and its lifecycle invariants.
- Keep Android/platform permission boundaries intact.
- Prefer owner authority + audit + rollback over repeated confirmation friction.
- Every Monster Mode change must be reconstructible, testable, and reversible by removing one final additive patch.

## First slice

1. Owner-control longevity
   - Red Queen authorization lasts 60 minutes while the device remains unlocked.
   - Normal app switching does not revoke authorization.
   - Device lock, process death/reboot, or explicit lock still revokes authority.

2. Voice breathing room
   - Keep the existing recognizer and state machine.
   - In Monster Mode, extend command completion/silence windows without changing default Sage behavior.
   - Preserve final + partial alternatives and write richer candidate diagnostics.
   - Add a conservative contextual fallback that may recover a non-empty alternate only when the normal selector produced no candidate and the alternate is not classified as Sage echo.

3. Guardrails
   - Monster Mode is owner-controlled and only usable while Red Queen authority is active.
   - It does not create new Android permissions, shell authority, public-network scope, or signing authority.
   - Existing tests remain inherited and a new Monster Mode regression test is added.
