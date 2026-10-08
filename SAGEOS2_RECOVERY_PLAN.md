# SageOS 2 targeted recovery plan

Date: 2026-09-10
Constraint: one Sage, same repository/branch/package/signing/data lineage. No model replacement and no physical APK handoff until the inherited Brain and P0/P1 experience are proven.

## Recovery rules

- Keep the new coordinator where it preserves strict lifecycle and deduplication behavior.
- Keep Chat-first UI, lazy Brain loading, durable history, staged watchdogs, isolated wake process, capability security checks, and the Advanced/Diagnostics split.
- Port or reconnect proven behavior at existing seams. Do not merge old branches or revive the old application wholesale.
- Treat all legacy app-private records as owner data: read/migrate idempotently, never delete or overwrite the source as part of recovery.
- No version bump, release artifact, installation, uninstallation, data clear, signing mutation, or GGUF change during this audit.

## P0 — Sage basically works again

1. **Make fast routing executable by construction.** Use `FastCommandParser` as the single eligibility check. Add regression tests proving every fast route parses and unsupported device wording reaches the Brain unchanged.
2. **Reconnect preserved local command speech.** Restore the verified historical Sherpa RecognitionService using the existing private 1.31–1.33 model directory. Select it explicitly only when ready; retain Android as fallback. Make the component private. Do not install or mutate speech model data.
3. **Keep the candidate 206 miss-loop repair and attack it.** Cover one miss, duplicate final, stale error/final, TTS completion, follow-up expiry, stop/start, and wake-during-thinking interleavings.
4. **Add safe inherited-Brain identification tooling.** Parse GGUF metadata/tensor descriptors with strict bounds and no tensor payload. Record exact size/hash on the connected owner device. Do not benchmark alternatives before this succeeds.
5. **Prove text integration.** Immediate bubble before cold load, silent typed answer, failure recovery, exact wording, cold first turn, clean faster warm turn.
6. **Keep bounded tool reasoning coherent.** A real bracketed tool result must retain the task and tool contracts for the next Brain step; action execution stays validated and capped.

P0 exit: physical typed chat and spoken wake/command/answer both work; one recognition miss cannot loop; inherited GGUF identity is exact; no owner data changed or lost.

## P1 — Sage feels like Sage again

1. Render migrated owner Core as an explicit high-priority identity section, without overwriting or duplicating it.
2. Strengthen only absent/fresh default identity fields with the proven direct, capable, warm, persistent Sage language. Never replace owner-edited Core.
3. Replace whole-render head/tail truncation with per-section budgets and relevant memory/history selection so identity, owner instructions, current wording, and relevant continuity always survive.
4. Expose a quiet Brain/route availability cue on Chat without exposing raw developer state.
5. Make imported wake profiles truthful: supported profiles work; unsupported phrases say why. Then implement verified token compilation and legacy profile-command dispatch with exactly-once guards.
6. Run an owner-centered conversation rubric on the exact inherited Brain: identity, familiarity, tone, initiative, continuation, uncertainty, action truthfulness, memory recall, and failure recovery.

P1 exit: owner accepts that text and voice feel like the same Sage; Core/personality/memory prompts are captured and verified; wake profiles used by the owner work; no generic fallback identity appears.

## P2 — Restore lost capability

Restore in small verified slices:

1. Semantic accessibility targeting and later numbered overlay.
2. Owner App startup procedures using validated existing representations.
3. Existing compiled workflows and unfinished task visibility/resumption.
4. Notifications summary, settings, timer/alarm, screenshot, media, and app close.
5. Preserved custom replies/media, Voice Studio/Speech Lab, Creative/Discover features.
6. Safe package/file/network/Workbench functions and Forge owner controls.
7. Autonomy/self-repair records only after a no-replay migration audit.

Every slice needs an absent-permission path, an exactly-once rule for consequential actions, an owner-visible success/failure result, and a physical test.

## P3 — Polish and performance

- Add privacy-safe timestamps for wake, ASR ready/final, queue, model-load start/end, first token, completion, TTS, and wake-ready.
- Measure cold/warm latency, peak/resident RAM, cancellation, ten-turn stability, process death, and thermal degradation with the inherited Brain.
- Tune output/prompt budgets only from measured owner-device behavior.
- Repair visual spacing/copy and restore valued backgrounds without crowding Chat.
- Compare release builds reproducibly and keep diagnostics export owner-readable.

## P4 — Future SageOS/root work

- Resume root broker, system-image, platform privilege, or expanded Forge execution only after P0/P1 physical acceptance.
- Keep caller identity, transport integrity, OS integrity, argument validation, consent, and audit checks outside personality prompts.
- Evaluate whether any former Shizuku capability remains necessary once the actual SageOS authority path exists; do not mix that decision into behavioral recovery.

## Verification ladder before any APK handoff

The connected L10_T05 currently runs signed candidate 205. Its release sandbox denies `run-as`, and its model screen has no embedded GGUF report. The read-only identity action is now in the existing 206 source, but no model identity can be claimed until a signed, lineage-verified in-place 206 candidate is approved and the owner taps **Inspect installed Brain (read-only)**. That report must be captured before any model recommendation, replacement, or comparative benchmark. The 205 APK hash and signer are already verified; the GGUF hash remains unknown.

1. Focused unit tests for each repaired seam.
2. Full unit tests and instrumentation-compilation checks.
3. Release lint and clean arm64 build.
4. Second independent clean build from the same commit.
5. Unzip/payload comparison with documented expected nondeterminism.
6. Package/version/manifest/native ABI and llama.cpp pin checks.
7. Signing configuration/lineage verification without exposing secrets.
8. Migration fixtures: Core, memory, owner apps, wake profiles, workflows/tasks, learned replies; repeat run must be a no-op.
9. Prompt integration: Core + owner + self + relevant memory + history + exact wording; tool contract only when applicable.
10. Recognition adversarial suite and cold/warm GGUF integration.
11. Only then: smallest owner-device acceptance with in-place update safeguards. Do not hand off merely because compilation is green.

## Minimal physical acceptance procedure (when the gate is reached)

1. Before update, export/share diagnostics and confirm package, signer, Brain file size/hash, Core/memory/Owner App/wake counts, and unfinished task count. Do not expose private contents in public logs.
2. Install strictly as an in-place update; reject any flow asking to uninstall or clear data.
3. Launch: Chat appears immediately; submit one identity/familiarity prompt. The exact owner bubble appears before cold load and the typed reply stays silent.
4. Submit a second continuation; confirm it is faster, coherent, and uses the first turn.
5. Say “Sage,” hear one acknowledgement, speak one command, receive one answer, complete one follow-up, then confirm wake-ready.
6. Produce one recognition miss; hear at most one miss line, wait, and confirm no recursive loop.
7. During a slow Brain turn, say “Sage”; hear only “I'm thinking,” with one original answer.
8. Verify Core, a known memory, one learned reply, one Owner App, one workflow, and each real wake profile without editing them.
9. Export post-update diagnostics and compare counts plus Brain digest. Stop immediately on data mismatch, crash, ANR, duplicate action, generic identity, or voice loop.
