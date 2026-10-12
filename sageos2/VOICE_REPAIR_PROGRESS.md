# Voice repair host handoff

Branch: codex/voice-repair-host.
Draft PR: https://github.com/superkat13/JE/pull/76 (target opencode/voice-self-repair).
Tested code SHA: 480fbce2aef585c9d04e451773fc449f14b06833.
Base and final fetched adapter SHA: 3b4340e4e50bb147b9779e715a53a542a38a7296.
The final checkpoint commit changes this handoff only; its SHA is recorded in the PR body.

## Completed host work

- Local requests open a dedicated expected-phrase field. The phrase bypasses normal routing, tools, actions, conversation memory and ordinary diagnostic exports.
- Deferred, bounded idle admission waits for the local reply to finish. Runtime input and recovery admission are reserved during capture/reset/retest.
- Chat shows speak prompts, cancellation, actual recognized words, backend/error/timing evidence, completed repair and retest outcomes.
- Cancel and Activity interruption reach the controller, invalidate pending admission, and release the diagnostic microphone before restoring the runtime's listening mode. Cancellation still releases resources when persistence fails.
- Private diagnostic-only persistence restores incomplete sessions as interrupted/unverified, with ten-session history shown in Diagnostics. Explicit document-picker export includes only the chosen test's words and evidence; no automatic upload or raw audio recording.
- Workflow, runtime isolation, Brain-unavailable local routing, deadline, late result and persistence tests are included in the integration lab. The existing full Android workflow now covers this PR target.
- VoiceDiagnosticPort and OpenCode-owned adapter/speech/repair-capable files and adapter tests are unchanged. Identity, models, signing and owner data are unchanged. No APK was installed or numbered release published.

## Before/after evidence

Before: SageRuntimeHost supplied no start callback, so hearing requests reported that the workflow was not connected. VoiceRepairResponder cancellation only relabelled the session manager, leaving controller ownership untouched. There was no dedicated diagnostic input or persistent host session history.

After: the local responder opens dedicated input even with Brain unavailable; admission waits for an idle runtime window; command-shaped phrases enter only diagnostic capture. Tests assert controller release, runtime release, stale callback rejection and interruption recovery, and distinguish a completed reset from a verified matching retest. Capture/reset/retest tests use injected diagnostic ports and do not demonstrate a physical microphone repair.

## Actual validation

Final CI: https://github.com/superkat13/JE/actions/runs/38286212342
- Integration lab: 277 tests, 0 failures, 0 errors, 0 skipped.
- Full Android/JVM suite: 465 tests, 0 failures, 0 errors, 0 skipped.
- Android Kotlin compilation, testDebugUnitTest, lintRelease, assembleDebug, native inference and arm64 packaging checks passed.
- git diff --check and protected ownership-path audit passed.

Earlier CI caught an invalid IntRange takeLast call in the new persistence code. Corrected it to operate on a List; no assertion or fixture was weakened. The subsequent full run passed 464 tests; the final run includes one additional deadline cleanup test.

Local validation limit: the initial lab bootstrap was unavailable as an executable; downloading Gradle through bash succeeded, but the local Java 25 Gradle daemon disappeared before compilation. Local Android compilation or test success is not claimed; the configured CI path supplied the successful results above.

## Concrete remaining blocker

The final fetch still contains scaffold VoiceDiagnosticAdapter.capture: it assigns a callback but never starts microphone capture. OpenCode owns that implementation. Therefore actual adapter reset/recreation -> recognized retest integration remains blocked, and hardware/device acceptance is NOT RUN. Do not merge or describe the owner's hearing problem as repaired on the strength of these host/controller tests.

Exact next step: fetch OpenCode's completed adapter on opencode/voice-self-repair, integrate it without changing VoiceDiagnosticPort, run the real-adapter fault/reset/retest integration tests and the same Android CI gates, then perform the tablet procedure below. Do not restart the host implementation.

## Short tablet procedure, after adapter integration

Type “fix your hearing”, enter a short expected phrase in the separate test box, tap Start test, speak only at “Speak now”, and repeat the same phrase if a retest is prompted. Compare the actual words and repair evidence. Cancel another test, then confirm normal Talk works. If unresolved, use Save test export to save the explicit test locally. This procedure has not been run on hardware.
