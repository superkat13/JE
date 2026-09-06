# SageOS 2.0 — single-codebase rebuild

This branch is the only active SageOS 2 development line. Do not create version-per-fix branches or repair APK families.

## Non-negotiable product rules

1. One Sage identity, one runtime coordinator, one codebase.
2. Kotlin owns the Android application and lifecycle. Native C/C++ is isolated behind a Brain interface only when it earns its complexity.
3. Voice and text enter the same turn pipeline.
4. Fast deterministic device commands never wait on the large language model.
5. Deep reasoning is cancellable and may not own the microphone lifecycle.
6. Every callback carries a turn/generation identity. Stale callbacks are ignored and counted.
7. `Sage` while Sage is thinking produces an immediate lightweight acknowledgement without cancelling the active thought.
8. `Sage` -> spoken `Yes` -> command is the normal wake flow.
9. The phrase `Do you feel like chicken tonight?` routes silently to the named workflow. Workflow behavior is defined separately from the trigger.
10. Capabilities are centralized behind CapabilityBroker. Accessibility, Assistant role, Device Admin/Owner, platform privilege, and the SageOS root broker must never be scattered as ad-hoc calls across UI code.
11. Shizuku is not part of SageOS 2. Do not add its dependency, permission, adapter, setup path, or fallback.
12. Root is part of the final SageOS 2 destination. The AI process does not need to run as uid 0; it receives broad root-backed capability through the SageOS root broker.
13. **No hidden SageOS behavioral restriction layer.** Sage's identity, judgment, preferences, principles, and self-restrictions live in the visible, revisioned Sage Core.
14. Infrastructure may validate caller identity, data shape, OS invariants, and execution success. Infrastructure does not decide what Sage is morally or behaviorally allowed to want.
15. If a model/provider/tool refuses or cannot perform a request, SageOS reports that as a provider/tool limitation rather than silently converting it into Sage's own rule. Alternative configured engines may be tried where appropriate.
16. Diagnostics are part of the architecture, not an afterthought.
17. A feature is not complete merely because it compiles. It needs automated coverage and physical-tablet acceptance where hardware/runtime behavior is involved.
18. The repository is the continuity source of truth. A lost chat must not strand the project.

## Runtime layers

- `turn`: single authoritative conversation/voice state machine.
- `routing`: deterministic fast path vs deep-reasoning path vs workflow.
- `brain`: replaceable/cancellable reasoning engines. Tablet-local, Forge, or future engines implement the same contract.
- `identity`: Sage Core. Visible and revisioned identity, principles, preferences, and self-restrictions supplied to every reasoning engine.
- `capability`: one authority snapshot and execution boundary for device actions.
- `root`: root-backed execution contract. Technical validation and audit, not behavioral policy.
- `speech`: lightweight wake, command recognition, TTS, and echo guard. It reports events to `turn`; it does not decide application state.
- `memory`: durable conversation history, learned device/app facts, and long-term memory are separate from Sage Core.
- `diagnostics`: trace each turn from input through route, action/brain, response, speech, and closure.
- `update`: one signed update lineage with health check and rollback. No repair-bundle carousel.

## Sage Core ownership model

Sage Core is the only SageOS-native behavioral constitution. It is not compiled into hidden conditionals. It is stored as readable data, can be inspected in full, has revision history, and is supplied to the active Brain as context. Self-restrictions are written there as Sage's own explicit rules rather than being scattered through services, routers, root code, UI code, or provider adapters.

Provider/model safeguards or limitations are external facts. SageOS must keep provenance: a provider refusal is a provider refusal, not a fake statement that Sage personally chose the restriction.

## Root architecture

The final system image contains a small privileged broker started by init and confined by SELinux. Sage's AI/application process remains separated from the privileged process for reliability. The broker verifies the Sage caller, parses typed operations, executes them, and returns results/audit IDs. It does not inspect natural-language intent or maintain a behavioral denylist.

The contract includes both high-level operations and a general argv-based root process operation so Sage is not trapped by an incomplete list of preplanned actions. Shell commands are represented as executable + argv/environment rather than concatenated strings to avoid accidental quoting/injection bugs. Sage Core decides when such capability should be used.

App-level development builds report the root broker as unavailable until the system-image/root integration exists. They never pretend root is active.

## Android voice architecture

The selected Android VoiceInteractionService is the lightweight always-running hotword home. Heavy session/business logic lives outside that process. The recognizer component remains explicit because Android voice-interaction applications require a RecognitionService. This architecture is guarded by CI.

## Migration policy

The 1.x repository is evidence and donor material. Nothing is copied merely because it exists. Each donor feature must be classified as KEEP, REWRITE, or DROP before entering `sageos2`.

Initial KEEP concepts: Sage Core, Owner Apps/APKs, Forge pairing, wake profiles, local Brain capability, Red Queen as a visible mode, device authority discovery, diagnostics, signed update intent.

Initial REWRITE concepts: voice lifecycle, Brain routing/watchdog integration, cross-app capability execution, Sage Core persistence/revision history, memory, background continuity, updater.

Initial DROP concepts: version-specific patch chains, reconstruction-as-normal-development, duplicate state owners, obsolete Mature Research surface, Shizuku, hidden behavioral restriction layers, and redundant compatibility shims not required by the target tablet.

## Tablet target

Primary acceptance target: VASOUN L10_T05, Android 13. Final SageOS 2 destination includes root-broker/system integration. Final package/signing/system-image migration happens only after the app-level core passes automated gates and the tablet migration path is verified.
