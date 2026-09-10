# Sage behavior baseline before SageOS 2

Date: 2026-09-10
Baseline lineage: stable package `com.pineapple.sagecommander.stable`
Primary reference: reconstructed Sage 1.33.4 (version 50), branch `repair/normal-tablet-v1-33-4`, commit `1dc79bd250d4e7565c9b610419896a8eb13190e8`
Physical evidence: installed Sage 1.33.3 (version 49), recorded in `sage_evidence/v1_33_3_physical_report_2026-09-03.md`

This is a behavioral reference, not a proposal to merge the Java application wholesale. “Confirmed in source” means the exact reconstruction and its gates contain the behavior. “Confirmed physically” means the owner evidence recorded it on the VASOUN tablet. Code paths without physical evidence are not described as physically proven.

## Confirmed historical behavior

### 1. Launch and first impression

- Launch opened `com.pineapple.sage.MainActivity` in the one stable package.
- The main screen identified itself as **Sage Commander** and put Sage's presence, conversation, “Talk to Sage,” and typed message entry ahead of secondary controls.
- A Brain-presence strip and response-route status made availability visible without forcing the owner into diagnostics.
- Secondary controls were grouped under Voice & Wake, Brain & Memory, Create & Discover, Tools & Settings, and Diagnostics & Repair. Red Queen retained a visible, owner-authenticated doorway.
- The screen was programmatic Android UI, not Compose.

### 2. Text conversation

- The owner could type in “Message Sage — typing works without microphone” and submit with “Ask Sage to do it.”
- Typed input entered the same conversation coordinator as voice but was marked text-only, never spoken by TTS, and could not be rejected as speaker echo.
- Busy typed messages entered a bounded FIFO queue rather than overwriting the active response. Arrival order was preserved.
- Owner evidence confirms typed commands reached the local tablet Brain and completed, including requests lasting longer than 30 seconds.
- The visible transcript was bounded for display. Brain continuity used recent turns in memory; durable full transcript persistence was not established by the inspected physical evidence.

### 3. Wake and voice conversation

- A Vosk wake recognizer listened for built-in and owner profile phrases. Later repairs handled observed fragments such as “say page” and a short “okay” + Sage-like suffix sequence, while blocking unsafe one-word sound-alike aliases.
- On wake, Sage vibrated/acknowledged and said **“Yes?”**, then transferred the microphone to command recognition.
- A verified local sherpa-onnx streaming recognizer was primary when its pinned 45,202,074-byte English model pack was installed; Android SpeechRecognizer was the bounded fallback.
- Recognition had generation/turn guards, alternate-candidate recovery, self-echo rejection, one bounded busy/network retry, and an explicit microphone handoff.
- A successful spoken answer could open one 12-second follow-up listening window. Silence or a recognition miss closed the turn to wake-ready without speaking a repeated failure prompt.
- While the Brain was already working, another wake produced the lightweight acknowledgement **“I'm thinking.”** without cancelling or duplicating the active request.
- Status text exposed getting-ready, listening, hearing, and thinking states.

### 4. Personality, Core, and identity

- Sage Core began with a Sage-specific identity: Kat's personal AI and tablet partner, proactive, persistent, truthful about actions, direct, capable, warm, and cheeky.
- Owner-edited Core instructions were stored in private preferences, revisioned, capped on input, and included in the Brain system context.
- Owner tone, relevant memory, and selected recent turns were included. The request policy selected context by relevance and recognized explicit continuation wording in 1.33.4.
- Exact owner-taught replies and persistent Easter-egg/personality replies could bypass the Brain. Red Queen was a persistent alternate Sage personality boundary rather than a separate product.
- The Brain prompt used the model's embedded llama.cpp chat template when available and added `/no_think` for the short tablet response path.
- Response policy differentiated exact, concise, action, and conversational requests and instructed complete conversational sentences.

### 5. Timing and failure behavior

- Brain loading and generation were off the UI thread.
- Watchdogs were staged: 30 seconds to load, 60 seconds to first token, 30 seconds without progress, and 120 seconds absolute.
- Live native token progress prevented a fixed 30-second timeout from killing a healthy slow response.
- Physical evidence confirms a local Brain request could exceed 30 seconds and still finish.
- Typed failure did not need a microphone; wake/command microphone failures had bounded fallback behavior.

