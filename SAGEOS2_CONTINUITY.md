# SageOS 2 continuity handoff

This file exists so the project can survive a lost conversation, model change, or long gap without asking the owner to reconstruct history.

## Resume here

- Repository: `superkat13/JE`
- Active branch: `sageos-2`
- Master workboard: GitHub issue #21, `SageOS 2 master build`
- Product target: one Sage, one SageOS 2.0 lineage
- Read `SAGEOS2_MASTER_PLAN.md` before changing architecture.
- Read branch HEAD and the latest `Verify SageOS 2` workflow run before writing new code.
- Never create a version-per-fix branch or APK family. Use commits and CI checkpoints on `sageos-2`.

## Owner decisions that are hard constraints

- SageOS 2's final destination has real root-backed system capability.
- The AI process itself does not run unrestricted as root. It uses a small privileged root broker with structured requests and audit results.
- No Shizuku. It is intentionally excluded from source and CI should reject its return.
- Do not make the owner repeatedly install test APKs. CI/unit/static/lint gates come first; physical tablet testing happens only at meaningful integration gates.
- Voice and text are equal inputs to the same Sage runtime.
- `Sage` -> `Yes` -> command.
- If the owner says `Sage` while Sage is thinking, acknowledge immediately without cancelling the active thought.
- The owner phrase `Do you feel like chicken tonight?` launches the named owner workflow silently. The workflow itself remains a separate scoped/authorized module.
- One assistant identity. Red Queen and other modes are facets of Sage, not separate assistants.
- iPhone integration is an external future bridge and must not block SageOS 2.

## Architecture already established

- Clean Kotlin Android project under `sageos2/`.
- Single `SageTurnCoordinator` owns turn/listening state.
- Listening modes are OFF, WAKE_ONLY, COMMAND, FOLLOW_UP.
- Typed messages queue while a turn is busy.
- Stale callbacks are rejected by turn/generation identity.
- Fast device commands are separated from deep Brain requests.
- `BrainEngine`, `FastActionEngine`, `SpeechPort`, `WorkflowEngine`, scheduler, diagnostics observer, `CapabilityBroker`, and `RootBrokerClient` are replaceable ports.
- Android VoiceInteractionService/SessionService architecture exists with heavy work kept outside the always-running interactor process.
- Legacy Android authority component names are preserved for the eventual signed in-place migration from Sage 1.x.
- Signing lineage from 1.33.3 is donor/reference material for final replacement, not for random debug installs.

## Reliability rules

- Do not call a feature complete because it compiled.
- Every state-machine fix gets a regression test.
- Every Android component identity that must survive upgrade gets a contract test/static gate.
- No silent fallback that changes security/authority semantics.
- No broad device capability is claimed ACTIVE unless Android reports it active.
- Device Owner/platform privilege/root broker are provisioning/system-image paths, not pretend in-app switches.
- Deep Brain latency must never block deterministic device commands.
- CI fails if Shizuku references return to SageOS 2.

## Next build gates

1. Finish deterministic Android fast-action execution through Accessibility/normal Android APIs, escalating only structured privileged tasks to RootBrokerClient.
2. Finish slim wake/recognition/TTS adapter and wire it to the runtime without introducing a second state machine.
3. Wire real local Brain and Forge adapters with cancellation/health/latency telemetry.
4. Migrate Owner Apps and Sage Core/memory as readable new source, not reconstruction patches.
5. Add per-turn diagnostic persistence/export.
6. Build the SageOS root broker/system-image integration, signed staged update, health check, rollback, then physical VASOUN L10_T05 acceptance.

If a future assistant cannot determine what to do next, inspect issue #21 and the latest failing/passing CI run rather than asking the owner to retell the project.
