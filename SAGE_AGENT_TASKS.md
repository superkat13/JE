# Ready-to-run Sage agent tasks

Use one task per isolated worktree. Do not combine them.

## Task A — background survival audit

Read `SAGE_AGENT_OPERATING_CONTRACT.md` first.

Inspect the current Sage source read-only. Focus only on Android background voice-service survival and lifecycle behavior. Verify foreground-service declaration, microphone foreground-service type, `START_STICKY`, `stopWithTask`, notification lifecycle, `onTaskRemoved`, partial wake-lock lifecycle, wake-listener re-arming, explicit-stop behavior, battery/background restriction exposure, and service restart behavior after process pressure. Do not modify files. Do not redesign the voice state machine, Brain, wake profiles, or command routing. Produce a concise evidence report with exact file/line locations, confirmed strengths, confirmed gaps, and the smallest fixes required, if any.

## Task B — regression blind-spot audit

Read `SAGE_AGENT_OPERATING_CONTRACT.md` first.

Inspect only `sage_tests/`, reconstruction scripts, and GitHub Actions workflows. Do not modify files. Identify behaviors implemented in Sage 1.29 that are not adequately protected by deterministic regression tests. Prioritize false-green risks that could let package identity, signing continuity, saved data compatibility, voice lifecycle, wake profiles, Brain, Red Queen, Forge, Workbench, Toolbelt, accessibility, or background survival regress unnoticed. Return exact missing assertions and the test file where each should live. Do not propose architecture changes.

## Task C — Forge reliability audit

Read `SAGE_AGENT_OPERATING_CONTRACT.md` first.

Inspect `sage_forge/` and its tests only. Do not modify files. Evaluate pairing persistence, authentication assumptions, reconnect behavior, job cancellation, interrupted-job recovery, artifact transfer, allowlists, structured logs, and failure reporting. Separate confirmed implementation from planned/stubbed behavior. Return the three highest-impact reliability gaps with exact files and the smallest maintainable fixes.

## Task D — UI dead-button audit

Read `SAGE_AGENT_OPERATING_CONTRACT.md` first.

Inspect Sage activities, menus, buttons, and navigation declarations. Do not modify files. Find controls that are visible to the owner but are stubbed, no-op, misleading, or route to screens without a working implementation. Pay special attention to Red Queen, Workbench, Toolbelt, package/network/model tools, creative/Adobe surfaces, and owner/device-authority screens. Report only confirmed dead or misleading controls with exact files and handlers. Do not redesign the UI.

## Task E — persistence/data-continuity audit

Read `SAGE_AGENT_OPERATING_CONTRACT.md` first.

Inspect SharedPreferences, files, databases, model paths, wake profiles, memories, Forge trust/pairing state, and migration/version handling. Do not modify files. Identify any code path in the current update that could rename, clear, orphan, overwrite, or stop reading existing Sage 1.29 user data. Return exact evidence and severity. Do not suggest wiping data as a fix.

## Implementation-task template

After an audit identifies one confirmed defect, create a fresh worktree and use this exact pattern:

> Read `SAGE_AGENT_OPERATING_CONTRACT.md`. Fix only: [ONE CONFIRMED DEFECT]. First reproduce or prove it from the current source/tests. Implement the smallest maintainable fix. Do not change unrelated code or redesign working architecture. Add/adjust the narrow regression that proves the defect is fixed. Run that test, then the relevant inherited tests. Report exact files changed, diff summary, commands/tests run, pass/fail results, and any remaining risk. Do not merge and do not build a release APK.