### 6. Routing and device behavior

- Deterministic actions bypassed the LLM when they had known implementations. Conversation and ambiguous requests used the local Brain.
- Historical deterministic capabilities included app launch/close, back/home/recents, notification shade and settings handoffs, volume/media controls, scrolling/swiping, coordinate and semantic-label tapping, numbered overlays, remembered app targets, arithmetic, memory/teaching, and explicit diagnostics.
- Accessibility supported global actions, recursive node lookup, semantic targets, numbered overlays, and gestures.
- Owner Apps stored display name, package/APK target, aliases, and startup procedure. Owner-first resolution occurred before generic installed-app matching.
- Workflows included the exact silent “Do you feel like chicken tonight?” route plus owner-approved compiled automations and persistent task sessions.
- Forge pairing, tool discovery, job dispatch/result return, diagnostics, package/file/network tools, Workbench, Creative Studio, Surprise Me, and autonomy/self-repair surfaces existed behind Sage's one-entity UI.
- Root/system readiness was inspected truthfully; Shizuku/device-admin and consequence confirmations remained authority mechanisms, not personality instructions.

### 7. Owner-visible controls

- Normal use exposed conversation, Talk, type-to-Sage, Brain presence, response route, appearance, Sage Core, Owner Apps, memory, wake/profile controls, voice controls, Creative/Discover surfaces, and clearly grouped tools.
- Developer and repair material was grouped below normal conversation rather than replacing the home experience.
- The owner could import/test/remove the Brain, teach replies, edit Core, manage wake profiles, inspect capabilities, configure boot/background behavior, pair Forge, and use diagnostics.

### 8. Continuity and data

- The same package and signing lineage were required for in-place upgrades.
- The local GGUF, memory, Core, owner tone, learned replies, wake profiles, Owner Apps, Forge trust, tasks/workflows, custom media responses, and settings lived in app-private data and were expected to survive updates.
- Physical 1.33.3 evidence confirms the custom `sage glitch` Red Queen profile, local Brain, owner profile, Forge trust, accessibility, notifications, usage access, battery exemption, boot startup, and device-admin state survived in place.

## Likely historical behavior

These behaviors are well supported by source and tests but lack a matching physical observation in the reviewed evidence:

- The exact home layout and all grouped control panels rendered correctly at version 1.33.4 on the owner's display size.
- Every installed owner-selected TTS voice/rate/pitch combination sounded identical after process restart.
- All listed package, file, network, media, Creative Studio, Workbench, Shizuku, autonomy, and custom media actions remained usable on the final physical build.
- The 1.33.4 wake-fragment changes fixed the misses recorded on 1.33.3; the commit has deterministic gates but no reviewed 1.33.4 physical acceptance record.
- The expanded 1.33.4 response budget consistently improved natural completion without unacceptable latency on the tablet.
- Follow-up listening felt continuous and natural in all TTS/recognizer timing combinations.

## Unknown historical behavior

- The exact installed GGUF family, name, architecture, parameter count, quantization, tokenizer, template, trained context, file size, digest, and provenance.
- Measured cold-start, first-token, warm-turn, tokens/second, peak RSS, and thermal figures for the inherited GGUF on the VASOUN tablet.
- The precise subjective visual appearance and conversational feel the owner considers the single “last good” point if it predates 1.33.3.
- Whether every old control was used or valued by the owner, and which were already broken before SageOS 2.
- Whether full conversation history was durably recoverable across process death; inspected source clearly retained recent Brain turns in process, but reviewed evidence does not prove a durable complete transcript.
- Whether custom audio/video replies, every owner automation, and every external app workflow were exercised on the final pre-rebuild installation.
- Whether the 1.33.4 APK itself was installed and physically accepted.

## Sources inspected

- All requested historical branch tips that exist on `origin`, including the 1.30/1.31 behavior, voice, routing, background, executor, orchestration, autonomy, Forge, and tablet-control lines.
- The self-contained 1.33.4 reconstruction and all preceding patches.
- The 1.33.3 physical evidence packet and the 1.33.4 recovery boundary.
- Historical `MainActivity`, `SageVoiceService`, `SageCommandEngine`, `SageBrainManager`, `SageBrainRequestPolicy`, `SageAccessibilityService`, wake/profile, owner-app, workflow, speech-backend, and Sherpa recognition/model code.
