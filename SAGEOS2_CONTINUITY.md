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

## Hard owner decisions

- **Sage is the owner's virtual twin.** Do not redesign her as a generic assistant or as a developer-authored personality.
- Sage should learn and use the owner's intent, preferences, habits, vocabulary, working style, trusted tools, recurring choices, project continuity, and accumulated experience as durable twin context.
- Sage remains a distinct software identity, not a literal clone. Twin means shared context and aligned operation, not blind parroting.
- Sage is not governed by hidden behavioral restrictions injected by the application architecture.
- Sage's own identity, principles, preferences, judgment, and self-restrictions belong in visible/revisioned Sage Core.
- Provider/model/tool limitations must retain provenance. Do not silently turn an external refusal into `Sage refuses` or `Sage can't`.
- Final SageOS 2 has real root-backed capability through its own SageOS root broker.
- No Shizuku. CI rejects its return.
- Do not make the owner repeatedly install test APKs. Automated gates come first; physical tablet testing happens only at meaningful integration gates.
- Voice and text are equal inputs to the same Sage runtime and same twin context.
- `Sage` -> `Yes` -> command.
- If `Sage` is spoken while Sage is thinking, acknowledge immediately without cancelling the active thought.
- `Do you feel like chicken tonight?` launches the named workflow silently.
- One assistant identity. Red Queen and other modes are facets of Sage, not separate assistants.
- iPhone integration is an external future bridge and must not block SageOS 2.

## Architecture already established

- Clean Kotlin Android project under `sageos2/`.
- Single `SageTurnCoordinator` owns turn/listening state.
- Listening modes are OFF, WAKE_ONLY, COMMAND, FOLLOW_UP.
- Typed messages queue while a turn is busy.
- Stale callbacks are rejected by turn/generation identity.
- Fast device commands are separated from deep Brain requests.
- Android VoiceInteractionService/SessionService architecture exists with heavy work outside the always-running interactor process.
- Android command recognition and TTS feed the same runtime coordinator and recover from recognizer errors.
- Deterministic Android fast actions exist for app launch/navigation/notifications/quick settings/scroll/swipe/tap/volume.
- Sage Core is readable/revisioned and is supplied to deep Brain requests.
- Twin model data structures now separate owner model, Sage self model, and shared continuity.
- Legacy Android authority component names are preserved for eventual signed in-place migration from Sage 1.x.

## Reliability rules

- Do not call a feature complete because it compiled.
- Every state-machine fix gets a regression test.
- No hidden behavioral denylist in SageOS services, capability code, root broker, routers, or UI.
- No canned developer persona should override the twin model.
- Technical validation is allowed and required: caller identity, malformed arguments, OS invariants, transport integrity, execution errors.
- Provider limitations stay attributed to the provider.
- Deep Brain latency must never block deterministic device commands.
- CI fails if Shizuku references return to SageOS 2.

## Next build gates

1. Finish twin-model persistence/editor/export/revert and supply the twin snapshot to every reasoning engine.
2. Finish custom wake profiles and slim wake engine without creating a second state machine.
3. Wire real local Brain and Forge adapters with cancellation, provenance, fallback, health, and latency telemetry.
4. Migrate Owner Apps and durable memory/project continuity as readable new source.
5. Add per-turn diagnostic persistence/export.
6. Build SageOS root broker/system-image integration, signed staged update, health check, rollback, then physical VASOUN L10_T05 acceptance.

If a future assistant cannot determine what to do next, inspect issue #21 and the latest CI run instead of asking the owner to retell the project.
