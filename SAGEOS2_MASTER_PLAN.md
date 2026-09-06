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
9. The owner-defined phrase `Do you feel like chicken tonight?` routes silently to the named owner workflow. The workflow implementation is separate from the trigger and can enforce its own scope/authorization rules.
10. Capabilities are centralized behind CapabilityBroker. Accessibility, Assistant role, Device Admin/Owner, Shizuku, platform privilege, and the future SageOS root broker must never be scattered as ad-hoc calls across UI code.
11. Diagnostics are part of the architecture, not an afterthought.
12. A feature is not complete merely because it compiles. It needs unit coverage and physical-tablet acceptance where hardware/runtime behavior is involved.

## Runtime layers

- `turn`: single authoritative conversation/voice state machine.
- `routing`: deterministic fast path vs deep-reasoning path vs owner workflow.
- `brain`: replaceable/cancellable reasoning engines. Tablet-local, Forge, or future engines implement the same contract.
- `capability`: one authority snapshot and execution boundary for device actions.
- `speech`: wake, recognition, TTS, echo guard. It reports events to `turn`; it does not decide application state.
- `memory`: durable owner instructions, conversation history, learned device/app facts, and long-term memory are separate stores.
- `diagnostics`: trace each turn from input through route, action/brain, response, speech, and closure.
- `update`: one signed update lineage with health check and rollback. No repair-bundle carousel.

## Migration policy

The 1.x repository is evidence and donor material. Nothing is copied merely because it exists. Each donor feature must be classified as KEEP, REWRITE, or DROP before entering `sageos2`.

Initial KEEP concepts: Sage Core, Owner Apps/APKs, Forge pairing, wake profiles, local Brain capability, Red Queen as a visible mode, device authority discovery, diagnostics, signed update intent.

Initial REWRITE concepts: voice lifecycle, Brain routing/watchdog integration, cross-app capability execution, memory, background continuity, updater.

Initial DROP concepts: version-specific patch chains, reconstruction-as-normal-development, duplicate state owners, obsolete Mature Research surface, redundant compatibility shims that are not required by the target tablet.

## Tablet target

Primary acceptance target: VASOUN L10_T05, Android 13. The final package/signing migration is decided only after the new app passes the core runtime tests and the tablet migration path is verified.
