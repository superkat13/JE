# Kilo Start Here for Sage

Open the repository root in VS Code, open Kilo Agent Manager, and create isolated worktrees from the current Sage branch. `AGENTS.md` is the governing contract for every agent.

## First agent: background-survival auditor

Paste this as the first task:

> Inspect the current Sage repository read-only. Focus only on Android background voice-service survival and lifecycle behavior. Do not modify anything. Verify the foreground-service declaration, microphone foreground-service type, `START_STICKY` behavior, `onTaskRemoved` handling, partial wake-lock lifecycle, wake-listener re-arming, explicit Stop behavior, notification behavior, and any Android background/battery restrictions visible in code. Confirm whether the Checkpoint 11 repair is internally coherent and identify any remaining device-only risks. Do not redesign the voice state machine, recognizer, Brain, wake profiles, or existing architecture. Cite exact files/classes/methods and separate confirmed evidence from device-only assumptions.

## Second agent: regression/test auditor

Run only after the first agent is underway:

> Audit Sage's automated test coverage read-only. Do not modify code. Map the current 1.29 regression suite to the preserved features: voice state machine, wake profiles, memory, Brain, Red Queen, Workbench, Toolbelt, Accessibility, Forge, package identity/signing continuity, background survival, and install-over-existing compatibility. Identify the three highest-value missing regression tests, with exact target files and assertions. Do not propose architecture changes.

## Third agent: Forge boundary auditor

Use only when a third parallel session is useful:

> Inspect Sage Forge and Android-to-Forge integration read-only. Do not modify code. Verify pairing/trust boundaries, job isolation, result handling, reconnect/recovery behavior, command allowlists, and whether Forge is kept behind one-Sage routing rather than exposed as another assistant. Report exact evidence and the highest-priority reliability gap only.

## Repair task template

After an audit identifies one confirmed blocker, create a fresh worktree and use:

> Fix only the confirmed blocker described below. First reproduce it or add a failing regression test. Implement the smallest maintainable additive repair. Preserve all working Sage architecture and obey `AGENTS.md`. Run the focused regression, then the applicable full suite. Do not merge, bump versions, modify signing material, build a final APK, or change unrelated code. End with the exact diff summary, tests run, results, and any device-only behavior still unproven.
>
> BLOCKER: [paste one confirmed blocker here]

## Review rule

Never integrate a Kilo worktree because the agent says it is done. Review the live diff, test output, and remaining assumptions first. Apply only narrow changes that preserve the Sage contract.
