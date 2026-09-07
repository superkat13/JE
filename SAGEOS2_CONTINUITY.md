# SageOS 2 continuity handoff

This file exists so the project can survive a lost conversation, model change, or long gap without asking the owner to reconstruct history.

## Resume here

- Repository: `superkat13/JE`
- Active branch: `sageos-2`
- Master workboard: GitHub issue #21, `SageOS 2 master build`
- Product target: **one Sage, one SageOS 2.0 lineage**
- Read `SAGEOS2_MASTER_PLAN.md` before changing architecture.
- Read branch HEAD and the latest `Verify SageOS 2` workflow run before writing new code.
- Never create a version-per-fix branch or APK family. Use commits and CI checkpoints on `sageos-2`.
- Do not ask the owner to reconstruct the history below. Treat this file plus issue #21 plus current CI as the source of truth.

## Current checkpoint — 2026-09-06/07

- Last fully verified ordinary SageOS 2 build before signing work: commit `a0b04d63b3c5d86b17c348a9f3d0a872add0b4d8`.
- `Verify SageOS 2` run 84 completed successfully through tests, native Brain build, APK assembly, arm64-only packaging, private GGUF provisioning checks, and artifact upload.
- Current branch includes a signed-candidate workflow at `.github/workflows/build-sageos2-signed-candidate.yml`.
- Signed-candidate source commit: `cc47898066ea69b75bc9bda5422755a1938a5f51`.
- Signed-candidate workflow run 1: GitHub Actions run `34072031805`.
- The signed workflow must not be treated as passed until it independently builds release twice, compares unsigned payloads, recreates the Android 13 signing lineage, verifies the known release certificate, checks package/version/native payload, and uploads `SageOS-2.0-signed-candidate`.
- **Do not give the owner a debug APK.** Only hand over the signed candidate after the signing workflow is green.

## Hard owner decisions

- **Sage is the owner's virtual twin.** Do not redesign her as a generic assistant or as a developer-authored personality.
- Sage should learn and use the owner's intent, preferences, habits, vocabulary, working style, trusted tools, recurring choices, project continuity, and accumulated experience as durable twin context.
- Sage remains a distinct software identity, not a literal clone. Twin means shared context and aligned operation, not blind parroting.
- Sage is not governed by hidden behavioral restrictions injected by the application architecture.
- Sage's own identity, principles, preferences, judgment, and self-restrictions belong in visible/revisioned Sage Core.
- Provider/model/tool limitations must retain provenance. Do not silently turn an external refusal into `Sage refuses` or `Sage can't`.
- Final SageOS 2 has real root-backed capability through its own SageOS root broker.
- **No Shizuku.** CI rejects its return.
- Do not make the owner repeatedly install test APKs. Automated gates come first; physical tablet testing happens only at meaningful integration gates.
- Voice and text are equal inputs to the same Sage runtime and same twin context.
- `Sage` -> `Yes` -> command.
- If `Sage` is spoken while Sage is thinking, acknowledge immediately without cancelling the active thought.
- `Do you feel like chicken tonight?` launches the named workflow silently.
- One assistant identity. Red Queen and other modes are facets of Sage, not separate assistants.
- iPhone integration is an external future bridge and must not block SageOS 2.

## Architecture already established and verified

- Clean Kotlin Android project under `sageos2/`.
- Application ID remains `com.pineapple.sagecommander.stable` for in-place migration.
- Current SageOS 2 versionCode is `200`, versionName `2.0.0`, targetSdk 35, arm64 only.
- Single `SageTurnCoordinator` owns turn/listening state.
- Listening modes are OFF, WAKE_ONLY, COMMAND, FOLLOW_UP.
- Typed messages queue while a turn is busy.
- Stale callbacks are rejected by turn/generation identity.
- Fast device commands are separated from deep Brain requests.
- Android VoiceInteractionService/SessionService architecture exists with heavy work outside the always-running interactor process.
- Android command recognition and TTS feed the same runtime coordinator and recover from recognizer errors.
- Deterministic Android fast actions exist for app launch/navigation/notifications/quick settings/scroll/swipe/tap/volume.
- Deterministic escape hatches exist for `share diagnostic report` and `import brain model`, so those functions do not depend on a healthy LLM.
- Sage Core is readable/revisioned and is supplied to deep Brain requests.
- Twin model data structures separate owner model, Sage self model, and shared continuity.
- Voice and text share durable conversation history.
- Twin memories carry subject/source/confidence/timestamps/active state.
- Owner Apps support owner aliases and purposes.
- Custom wake profiles are modes of the same Sage; defaults include Sage and Sage Glitch / Red Queen.
- Offline wake uses pinned sherpa-onnx KWS with verified model dependencies and an arm64-only runtime.
- Native local Brain compiles from verified donor C++ source against pinned llama.cpp commit `d73c1d6b22a2d3ecc74c2c9cde354015ee72e862`.
- Local Brain receives twin context as system prompt and the current owner request as user prompt.
- Brain provider fallback retains failure provenance.
- Forge trust/job transport is preserved through the existing encrypted pairing identity; pairing/revoke are not exposed as Brain tools.
- Structured Brain tool calls are exact, bounded, and routed through one capability broker.
- Root transport source exists as a small authenticated `sage_rootd` service with init/SELinux policy and an app-side socket client.
- Root capability is **not active on a normal APK install**; it requires the SageOS platform/system-image path.
- Persistent task checkpoints and safe reboot/crash recovery exist; recovery never blindly replays the last side effect.
- The owner cockpit now includes Chat, Sage Core, Health, Tasks, Diagnostics, Owner Apps, Modes, and Workflows.
- Chicken Tonight uses an exact silent trigger and requires a stored usable scope before becoming active.
- Privacy-conscious diagnostic report generation exists and omits conversation contents, Sage Core contents, owner-app details, and Chicken Tonight authorization details by default.
- Legacy Android authority component names are preserved where needed for signed in-place migration from Sage 1.x.

