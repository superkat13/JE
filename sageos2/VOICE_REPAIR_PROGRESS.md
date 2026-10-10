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
- Completion presentation: `VoiceRepairCompletionPresenter.ownerText()` surfaces SUCCESS/HEALTHY/FAILED capture outcomes; `VoiceRepairOutcomeNotifier` persists exactly one SAGE/SYSTEM conversation entry (turnId 0) through `ConversationHistoryStore` BEFORE notifying the UI, exactly once per terminal result state per session; `SageRuntimeHost` wires it to `history` + `observer.onTextResponse(0L, …)`. MainActivity renders from persistent history, so the report shows foreground and after background→foreground (the persistence is the source of truth, not the callback). The "voice repair status" command also presents the persisted terminal report. Interruption and cancellation are surfaced only through status/startup notices, never pushed unprompted.
- SageRuntimeHost: startup `reconcileInterrupted()` records a redacted `interrupted session recovered; unverified; no auto-repeat` trace; orchestrator onChanged → trace (redacted) + history + outcome presentation.
- Self-reliance lab includes VoiceRepairReporterTest and the Robolectric VoiceDiagnosticAdapterTest.
- Tests: VoiceRepairReporterTest (9), VoiceRepairResponderTest (22, incl. phrase-redaction regressions), VoiceRepairSessionManagerTest (12), VoiceRepairIntegrationTest (21, incl. outcome-presentation + status presentation) — 64 tests, 0 failures locally; store Robolectric suite now 15 tests (reconcile battery incl. double restart, new repair after interruption, no auto-repair).
- Owner-visible completion integration: `VoiceRepairOutcomeNotifierTest` (9) drives real controller→notifier→persistent history→the `recent(60)` row source MainActivity renders: exactly-once per result, persistence-before-notify, interruption/cancel never pushed, normal routed turns unaffected, second-session entry coexistence. `VoiceRepairCompletionPersistenceTest` (Robolectric, 5) proves background→foreground via the real prefs store and idempotency across activity lifecycle.

Actual test runs on this host: compileDebugKotlin + compileDebugUnitTestKotlin SUCCESS; pure-JVM voicerepair suites 73/73 green (incl. 9 new notifier integration tests).
Robolectric suites (VoiceDiagnosticAdapterTest, SharedPreferencesVoiceRepairHistoryStoreTest) compile here but cannot execute on aarch64 Termux (conscrypt lacks linux-aarch_64 natives); they pass in CI. No hardware validation. No APK merge/release performed (unauthorized).

Hardware self-repair evidence: none yet (pending owner procedure and device/CI run).