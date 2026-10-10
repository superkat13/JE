# Voice repair progress

Status: Voice self-repair loop completed on top of PR #69 (codex/recover-jules-voice-adapter), reviewed on PR #71, and the two confirmed review defects are fixed. Runtime responder drives the orchestrator (start/cancel), owner-facing verified reporting and restart persistence are implemented, capture outcomes are presented to the owner after capture, and regression tests were added.

Base: release/sage-219 at 1498d57a3896be072e0d4ae360be7c76a5935512
PR #69: https://github.com/superkat13/JE/pull/69 (draft; head codex/recover-jules-voice-adapter at fe40fcb)
PR #71: https://github.com/superkat13/JE/pull/71 (draft; head opencode/voice-self-repair-loop)

Completed:
- Preserved all PR #69 improvements: AndroidSpeechPort diagnostic window, VoiceDiagnosticAdapter, VoiceRepairCapable, adapter Robolectric suite.
- VoiceRepairReporter: owner-facing terminal reports (SUCCESS/HEALTHY/FAILED-by-cause/INTERRUPTED/CANCELLED) plus a redacted traceStep for diagnostics (no phrase/IDs).
- VoiceRepairExport.from(session) shared builder; orchestrator.createExport delegates to it.
- VoiceRepairHistoryStore + SharedPreferencesVoiceRepairHistoryStore: bounded terminal history (capacity 8) and a single in-flight marker. Non-terminal markers are durably reconciled on restart or on the next test begin into a terminal INTERRUPTED/unverified history event (idempotent, never auto-repaired, never relabeled). `reconcileInterrupted()` clears the marker; `observe()` also preserves a stale interruption when a new session pins the marker or a terminal completes.
- VoiceRepairResponder rewritten: request → typed phrase capture (1–200 chars) → startRepair(phrase); cancel routes through orchestrator.cancel(); status/export commands; busy/start failures reported honestly; command-shaped phrases consumed as phrases. Diagnostics are redacted: the owner's typed phrase never reaches ordinary traces/diagnostic exports.
- Completion presentation: `VoiceRepairCompletionPresenter.ownerText()` surfaces SUCCESS/HEALTHY/FAILED capture outcomes; `SageRuntimeHost` forwards the outcome to the text-response channel (outside any turn state machine) exactly once per session result, and the "voice repair status" command also presents the persisted terminal report (the owner can check there at any time). Interruption and cancellation are surfaced only through status/startup notices, never pushed unprompted.
- SageRuntimeHost: startup `reconcileInterrupted()` records a redacted `interrupted session recovered; unverified; no auto-repeat` trace; orchestrator onChanged → trace (redacted) + history + outcome presentation.
- Self-reliance lab includes VoiceRepairReporterTest and the Robolectric VoiceDiagnosticAdapterTest.
- Tests: VoiceRepairReporterTest (9), VoiceRepairResponderTest (22, incl. phrase-redaction regressions), VoiceRepairSessionManagerTest (12), VoiceRepairIntegrationTest (21, incl. outcome-presentation + status presentation) — 64 tests, 0 failures locally; store Robolectric suite now 15 tests (reconcile battery incl. double restart, new repair after interruption, no auto-repair).

Actual test runs on this host: compileDebugKotlin + compileDebugUnitTestKotlin SUCCESS; pure-JVM voicerepair suites 64/64 green.
Robolectric suites (VoiceDiagnosticAdapterTest, SharedPreferencesVoiceRepairHistoryStoreTest) compile here but cannot execute on aarch64 Termux (conscrypt lacks linux-aarch_64 natives); they pass in CI. No hardware validation. No APK merge/release performed (unauthorized).

Hardware self-repair evidence: none yet (pending owner procedure and device/CI run).