## Local Brain model provisioning

- SageOS 2 does **not** bundle a multi-gigabyte GGUF in the APK.
- Preferred in-place migration path: preserve the existing app-private file `files/brain/sage-brain.gguf` from the currently installed Sage package.
- Fallback path: deterministic command `import brain model` opens Sage's private model importer.
- Importer validates the GGUF magic header, copies on a worker thread, computes SHA-256, atomically replaces `files/brain/sage-brain.gguf`, stores metadata, and checks live Brain health.
- If an existing corrupt model has already caused a one-time native load failure, restart Sage after replacing the model instead of trying to hot-swap an already-failed native engine.

## Signing lineage that must remain unchanged

Known certificate SHA-256 fingerprints already proven by the 1.33.3 workflow:

- Legacy signer: `99e0a7c655cdefb3bb4ac85e5961d19358ee0ffdb3dce9b3a145f9cbcda78d35`
- 2026 release signer: `e2e3e2cabd3372d6073643b35dc94b5fb62e32c200f9e236d4b9f1e403f61b6e`
- Rotation minimum SDK: 33

The signed candidate must use the existing GitHub signing secrets and the same `apksigner rotate` lineage strategy proven by Sage 1.33.3. Never create a replacement signing identity just to make a build easier.

## Physical tablet acceptance — next owner-visible gate

Physical acceptance means proving Sage on the VASOUN L10_T05 hardware after the signed candidate passes CI. It is not a claim that compilation equals success.

First install should be one meaningful signed in-place candidate, not a debug build carousel. Preserve app data if Android accepts the signing lineage.

Initial acceptance order:

1. Install/update succeeds without uninstalling existing Sage.
2. Sage launches into the SageOS 2 cockpit.
3. Health page reports wake runtime and native library state truthfully.
4. Existing `files/brain/sage-brain.gguf` is reused if present; otherwise use `import brain model` once.
5. Text chat produces repeated local-Brain responses.
6. Push-to-talk recognizes a command and returns correctly.
7. `Sage` -> spoken `Yes` -> command works repeatedly.
8. Saying `Sage` while a deep turn is active produces the lightweight thinking acknowledgement without cancelling the turn.
9. `sage glitch` activates Red Queen as a mode of the same Sage.
10. Accessibility-backed deterministic app/navigation actions work after the Android grant is confirmed active.
11. Close/reopen and reboot preserve Core/history/memory/task continuity; interrupted work becomes recoverable rather than replaying side effects.
12. `share diagnostic report` works even if the Brain is unhealthy.
13. Chicken Tonight trigger stays silent and only activates when its stored scope is usable.
14. Background/wake survival is checked over normal real use, not only the first minute after launch.
15. Root remains reported unavailable until the SageOS system image/root broker is actually installed and authenticated.

If anything fails, capture the built-in diagnostic report first. Repair the **same `sageos-2` project**, rerun CI, and produce the next meaningful signed candidate only after the failure is understood.

## Reliability rules

- Do not call a feature complete because it compiled.
- Every state-machine fix gets a regression test.
- No hidden behavioral denylist in SageOS services, capability code, root broker, routers, or UI.
- No canned developer persona should override the twin model.
- Technical validation is allowed and required: caller identity, malformed arguments, OS invariants, transport integrity, execution errors.
- Provider limitations stay attributed to the provider.
- Deep Brain latency must never block deterministic device commands.
- CI fails if Shizuku references return to SageOS 2.
- AVAILABLE is not ACTIVE. Never claim root, Device Owner, Assistant role, or another authority is active until the physical device proves it.

## Next build gates after signed-candidate verification

1. Signed in-place candidate must pass the new double-build/signature/identity workflow.
2. Perform the first physical tablet acceptance pass using the built-in Health + Diagnostics surfaces.
3. Repair only failures actually observed on hardware and rerun the same integrated gates.
4. Continue the SageOS root-broker/system-image path using the already-written daemon/init/SELinux sources; do not substitute Shizuku or raw unrestricted model root.
5. Build device-specific staged OS update/rollback only after the VASOUN boot/recovery/partition facts are verified from the actual hardware.
6. Final SageOS 2 acceptance requires both normal virtual-twin operation and the intended authenticated root-broker path on the real tablet.

If a future assistant cannot determine what to do next, inspect issue #21, this file, branch HEAD, and the latest CI runs instead of asking the owner to retell the project.
