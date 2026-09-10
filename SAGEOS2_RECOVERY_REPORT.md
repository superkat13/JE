# SageOS 2 recovery report

Date: 2026-09-10
Line: `sageos-2` in `superkat13/JE`
Continuity boundary: package `com.pineapple.sagecommander.stable`; signing lineage and app-private owner data unchanged
Historical behavioral reference: Sage 1.33.4 at `1dc79bd250d4e7565c9b610419896a8eb13190e8`
Audited SageOS 2 candidate: `acf3b03bd77d8d97a573ae2f22f875921f75e8ad`

The implementation commit and independent build hashes are reported by the final handoff after verification; a commit cannot contain its own final hash.

## Brain decision

The installed `files/brain/sage-brain.gguf` is not in Git, in the APK, or anywhere under `/home/kat`, and no Android device is attached. Its family, embedded name, architecture, parameters, quantization, trained context, tokenizer, chat template, file size, digest, and provenance therefore remain **UNKNOWN**. No repository label or historical recommendation has been treated as metadata.

A read-only bounded GGUF v2/v3 inspector now reports those fields, tensor-type distribution, summed tensor parameters, alignment/data offset, complete metadata, and optional full-file SHA-256. It was checked against synthetic malformed/bounds fixtures and a real llama.cpp vocabulary GGUF. Exact compatibility is gated against llama.cpp commit `d73c1d6b22a2d3ecc74c2c9cde354015ee72e862`, the revision built into SageOS 2.

No alternative was researched, downloaded, benchmarked, imported, or recommended. The inherited Brain was not replaced or modified. Sage-specific comparative benchmarking remains blocked until the exact current file is inspected and can serve as the baseline on the 8 GB VASOUN tablet.

## What changed between last-good Sage and candidate 206

Intentional architectural changes that should remain:

- A Chat-first home replaced the dense programmatic control screen; engineering detail moved under Advanced/Diagnostics.
- One explicit coordinator now owns turn, listening, echo-guard, follow-up, queue, and stale-callback state.
- Brain loading became lazy and stayed off the UI thread, with durable conversation and task checkpoints.
- Wake spotting moved into an isolated process, while capability execution gained structured broker validation.
- Root-broker contracts replaced the planned privilege direction; root/system-image work remains deferred.

Regressions that made Sage feel or work differently:

- The proven local command recognizer was replaced by a service that always returned `ERROR_CLIENT`; the surviving 45,202,074-byte owner speech model was ignored.
- Fast routing claimed commands the fast executor could not parse, producing immediate incapability instead of a normal Sage/Brain turn.
- Recognition listeners read mutable current turn/generation state, allowing a late callback from an old recognizer session to be mislabeled as current.
- Migrated owner Core text survived in storage but was demoted into generic notes and could be weakened or removed by whole-prompt head/tail clipping.
- The fresh fallback identity was less recognizably Kat's persistent Sage, and model-family assumptions leaked into comments/default prompt control.
- Relevant memories could lose to unrelated high-confidence memories, and entire memory/self/history sections could disappear under prompt pressure.
- Real bracketed tool results were not recognized by the continuation policy, so a multi-step tool turn could lose its tool contract after step one.
- Custom wake/profile commands, semantic app control, most historical workflows, and many owner-facing capabilities remain disconnected even though much of their private data survives.

The complete evidence-backed comparison is in `SAGE_BEHAVIOR_BASELINE.md`, `SAGEOS2_BEHAVIOR_CURRENT.md`, and `SAGEOS2_RECOVERY_MATRIX.md`.

## Targeted restoration completed

1. `FastCommandParser` is now the single eligibility authority for fast device routing. Unsupported wording reaches the Brain unchanged instead of entering a dead fast path.
2. The bounded Sage 1.33.4 Sherpa RecognitionService is restored against the existing private English streaming model directory. It reads but never installs, deletes, moves, or rewrites that owner data.
3. The local recognizer is explicitly selected only when Java/native/model readiness is true. Android on-device/default recognition remains a one-attempt technical fallback; a normal `NO_MATCH` does not trigger a second listener.
4. The recognizer component is private. Per-session listeners capture immutable turn/generation/session values; stale and duplicate final/error callbacks are invalidated before dispatch.
5. TTS completion is utterance-ID checked and exactly-once. Candidate 206's terminal recognition-miss transition is retained and adversarially exercised.
6. The fallback Core again names Sage as Kat's personal AI, tablet partner, digital twin, and computational manifestation: persistent, direct, capable, warm, cheeky, truthful, and committed to the work. This applies only when owner Core is absent.
7. Migrated owner Core is rendered early as explicit authoritative `OWNER CORE`, with its historical 2,400-character prompt contribution bound. Stored source text is untouched and other notes are retained without duplication.
8. Memory ordering considers current-request lexical relevance before confidence/recency. Section-aware prompt fitting keeps applicable identity, owner, self, memory, recent history, task, and tool sections represented.
9. Family assumptions were removed from prompt defaults and loader comments. `/no_think` is added natively only when the embedded model template itself advertises a thinking mode.
10. The exact bracketed tool-result form now retains task/tool context for the next bounded reasoning step.

