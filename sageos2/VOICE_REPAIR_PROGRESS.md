# Voice repair progress

Status: Scanning core seams. Identified AndroidSpeechPort internals (destroyed flag, ensureRecognizer recreates on backend change, session token gating, stale callback rejection, fallback logic, shutdown semantics). SpeechPort interface minimal; no repair op. DiagnosticReport omits conversation text (redaction confirmed). Local typed routing via TextSubmitted through runtime/host.

Base SHA: 1498d57a3896be072e0d4ae360be7c76a5935512
Branch: opencode/voice-self-repair
Current SHA: 7e5d4f4

Completed:
- Fetched branch, checked out, read handoff, established progress tracking.
- Inspected AndroidSpeechPort (handleResults emits nonempty=true diagnostic but stores actual transcript; ensureRecognizer recreates recognizer on backend mismatch; sessions invalidate on stop/recreate/fallback; local fallback attempts one time; shutdown sets destroyed and closes resources). Noted: no repair method exists; shutdown destroys instance permanently.
- Confirmed SpeechPort has no repair operation.
- Reviewed DiagnosticReport structure (no conversation text export field). Redaction is the default.
- Located local text submission paths (host.submitText/submitTextIfReady emit TextSubmitted).

Next steps:
1. Read RecognitionSessionGate implementation to fully understand token semantics for late callback rejection.
2. Read full SageRuntime/SageRuntimeHost to see how to insert bounded voice-repair session without re-entering coordinator from router.resolve (build219 gating noted).
3. Add voice repair domain types: session state, repair action, test result, export for explicit test only.
4. Implement VoiceRepairSession/manager and a VoiceRepairResponder (local typed commands like "diagnose my voice", "fix my hearing") that orchestrates bounded test+repair+retest.
5. Add adapter hook to inject lifecycle/start faults for reproducing recoverable failure.
6. Add tests (including lab registration) covering: recoverable lifecycle fault -> repair -> retest verified; wrong transcript mismatch; reset alone not verified; permission/model blocker; cancel/deadline; late callbacks; busy runtime; restart interruption; test phrases never reach normal actions; local entry with Brain unavailable.
7. Update VOICE_REPAIR_PROGRESS.md after each checkpoint.

Tests for new feature: none yet.
Hardware self-repair evidence: none yet.
Blockers: none.
