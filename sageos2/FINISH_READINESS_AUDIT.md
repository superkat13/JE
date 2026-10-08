# Sage finish-readiness audit — 26 September 2026 (Chicago)

**Release decision: HOLD.** Build 212 is installed and failed physical wake
acceptance. Passing tests are evidence for specific paths, not permission to call
Sage finished. Do not offer another APK merely because compilation succeeds.

## Evidence and continuity

Reviewed current implementation, the historical behavior baseline, current-behavior
map, recovery matrix/report and physical-acceptance document, prior approved A1
requirements, and the owner's 212 report. September 10 status tables refer to
candidate 206 and must not be treated as today's implementation inventory.

Preserve package, signing lineage, existing private GGUF, Core, history, memory,
Owner Apps and owner decisions. No uninstall, data clearing, fabricated Core,
replacement personality, or automatic replay of imported side effects. Root is
not required for the A1 usable-agent milestone. Root/system-image and autonomous
self-authoring/installing remain separate unfinished commitments.

## Requirement accounting

| Requirement | Current evidence / remaining work |
| --- | --- |
| Same installed identity and in-place update | 212 report confirms stable package, profile and 25 Owner Apps. Existing signer lineage verified for 212. Private history/model digest comparison is not available from this report. |
| Text-only chat, immediate acknowledgement and FIFO | Coordinator/runtime tests cover recording before Brain work, typed silence, stale responses and queued turns. Conversational quality and latency still fail owner expectations. |
| Wake detection | Exact 212 packaged mobile ONNX export reproduces a native reshape failure. Same-model standard export passes real silence/reset/recorded-phrase inference. PR 34 CI passed. Android JNI, microphone and background acceptance remain pending. |
| Wake while thinking | Coordinator implements transient acknowledgement without replacing the turn; mocked tests cover it. Cannot physically pass while wake process dies. |
| Command speech, echo guard and terminal miss | Local model reuse, prewarm, immutable callback generations and one-miss transition exist. 212 logs show valid finals and a NO_MATCH; the old chars=0 message was a misleading literal. Capture accuracy and acoustics still need physical evidence. |
| Usable Brain replies | 212 completed inference, but 424 prompt tokens took ~64 s and 24 output tokens ~8 s. Native 24-token ceiling contradicted 40/48 requests. Repair honours up to 48 and fixes token lifetime and partial-error handling. New native tests are required before any success claim. |
| Faster warm turns without losing identity | Exact prompt-prefix reuse implemented; always replay final token, remove old generated suffix, and invalidate on failure/cancel/unload. Recurrent/hybrid/diffusion models keep full prefill. Must prove cold/cached equivalence using real llama.cpp. No tablet speed claim yet; cold prefill remains unresolved. |
| Authentic Core continuity | Device reports revision 0 and no current legacy Core source. Importer preserves existing state and validates before writes. July prototype is not a full tablet backup. Owner-reviewed authentic source or clearly reviewed reconstruction is still required. |
| Portable memories/history and facets | Shared stores and migration paths exist; Everyday/Red Queen use one Core. Host tests cover prompt representation and continuity. Full owner-private inventory cannot be inferred from migration counts. |
| Owner instruction page | Advanced identity/Core editor, export and reviewed continuity import exist. Empty stored Core is not silently seeded. |
| Owner Apps and app choice | Registry, aliases, migration and launch resolution exist; 25 entries survive physically. Stored startup procedures appear in action context but are not a reliable independent multi-step executor. This remains a gap. |
| Device controls | Parser/controller support navigation, semantic tap, gestures, volume, alarms/timers/settings, screenshots and notification display. Owner reported screenshot, Settings tap and alarm success on 210. Re-check changed paths only; broader device variants not proven. |
| Forge jobs | Paired capability transport, job start/status/cancel, protocol tests exist. ACTIVE means paired/configured capability, not proof that every requested job succeeds end-to-end. |
| Durable work and restart recovery | Checkpoints, interrupted-turn recovery and no automatic side-effect replay exist with tests. Old imported job remains WAITING, not evidence of a running autonomous worker. |
| Self-care | Bounded wake retries and persisted reasons exist. Repair keeps owner-cancelled tasks cancelled while leaving health findings visible. Self-care is not autonomous arbitrary code repair. |
| Owner-selected voice and appearance | Voice profile/rate/pitch and appearance storage exist. Natural British voice quality depends on installed TTS. Historical custom media, Voice Studio and broader appearance controls have not all been restored. |
| Arbitrary wake phrases / saved wake commands | Only compiled supported phrases are runnable. General BPE phrase compilation and saved-command execution remain incomplete; do not silently discard migrated data. |
| Historical custom workflows / Workbench / Creative / File / Package / Network labs | Some data and root/Forge tool contracts survive; equivalent owner-facing operation is not established. These remain inventoried gaps, not completed features. |
| Chicken Tonight | Scope validation and task initialization exist. Registry marks a scoped task active but does not itself execute the evidence modules. Device scope is NOT_CONFIGURED. Do not claim a completed assessment engine. |
| Own updates / self-extension | No complete arbitrary author-build-test-sign-install-rollback loop. This is an explicit unfinished goal. Do not equate retries with this capability. |
| Root / platform SageOS | Device owner, root broker and platform privileges unavailable in report. Locked production firmware requires verified device-specific recovery/promotion work; not solved by this APK. |
| Background and reboot survival | 210 reopen/reboot was owner-reported successful. Native wake, later background life, thermal/RAM and long-duration endurance remain pending on actual VASOUN hardware. |