No architecture was replaced wholesale, no new product/mode/version was created, and no package, signer, owner-data, or GGUF mutation was made.

## Adversarial review results

- Duplicate recognizer finals cannot create a second Brain turn.
- A final from a closed recognizer session cannot reopen or replace the current listener.
- A recognition miss clears follow-up before its one spoken miss line; duplicate TTS completion cannot restart listening.
- Wake during fast/deep thinking produces only `I'm thinking`, without canceling or duplicating the active turn.
- Local-ASR technical failure can fall back once; `NO_MATCH` cannot recursively fan out into another recognizer.
- Queued typed input is recorded immediately and dispatched once in FIFO order; typed replies remain silent.
- Brain timeout/cancellation and stale response checks leave Chat recoverable.
- A bracketed capability result preserves the same turn and bounded tool contract; the per-turn ceiling remains four.
- Migration is copy-only/idempotent by marker and source stores are not deleted. Physical count/digest comparison remains required because JVM tests cannot read the owner's private installation.

## Not restored in this minimum recovery

- Arbitrary custom KWS phrase compilation and saved wake-profile command execution: current token support is not sufficient to make every migrated phrase truthful, and guessing could make background wake unsafe.
- Semantic label tapping/number overlays, timers, alarms, screenshots, settings/media actions, notification interaction, and Owner App startup procedures: these need individual bounded executors and physical target/permission tests.
- Most historical compiled workflows, custom media, Voice Studio/Speech Lab, Creative/Discover, Package/File/Network labs, Workbench, and autonomy/self-repair execution: data must first be inventoried read-only to prevent duplicate or destructive replay.
- Shizuku/Sui and root/system-image work: intentionally deferred until P0/P1 physical acceptance.
- Output-token/context tuning or a Brain replacement: prohibited until the exact inherited GGUF and its cold/warm physical behavior are measured.

These omissions are regressions or unverified capabilities, not claims that the owner should accept them permanently. They remain sequenced in `SAGEOS2_RECOVERY_PLAN.md`.

## Verification boundary

Host verification covers parsing bounds, routing, identity/Core/memory/prompt construction, tool continuation, coordinator interleavings, unit behavior, release lint/build, package/version/ABI/native symbols, dependency pins, payload reproducibility, and signing configuration. Final command counts, hashes, and any deviations are recorded in the handoff.

Pre-commit verification completed:

| Check | Result |
|---|---|
| Sage 1.33.2 reconstruction gate | 28/28 passed |
| Sage 1.33.3 reconstruction gate | 18/18 passed |
| Sage 1.33.4 reconstruction gate | 25/25 passed |
| GGUF parser + static recovery contracts | 9/9 passed |
| SageOS 2 JVM unit suite | 149/149 passed across 35 suites |
| Release lint | 0 errors; 78 non-blocking warnings, none in the repaired files |
| Release assembly | Passed; output deliberately unsigned |
| Package/version/ABI check | Stable package, version 206 / 2.0.0, min 26, target 35, arm64 only |

The historical Python checks are standalone reconstruction gates with required source-tree arguments, not one importable `unittest discover` suite. A blanket discovery invocation reports missing reconstruction inputs and is not counted as a product failure; each relevant reconstructed-version gate above was run against its intended tree.

Host verification cannot prove the exact installed Brain, cold/warm GGUF generation, microphone/TTS acoustics, wake accuracy, RAM/thermal behavior, app-private migration counts, current installed signer, or physical owner experience. No APK is justified while those gates remain open.

## Smallest eventual physical acceptance

1. Connect the tablet read-only first. Record installed package/version/signer and GGUF size/SHA/metadata plus counts for Core, memory, Owner Apps, wakes, workflows, history, and unfinished tasks. Do not uninstall or clear data.
2. Only after signer/lineage and in-place upgrade safety pass, launch Sage and send one identity/familiarity prompt. Confirm the exact owner bubble appears before cold load and no TTS is used.
3. Send one continuation and confirm a coherent, faster warm reply using the first turn and a known memory.
4. Say “Sage,” hear one acknowledgement, speak one command, complete one follow-up, then verify wake-ready.
5. Produce one silence/miss and verify no more than one miss line and no recursive listener.
6. Wake during a slow Brain turn and verify only `I'm thinking` plus one original final answer.
7. Verify the owner's actual Core, one learned reply, one Owner App, one workflow, and every real wake profile without editing them.
8. Compare post-update signer, GGUF digest, and private-data counts to the pre-update record; stop on any mismatch, ANR, duplicate action, generic identity, or voice loop.
