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

## Current checkpoint — 2026-09-08

- Candidate 203 source commit `ed2174ce0338b27f9eb337fb8d7ac0609ee03d40` passed ordinary run `34086719218` and signed run `34086719228`. Its signed APK SHA-256 was `a29cfaa23fd88b3e9e100cea89eea727e213a2f7a60408586388208ef13ae58d`.
- Candidate 203 installed over the existing package and stayed open after native wake isolation, so package/signing continuity and the `:wake` process repair survived that checkpoint.
- Candidate 203 failed product acceptance: the owner reported that Sage/chat did not work, and Android sharing did not let the owner send the diagnostic report. No exact device trace was recovered, so do not invent one.
- Comparison with the inherited working Sage 1.33.3 Brain found a concrete tablet regression risk: candidate 203 expanded native context from 2,048 to 4,096 tokens, output from a proven adaptive 16–24 tokens to 192, and prompt context from 3,600–4,800 formatted characters to 9,000. At the previously observed mobile generation rate, 192 tokens can consume almost the entire 180-second watchdog.
- Candidate 204 source commit `788cd9d4a5225a8c941f0117b52e92a93a9d75cd` passed ordinary run `34139696044` and signed run `34139696093`, including release lint and two-build reproducibility. Its independently downloaded APK SHA-256 was `2974afa733f9c86890477b22878758ab5c880d4fe56bdf71ef5bd3a4288cf4e1`.
- Candidate 204 remains **withheld** and was not handed to the owner. Automated correctness did not resolve the clarified product blocker: Sage still was not obvious to use, normal Settings still exposed Core/workflows/scopes/capabilities/diagnostics, ordinary conversations still received an engineering tool contract, queued sends were not immediately visible, and familiar taught phrases were stranded.
- Candidate 205 is the next in-place SageOS 2 repair counter, not a new Sage version. It keeps Chat as home, adds persistent plain-language help, immediately displays accepted/queued messages, restores legacy taught phrases in place, adds owner-friendly memory controls, separates ordinary twin conversation from operational tool prompts, and nests all engineering machinery under Settings → Advanced.
- Candidate 205 source commit `b10607a506e637651027ec3d9f8d107887b96a11` passed 102 focused product/path tests, ordinary run `34228707869`, and signed run `34228707889`, including release lint, two independent builds, payload comparison, signing-lineage verification, and package/version/native-payload checks.
- The signed artifact `10057183257` was independently downloaded and inspected. Archive SHA-256 is `4f0073246cda2f681e6fc8de5389077e3b7398e07b22fe10579806ab81fbbf21`; APK SHA-256 is `9e2f30be52f1a1a15fd4a422b57695a8c727c212879c910002c2820199b5261e` (41,687,363 bytes).
- Candidate 205 failed physical voice acceptance. After a recognition miss, Sage repeatedly spoke `I didn't catch that.` without stopping. The exact state-machine cause was `onRecognitionFailed` reopening `FOLLOW_UP_LISTENING` after its error speech, so silence recursively created another recognition miss.
- Candidate 206 is the next in-place repair counter in the same SageOS 2 lineage. A recognition miss is terminal for that voice turn: Sage speaks the error at most once, returns to wake-ready after echo guard, and rejects stale duplicate callbacks. A regression test reproduces the failed path before another tablet build is considered.
- Candidate 206 source commit `e133e032df42ec6d4e3dac1f976441ef155f2964` passed ordinary run `34281038636` and signed run `34281038596`, including the full unit suite, release lint, two independent builds, payload comparison, signing-lineage verification, and package/version/native-payload checks.
- Signed artifact `10077833315` was independently downloaded and inspected. Archive SHA-256 is `0ee58dab29c760d866297f8d0928f44fdd7cb19a551e1a34c82f2bf37e7b359d`; APK SHA-256 is `3ce9966a6d6f3364692d955dddf6af28909b7e082774696afe2eed6e34beb71b` (41,687,363 bytes).
- Candidate 206 has automated release integrity only. It remains withheld until the owner-visible Chat and inherited private-GGUF text path have a meaningful acceptance pass; compilation does not establish physical behavior.
- **Do not give the owner a debug APK. Do not hand over candidate 203 or 205 again.**

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