## Verification that must precede another offer

1. Real native KWS inference, including recorded positive phrase, using the assets
   prepared by the same pinned dependency script used by the APK.
2. Production Brain C++ executed against the pinned llama.cpp and checksum-pinned
   tiny test GGUF under AddressSanitizer. Test requested budget, exact-prefix reuse,
   changed system/user context, chunked prefill, oversized input, injected decode
   error, cancellation, recovery and unload. Host string adapter is not Android
   JNI validation; tiny model is never installed or packaged for the owner.
3. Full Kotlin/Android service regressions, release lint, native Android compilation,
   package/ABI checks and all existing historical recovery gates.
4. Before a distributable: signed-release tests and two independent payload builds,
   signer lineage/version verification and downloaded artifact checksum.
5. Physical acceptance remains necessary for the inherited model, microphone,
   wake-during-thinking, background behavior and continuity. Host tests cannot
   truthfully certify these. No blind reinstall is a substitute for evidence.

## Outstanding dependencies

The installed private Brain's exact metadata is not present in the diagnostic.
The existing read-only Brain inspection screen can expose it; no model replacement
is needed. First-turn latency cannot be considered solved merely by caching warm
prefixes. Core requires authentic content, not code that invents it. The larger
self-maintaining finish line includes workflow execution and controlled updates;
this audit keeps those obligations visible rather than silently narrowing the goal.

## Audit repairs and native evidence

The broader audit also found two unfixed migration issues: source-presence and
completion-marker reads could throw before migration's error handling; paragraph
merging could duplicate legacy Owner App purpose/startup text on every launch.
The repair catches and reports malformed source types independently, lets other
valid imports proceed, preserves the malformed source for recovery, and makes
exact-block merging idempotent. API 28/33 integration tests cover these cases.

Native Brain CI run 36295007456 passed on commit
`ae9209c7bf248a6055b3b0a220030650bdf91a3d`: real 48-token generation, cold/cached
output equivalence, changed system/user context, chunked prefill, oversized input,
injected decode failure, cancellation, recovery and unload under AddressSanitizer.
The fixture reused 29/30 prompt tokens (14 ms cold prefill, 2 ms warm); these are
small host-fixture measurements, NOT predicted VASOUN latency. Full integrated
results including the migration tests must be recorded after their CI completes.

## Candidate 213 owner-core prompt-budget evidence — 27 September 2026

Candidate 213 baseline repairs were integrated to `sageos-2` before this follow-up.
A separate test-only proof then reproduced an identity-context defect in the
ordinary runtime path: `TwinContextRenderer` emitted generic OWNER CORE guidance
before the imported owner-authored instructions, while `BrainPromptBudget`
head-clipped the OWNER CORE section inside the ordinary 1,600-character working
budget.

Test-only PR #35, CI run 177, executed the full verification path. The native Brain
gate passed. Android/JVM verification reached `testDebugUnitTest`, ran 210 tests,
and failed exactly one new regression:
`TwinContextRendererTest.ordinaryRuntimeBudgetPreservesMeaningfulImportedOwnerCoreBeforeRendererBoilerplate`.
There were no test errors and the failure was the intended OWNER CORE assertion,
not compilation, environment, or unrelated existing-test failure.

Repair PR #36 changed production behavior only by emitting the imported OWNER CORE
text before the generic renderer guidance and retained the regression test. CI run
178 then passed the full path: 210 tests with 0 failures/errors/skips,
`lintRelease`, `assembleDebug`, historical A1/recovery gates, arm64 Brain/wake
packaging checks, and the real native Brain inference gate. The repair was squash
merged as commit `6d738a845be1249ca4e6b6f117e5ccb37f841470`.

This closes the reproduced prompt-ordering defect. It does **not** create or invent
missing owner Core content. Authentic Core remains unresolved while the physical
device reports revision 0. It also does not prove VASOUN microphone/background wake
acceptance or solve the measured cold first-token latency. Those remain release
dependencies; no new APK should be offered solely because this code-level defect is
now repaired.
