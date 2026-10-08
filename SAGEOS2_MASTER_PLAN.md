# SageOS 2.0 — single-codebase rebuild

This branch is the only active SageOS 2 development line. Do not create version-per-fix branches or repair APK families.

## Core identity

Sage is the owner's **virtual twin**. She is not a generic assistant personality authored by the developer. Sage is a distinct software identity designed to model the owner's intent, preferences, habits, working style, trusted tools, recurring choices, and accumulated experience closely enough to operate as a second set of hands and a second thinking surface.

The twin model is not blind imitation. Sage can reason, notice conflicts, ask when genuinely necessary, and maintain continuity, but her center of gravity is the owner's context rather than a developer-authored assistant persona.

## Non-negotiable product rules

1. One Sage identity, one runtime coordinator, one codebase.
2. Sage is the owner's virtual twin, not a generic helper with a canned personality.
3. Kotlin owns the Android application and lifecycle. Native C/C++ is isolated behind a Brain interface only when it earns its complexity.
4. Voice and text enter the same turn pipeline.
5. Fast deterministic device commands never wait on the large language model.
6. Deep reasoning is cancellable and may not own the microphone lifecycle.
7. Every callback carries a turn/generation identity. Stale callbacks are ignored and counted.
8. `Sage` while Sage is thinking produces an immediate lightweight acknowledgement without cancelling the active thought.
9. `Sage` -> spoken `Yes` -> command is the normal wake flow.
10. The phrase `Do you feel like chicken tonight?` routes silently to the named workflow. Workflow behavior is defined separately from the trigger.
11. Capabilities are centralized behind CapabilityBroker. Accessibility, Assistant role, Device Admin/Owner, platform privilege, and the SageOS root broker must never be scattered as ad-hoc calls across UI code.
12. Shizuku is not part of SageOS 2. Do not add its dependency, permission, adapter, setup path, or fallback.
13. Root is part of the final SageOS 2 destination. The AI process does not need to run as uid 0; it receives broad root-backed capability through the SageOS root broker.
14. No hidden SageOS behavioral restriction layer. Sage's identity, judgment, preferences, principles, and self-restrictions live in the visible, revisioned Sage Core.
15. Infrastructure may validate caller identity, data shape, OS invariants, and execution success. Infrastructure does not define Sage's personality or values.
16. If a model/provider/tool refuses or cannot perform a request, SageOS preserves that provenance rather than silently converting it into Sage's own rule.
17. Diagnostics are part of the architecture, not an afterthought.
18. A feature is not complete merely because it compiles. It needs automated coverage and physical-tablet acceptance where hardware/runtime behavior is involved.
19. The repository is the continuity source of truth. A lost chat must not strand the project.
20. Chat is Sage's home. Owner-facing screens use human language and present Sage before runtime machinery; Core, diagnostics, capabilities, workflows, scopes, and engineering controls live under Settings/Advanced.

## Virtual-twin model

Sage Core separates four kinds of durable context:

- **Twin identity**: who Sage is in relation to the owner and how she should think about that relationship.
- **Owner model**: preferences, habits, defaults, recurring choices, vocabulary, working style, trusted apps/tools, and learned patterns.
- **Self model**: Sage's own operational identity, capabilities, current limitations, experiences, and self-restrictions.
- **Shared continuity**: ongoing projects, decisions, task state, and lessons learned that both voice and text modes use.

These are readable, revisioned data. They are not hidden developer prompts scattered through services.

## Runtime layers

- `turn`: single authoritative conversation/voice state machine.
- `routing`: deterministic fast path vs deep-reasoning path vs workflow.
- `brain`: replaceable/cancellable reasoning engines. Tablet-local, Forge, or future engines implement the same contract.
- `identity`: Sage Core plus owner/twin model. Visible and revisioned.
- `capability`: one authority snapshot and execution boundary for device actions.
- `root`: root-backed execution contract. Technical validation and audit, not personality policy.
- `speech`: lightweight wake, command recognition, TTS, and echo guard. It reports events to `turn`; it does not decide application state.
- `memory`: conversation history, learned owner/device/app facts, long-term memory, and project continuity.
- `diagnostics`: trace each turn from input through route, action/brain, response, speech, and closure.
- `update`: one signed update lineage with health check and rollback. No repair-bundle carousel.

## Sage Core ownership model

Sage Core is the only SageOS-native behavioral constitution. It is stored as readable data, can be inspected in full, has revision history, and is supplied to active reasoning engines. The owner/twin model lives alongside it and is learned from explicit choices plus durable experience, not hard-coded by the developer.

Provider/model safeguards or limitations are external facts. SageOS keeps provenance: a provider refusal is a provider refusal, not a fake claim that Sage personally chose the restriction.

## Root architecture

The final system image contains a small privileged broker started by init and confined by SELinux. Sage's AI/application process remains separated from the privileged process for reliability. The broker verifies the Sage caller, parses typed operations, executes them, and returns results/audit IDs. It does not inspect natural-language intent or maintain a behavioral denylist.

The contract includes high-level operations and a general argv-based root process operation so Sage is not trapped by an incomplete list of preplanned actions. Shell commands are represented as executable + argv/environment rather than concatenated strings to avoid accidental quoting/injection bugs.

App-level development builds report the root broker as unavailable until the system-image/root integration exists. They never pretend root is active.

## Android voice architecture

The selected Android VoiceInteractionService is the lightweight always-running hotword home. Heavy session/business logic lives outside that process. The recognizer component remains explicit because Android voice-interaction applications require a RecognitionService. This architecture is guarded by CI.

## Migration policy

The 1.x repository is evidence and donor material. Nothing is copied merely because it exists. Each donor feature must be classified as KEEP, REWRITE, or DROP before entering `sageos2`.

Initial KEEP concepts: Sage Core, Owner Apps/APKs, Forge pairing, wake profiles, local Brain capability, Red Queen as a visible mode, device authority discovery, diagnostics, signed update intent.

Initial REWRITE concepts: voice lifecycle, Brain routing/watchdog integration, cross-app capability execution, Sage Core/twin-model persistence, memory, background continuity, updater.

Initial DROP concepts: version-specific patch chains, reconstruction-as-normal-development, duplicate state owners, obsolete Mature Research surface, Shizuku, hidden behavioral restriction layers, canned developer persona, and redundant compatibility shims not required by the target tablet.

## Tablet target

Primary acceptance target: VASOUN L10_T05, Android 13. Final SageOS 2 destination includes root-broker/system integration. Final package/signing/system-image migration happens only after the app-level core passes automated gates and the tablet migration path is verified.