## Architecture established; candidate 206 automated verification passed

- Clean Kotlin Android project under `sageos2/`.
- Application ID remains `com.pineapple.sagecommander.stable` for in-place migration.
- Current SageOS 2 versionCode is `206`, versionName `2.0.0`, targetSdk 35, arm64 only. This remains the same SageOS 2 lineage; versionCode is only Android's in-place update counter.
- Single `SageTurnCoordinator` owns turn/listening state.
- Listening modes are OFF, WAKE_ONLY, COMMAND, FOLLOW_UP.
- Typed messages queue while a turn is busy; every accepted message is written to Chat immediately and visibly confirmed as sent.
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
- Candidate 206 retains the inherited 2,048-token native context and tablet request profiles: exact checks use 4–12 output tokens, ordinary answers 16, action selection 20, and contextual conversation 24.
- Ordinary twin conversation receives Sage's identity, memories, and relevant history without the engineering tool contract or task machinery. Operational prompts receive those details only when an action is actually being reasoned about.
- Chat immediately distinguishes getting ready, gathering relevant context, and writing; native token progress drives first-token/stall watchdogs underneath that human language.
- Qwen `<think>` content and model-control tokens are removed before any response reaches conversation history or the visible Chat surface.
- Brain provider fallback retains failure provenance.
- Forge trust/job transport is preserved through the existing encrypted pairing identity; pairing/revoke are not exposed as Brain tools.
- Structured Brain tool calls are exact, bounded, and routed through one capability broker.
- Root transport source exists as a small authenticated `sage_rootd` service with init/SELinux policy and an app-side socket client.
- Root capability is **not active on a normal APK install**; it requires the SageOS platform/system-image path.
- Persistent task checkpoints and safe reboot/crash recovery exist; recovery never blindly replays the last side effect.
- Chat is the Sage home experience, with persistent plain-language help and tappable examples. Normal Settings contains only What Sage remembers, Apps Sage knows, Voice & wake, Appearance, and one Advanced doorway. Sage Core, Local Brain/capability health, Tasks, Diagnostics, and Workflows/scopes are nested one level deeper under Settings → Advanced.
- Sage 1.33.3 `sage_state/phrase_aliases` remains the live learned-phrase store, so existing lessons work immediately without copying, renaming, or deleting the legacy data. Chat once again supports `remember that…`, two-step teaching, one-line `when I say… it means…`, learned commands, and exact owner-taught personality replies without a GGUF wait.
- Sage 1.33.3 appearance mode, saved background URI/intensity, and owner language preference remain live in their original `sage_state` keys. SageOS 2 reads them in place, exposes Appearance and language in ordinary Settings, and honors the selected tone in local Brain conversation.
- Chicken Tonight uses an exact silent trigger and requires a stored usable scope before becoming active.
- Privacy-conscious diagnostic report generation exists and omits conversation contents, Sage Core contents, owner-app details, and Chicken Tonight authorization details by default. Diagnostics has direct Copy and Share buttons that bypass the Brain; sharing failure falls back to the clipboard.
- Legacy Android authority component names are preserved where needed for signed in-place migration from Sage 1.x.

## Local Brain model provisioning

- SageOS 2 does **not** bundle a multi-gigabyte GGUF in the APK.
- Preferred in-place migration path: preserve the existing app-private file `files/brain/sage-brain.gguf` from the currently installed Sage package.
- Fallback path: deterministic command `import brain model` opens Sage's private model importer.
- Importer validates the GGUF magic header, copies on a worker thread, computes SHA-256, atomically replaces `files/brain/sage-brain.gguf`, stores metadata, and checks live Brain health.
- A failed native model load can be retried on the next message without restarting Sage; replacing an already loaded model still requires a process restart before the new file is mapped.

