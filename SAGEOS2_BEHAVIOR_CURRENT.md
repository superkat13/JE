# SageOS 2 current behavior map

Date: 2026-09-10
Inspected line: `origin/sageos-2` at `acf3b03bd77d8d97a573ae2f22f875921f75e8ad` (candidate 206 documentation)
Comparison baseline: `SAGE_BEHAVIOR_BASELINE.md`

This map records the audited pre-recovery candidate so the evidence does not move underneath the comparison. Targeted repairs made after the audit are recorded separately in `SAGEOS2_RECOVERY_REPORT.md`; they do not retroactively change these findings.

Statuses describe traced runtime behavior, not test names or architectural intent.

| Baseline behavior | Status | Current runtime path and evidence |
|---|---|---|
| Stable package and in-place data directory | UNCHANGED | `applicationId` remains `com.pineapple.sagecommander.stable`; no uninstall/clear migration exists. |
| Launch into Sage rather than setup/diagnostics | REPLACED | `SageLaunchActivity` opens the new `MainActivity`; the home is Chat-first but is a new UI. |
| Sage presence/conversation first | IMPROVED | Header, state, welcome copy, bubbles, type field, Talk, and Send are above Settings. |
| Old visual layout and full normal controls | REMOVED | Programmatic UI was rewritten. Many old activities and surfaces are absent; surviving settings are regrouped under Memories, Apps, Voice & wake, Appearance, and Advanced. |
| Brain-presence and response-route strip | REPLACED | New high-level status uses `SageChatPresentation`; technical detail moved to diagnostics. There is no equivalent normal-screen route strip. |
| Immediate typed-message acknowledgement | IMPROVED | The coordinator emits `RecordOwnerInput` before Brain/model work, the bubble renders immediately, and queued input shows a sent/queued state. |
| Typed turns remain silent | UNCHANGED | Text responses use `EmitTextResponse`; only voice-origin responses use `Speak`. |
| Busy typed FIFO | IMPROVED | `SageTurnCoordinator` has a 16-entry FIFO and records each queued owner turn immediately. |
| Owner wording reaches the Brain exactly | UNCHANGED | Normalization is limited to deterministic routing; deep requests retain original capitalization, punctuation, paths, names, and code. |
| Readable loading/thinking/failure state | IMPROVED | UI presents Getting ready, Here with you, Listening, On it, Waking up, and Thinking states. Typed Brain failure returns a visible answer and closes back to wake-ready. |
| Brain off the UI thread and lazy loaded | IMPROVED | The runtime loads on its executor and no longer starts the GGUF synchronously at Activity launch. |
| 30/60/30/120 staged Brain watchdog | UNCHANGED | Load, first-token, progress-stall, and absolute bounds remain in current runtime code. |
| Model's embedded chat template used | UNCHANGED | JNI queries `llama_model_chat_template` and applies it, with a plain fallback. |
| Exact inherited GGUF identity known | UNKNOWN | Model is private owner data and unavailable on this host; repository names are not metadata. |
| “Sage” wake → acknowledgement → command listening | REPLACED | Isolated sherpa-onnx KWS supplies a profile acknowledgement (default “Yes”), then the coordinator opens command recognition. Physical behavior is unverified. |
| Wake stays light during Brain work | UNCHANGED | Thinking keeps wake-only mode; another valid wake yields transient “I'm thinking” without changing the active coordinator turn. |
| Wake returns after errors | IMPROVED | Coordinator transitions are explicit and tested; error speech passes through echo guard back to wake-only. Physical process/lifecycle recovery remains unverified. |
| One miss is terminal and speaks at most once | IMPROVED | Candidate 206 clears `followUpAfterSpeech` before one “I didn't catch that”; echo guard then returns to wake-only. |
| Duplicate/stale recognition finals do not duplicate turns | IMPROVED | Turn ID, recognizer generation, state, and mode must all match; dispatch changes generation/mode before callbacks can repeat. |
| TTS completion cannot reopen failed listening | IMPROVED | Recognition failure clears follow-up intent; TTS completion can only enter echo guard, then wake-only. |
| Local Sherpa command ASR primary when old verified model exists | BROKEN | Historical 45 MB model data can survive, but current `AndroidSpeechPort` never checks it. The retained `SageSherpaRecognitionService` is a stub that always emits `ERROR_CLIENT`. |
| Android command ASR fallback | PRESENT BUT INACCESSIBLE/UNKNOWN | Runtime requests on-device Android recognition, then default Android recognition. On a device where Android exposes a working service it can function, but the app also exports its own failing recognition service and physical selection is unverified. |
| Owner-selected TTS voice/rate/pitch | UNCHANGED | Current speech port imports `sage_voice_profile` voice name, rate, and pitch. |
| Arbitrary owner wake phrases | PARTIALLY REGRESSED | Profiles and phrases migrate, but only hard-coded phrases with built-in BPE token strings can run. |
| Wake profiles that execute a saved command | PRESENT BUT INACCESSIBLE | Legacy command data migrates, but current wake dispatch activates mode only; it never executes the profile's command. |
| 1.33.4 Vosk fragment/sound-alike recovery | REPLACED | KWS uses token-level keyword spotting. Only “sage” and “sage glitch” are compiled; 1.33.4 fragment logic is absent. Physical relative accuracy is unknown. |
| Sage-specific Core reaches model | UNCHANGED | `TwinContextRenderer` emits Sage self-model, principles, tone, notes, owner preferences, shared state, memory, history, and current facet. |
| Old owner Core text survives | UNCHANGED | One-time migration puts `sage_core/owner_instructions` into structured Core notes, leaving legacy data untouched. |
| Strong default Core identity copy | PARTIALLY REGRESSED | Current fallback says Sage is the owner's persistent virtual twin, but is less distinctive and less action-oriented than the old “Kat's personal AI and tablet partner” Core. Migrated owner Core helps only existing installs with that field. |
| Owner context/self-model/current wording | UNCHANGED | Current render includes owner model/preferences, Sage self model, current facet, and exact request. |
| Relevant memory and history | IMPROVED | Durable v2 stores up to 500 conversation entries; prompts include up to 64 active memories and 24 recent history items before budget trimming. |
| Explicit continuation carries recent turn | LIKELY UNCHANGED | Recent history is rendered in chronological order, but physical “finish your thought” behavior with the inherited model is unverified. |
| Owner-taught exact replies | UNCHANGED | Legacy learned replies migrate to Sage-subject memory and `SagePersonalCommandEngine` resolves them deterministically before the Brain. |
| Teaching, remember/recall, help, tone, Red Queen local behavior | REPLACED/UNCHANGED | Reimplemented as `SagePersonalCommandEngine`; key flows exist, but wording/physical interaction parity is not proven. |
| Chicken Tonight workflow | UNCHANGED | Exact normalized phrase routes to `chicken_tonight` without Brain generation. |
| Deterministic actions bypass Brain only when executable | BROKEN | Router marks more phrases fast than `FastCommandParser` can parse. Close, timer, alarm, screenshot, settings, turn on/off, and label tap requests can be intercepted then fail instead of reaching Brain. |
| Multi-step structured tool continuation | BROKEN | Runtime tool results are wrapped in `<SAGE_TOOL_RESULT>`, but the request policy recognizes only an unbracketed prefix. The next Brain call can lose its tool contract even though four calls are nominally allowed. |
| App launch, owner-app-first resolution | UNCHANGED | Fast controller resolves stored Owner Apps first and otherwise matches installed launcher labels. |
| Owner App startup procedure runs | PARTIALLY REGRESSED | Procedure text persists and can appear in action prompts, but fast open only launches the package and structured Brain tools cannot perform the procedure. |
| Back/home/recents/notifications/quick settings | PRESENT AND WORKING* | Implemented with accessibility global actions; `*` depends on enabled service and needs physical verification. |
| Scroll/swipe/coordinate tap | PRESENT AND WORKING* | Implemented with node scroll and gestures; `*` requires physical service/window verification. |
| Semantic label tap and numbered overlay | REMOVED | Current accessibility service has no semantic target search, click-by-label, event tracking, or numbered overlay. |
| Volume | PRESENT AND WORKING* | Direct `AudioManager` path exists; physical result unverified. |
| Media controls, timers, alarms, screenshot, settings actions | REMOVED or PRESENT BUT INACCESSIBLE | No matching fast executor exists. Some requests are incorrectly captured by the fast router. |
| Notifications reading/response | PRESENT BUT INACCESSIBLE | Listener component and access probe exist, but no user action path for notification content was found. |
| Owner Apps data | UNCHANGED | One-time migration preserves entries; Apps settings provide the current surface. |
| Persistent owner tasks | UNCHANGED | Tasks migrate and deep turns can create/continue task state. |
| Owner workflows/compiled automations | PARTIALLY REGRESSED | Legacy data is preserved, but only the hard-coded Chicken Tonight runtime path is established. |
| Forge pairing/trust data | UNCHANGED | Migrated/preserved and probed; active Forge exposes health/tools/jobs through structured tool calls. |
| Forge normal-owner controls | PARTIALLY REGRESSED | Runtime tools exist, but several historical browse/pair/workbench flows are no longer normal home controls. |
| File/package/network tools and Workbench | REMOVED/PRESENT BUT INACCESSIBLE | Historical activity implementations were not carried forward. Root/Forge could supply a subset only when active. |
| Root broker readiness | REPLACED | New capability broker probes a SageOS root service and exposes strict structured operations only when active. Physical root is intentionally deferred. |
| Shizuku/Sui authority | REMOVED | This was an explicit SageOS 2 architecture decision; old app-side bridge is absent. |
| Diagnostics and model import | PRESENT AND WORKING* | Advanced contains diagnostics; fast escape-hatch commands share a report/open importer. Model identity metadata is still not exposed. |
| Brain import preserves existing model on failure | IMPROVED | Validation and staging are atomic; failed imports do not intentionally replace the current GGUF. Physical storage/error tests remain needed. |
| Brain remove/test/manager parity | PARTIALLY REGRESSED | Import/health exist; old owner-facing test/remove/manager feature set is not present. |
| Appearance | PARTIALLY REGRESSED | Current appearance setting exists, but old backgrounds/theme/custom media breadth is absent. |
| Custom audio/video replies and Voice Studio/Speech Lab | REMOVED | Legacy data is not shown to be deleted, but the old surfaces/runtime are absent. |
| Creative Studio, Surprise Me, Adobe routes | REMOVED | No equivalent current activity/routing implementation was found. |
| Package/File/Network labs | REMOVED | Old activities and deterministic command routes are absent. |
| Autonomy/self-repair/Company Orders | PARTIALLY REGRESSED | A legacy active job migrates to a task; the former dedicated operational surfaces and heartbeat/executor workflow are absent. |
| Normal owner UI hides engineering detail | IMPROVED | Technical surfaces are under Advanced/Diagnostics; the main page is conversational. |
| Full physical stability on 8 GB VASOUN | UNKNOWN | Candidate 206 has automated evidence only. Cold GGUF Chat, warm turn, voice loop, memory migration, and sustained stability still require owner-device acceptance. |

