# Sage Forge: bounded candidate self-repair prototype

## Why this is separate from Android voice repair

Sage has two different kinds of repair. An Android voice failure can sometimes be corrected **in place** by a narrowly defined native actuator such as recreating a command recognizer, followed by a real microphone retest. The Android work in PR #69 is for that path.

Python/Forge **source code** failures require a separate engineering loop, because rewriting an installed Android APK in place is not a valid Android deployment or recovery mechanism.

This module implements a reviewable **candidate** loop for Sage Forge Python files, not unsupervised changes to a running device:

`observe -> root-cause proposal -> stage in detached Git worktree -> rerun same test suite -> return reviewed diff or BLOCKED`.

## Current implementation

- Location: `sage_forge/self_repair_loop.py`.
- Only explicitly selected `sage_forge/*.py` target paths; rejects absolute and parent-traversal paths and symlink targets.
- Requires an unchanged repository worktree before starting.
- A detached Git worktree keeps generated edits away from the checked-out source and installed Sage.
- Exactly one fixed Python unittest suite is run with `subprocess.run(argv, shell=False)`, a hard per-run deadline, captured exit code and bounded failure output.
- Model gets one bounded source file + test errors, **never** arbitrary repository contents by default.
- Model replies with strict JSON: `source_sha256`, `replacement_code`, `diagnosis`. Exact source SHA must match; malformed replies fail closed. Python syntax is checked before any test.
- At most three candidate iterations, no infinite retry; successful verification returns a diff for independent review. Candidate worktree is cleaned up on exit.
- Tests use Python `-B` to prevent stale bytecode caches from making a same-length repair look unsuccessful.
- Does not commit, merge, push, install, restart services, edit the original checkout or claim that passing tests guarantees correct behavior.

## Proof and test procedure

The reference prototype was executed locally with:

```sh
python -m unittest -v sage_forge.tests.test_self_repair_loop
```

Five focused tests passed: successful patch with original unchanged, already-healthy fast path, malformed model output, bounded unsuccessful retries, and traversal/dirty-tree rejection.

**This does not mean the complete Forge suite or Android build was run on the feature branch.** It does not establish any hardware result.

## Still required before production integration

1. **OS/process isolation**: Unit tests execute model-proposed source code. A detached worktree is *not* a security sandbox. Before connecting a real model, run verification inside a low-privilege isolated container/VM without network or owner secrets, with CPU/memory/process/file limits and hard termination. Do not expose this candidate runner as an owner-facing Forge job yet.
2. **Privacy**: The injected `PatchModel` is a protocol, not a configured provider. Select a trusted local model/provider and redact source, logs, bearer tokens and paths before external model calls. Do not silently upload diagnostics.
3. **Trusted admission**: Add a fixed allowlist of repair targets and test presets, actual device/owner approval for high-impact actions, authenticated job identity, cancellation, durable attempt logs and bounded output schemas in the existing Forge tool framework.
4. **Stronger acceptance**: Add baseline reproduction, targeted regression, whole relevant suite, independent security/static checks, regression rollback and a later owner-approved integration branch. A model-generated patch is not trustworthy merely because one test turns green.
5. **Sage conversation path**: Connect owner request -> classification -> safe Android actuator **or** isolated Forge code candidate -> verification result -> human-language evidence. Keep Brain-independent local routing available. Never present an unverified candidate as an installed repair.
6. **Android voice path**: Separately connect `VoiceRepairResponder.startRepair` and cancellation to the orchestrator, add persistent interrupted/unverified state, owner-facing recognized/expected test results, and tablet acceptance. PR #69's adapter alone does not finish this path.

## Operational boundary

This is a functioning, intentionally unregistered code-repair *candidate engine* for controlled development experiments. It is not Sage autonomously fixing all tablet/app problems, and no APK or source-code update has been applied to the tablet.