## Signing lineage that must remain unchanged

Known certificate SHA-256 fingerprints already proven by the 1.33.3 workflow:

- Legacy signer: `99e0a7c655cdefb3bb4ac85e5961d19358ee0ffdb3dce9b3a145f9cbcda78d35`
- 2026 release signer: `e2e3e2cabd3372d6073643b35dc94b5fb62e32c200f9e236d4b9f1e403f61b6e`
- Rotation minimum SDK: 33

The signed candidate must use the existing GitHub signing secrets and the same `apksigner rotate` lineage strategy proven by Sage 1.33.3. Never create a replacement signing identity just to make a build easier.

## Physical tablet acceptance — next owner-visible gate

Physical acceptance means proving Sage on the VASOUN L10_T05 hardware after the signed candidate passes CI. It is not a claim that compilation equals success.

Candidate 203 was the first install and failed the Chat/product gate. Candidate 205 then failed physical voice acceptance with a recursive recognition-error speech loop. Candidate 206 is the same-lineage repair and must pass every automated release gate before it can become the next meaningful physical candidate. Preserve app data through the same package and signing lineage.

Initial acceptance order:

1. Install/update succeeds without uninstalling existing Sage.
2. Sage launches directly into Chat, with engineering controls only under Settings/Advanced.
3. The first typed message immediately appears and shows human progress while the inherited model loads.
4. Existing `files/brain/sage-brain.gguf` is reused if present; otherwise use the Advanced model importer once.
5. Advanced → Run local Brain self-check returns exactly `Brain online.` and Diagnostics records real native prompt/token/timing evidence.
6. Text chat produces repeated cleaned Sage responses and returns to an idle, sendable state after both success and failure.
7. Push-to-talk recognizes a command and returns correctly; a recognition miss speaks at most once and returns to wake-ready without reopening a follow-up microphone.
8. `Sage` -> spoken `Yes` -> command works repeatedly.
9. Saying `Sage` while a deep turn is active produces the lightweight thinking acknowledgement without cancelling the turn.
10. `sage glitch` activates Red Queen as a mode of the same Sage.
11. Accessibility-backed deterministic app/navigation actions work after the Android grant is confirmed active.
12. Close/reopen and reboot preserve Core/history/memory/task continuity; interrupted work becomes recoverable rather than replaying side effects.
13. Direct Copy/Share diagnostic report works even if the Brain is unhealthy or a turn is stuck.
14. Chicken Tonight trigger stays silent and only activates when its stored scope is usable.
15. Background/wake survival is checked over normal real use, not only the first minute after launch.
16. Root remains reported unavailable until the SageOS system image/root broker is actually installed and authenticated.

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

## Next gates for candidate 206

1. **Passed:** the recognition-loop regression and full existing unit/product/path suite in ordinary and release CI.
2. **Passed:** ordinary CI and the full double-build/release-lint/signature/identity workflow.
3. **Passed:** independent signed-artifact download, archive/APK checksum match, ZIP integrity, and arm64 native-payload inspection.
4. Perform the focused Chat/local-Brain and voice physical acceptance pass using the exact self-check and direct diagnostic-copy escape hatch first.
5. Repair only failures actually observed on hardware and rerun the same integrated gates.
6. Continue the SageOS root-broker/system-image path using the already-written daemon/init/SELinux sources; do not substitute Shizuku or raw unrestricted model root.
7. Build device-specific staged OS update/rollback only after the VASOUN boot/recovery/partition facts are verified from the actual hardware.
8. Final SageOS 2 acceptance requires both normal virtual-twin operation and the intended authenticated root-broker path on the real tablet.

If a future assistant cannot determine what to do next, inspect issue #21, this file, branch HEAD, and the latest CI runs instead of asking the owner to retell the project.