## Current text path

`MainActivity.submitChatInput` → runtime `submitText` → `SageTurnCoordinator.TextSubmitted` → immediate `RecordOwnerInput`/history render → personal/workflow/fast/deep route → lazy Brain executor → `TwinContextRenderer` + prompt budget → JNI llama.cpp generation → output cleanup/tool loop → assistant history record → text bubble → wake-ready.

The visible owner wording is preserved. The input is cleared before dispatch and restored only on synchronous submission failure. Model load/generation are not performed on the main thread.

## Current voice path

isolated `SageWakeRemoteService`/Sherpa KWS → `WakeDetected` with profile and acknowledgement → TTS acknowledgement → Android command recognizer → final/error with turn and generation → same router → response → TTS → echo guard → one follow-up or wake-ready.

The coordinator closes the recursive miss path. The weak link is actual command recognition: preserved historical local ASR is disconnected and its component is a hard-error stub.

## Current prompt order

1. Sage twin identity and self model.
2. Owner model/preferences.
3. Shared projects/tasks and Owner Apps.
4. Active personality facet.
5. Active memory.
6. Recent conversation history.
7. Operational tool detail for action turns only.
8. Current exact owner wording in the user message.

There is no generic “helpful assistant” system prompt in the primary current path. The default Core is Sage-specific but less vivid than the historical default. Head/tail prompt trimming can omit middle sections under pressure; this requires an integration test with a large Core/memory set.
