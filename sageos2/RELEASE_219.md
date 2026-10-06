# Sage build 219

Combines verified build 218 with OpenCode PR #63 at 4d1ed808c30ef60a2dc4b24798d0677642124258 and integration corrections.

Owner commands: “what’s unfinished”, “continue that task”, “cancel that task”. Ambiguous selections ask for a number or exact title. Listing and cancellation do not require the Brain. Continuing needs a ready reasoning model; unavailable-model requests leave saved work intact and explain the blocker.

Integration corrects recursive resume dispatch, unreachable task selection, inaccurate test fixtures, reentrant runtime recovery, and recovery superseding unrelated tasks. Unknown previous action outcomes remain blocked; unexpected dispatch exceptions retain the duplicate-attempt guard. Cancellation saves the cancelled state and does not undo or guarantee interruption of external actions already running.

Local verification: 74 follow-through, runtime and recovery tests passed. Full Android and signed-candidate checks pending. Tablet acceptance remains unverified.

Install as an in-place update over the existing stable Sage app. VersionCode 219; no need to install 217 or 218 first. Models, signing lineage, speech fixes and recovery replay guards are retained.
