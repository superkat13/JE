# Voice repair progress

Status: Implementing capture→repair→retest controller and integration tests. Orchestrator scaffolding added; next: extend orchestrator with correlated reset completion contract and explicit diagnostic routing.

Base: release/sage-219 at 1498d57a3896be072e0d4ae360be7c76a5935512
Branch: opencode/voice-self-repair
Current SHA: fc09563
Draft PR: https://github.com/superkat13/JE/pull/65 (targeting release/sage-219)

Completed:
- Merged codex review (guards, readiness, routing, AndroidSpeechPort fixes)
- VoiceRepairOrchestrator scaffolding added

Next:
1. Extend orchestrator to handle correlated reset completion/failure contract; do not count reset requested as completed; track resetRequested/resetCompleted, generation, timeouts.
2. Add dedicated diagnostic input path + session-scoped routing (test results never go to normal command routing). Add integration test for command-shaped phrases.
3. Implement retest logic with same-phrase verification; require evidence before SUCCESS per manager rules.
4. Add integration tests: fault→real adapter reset→retest, late callbacks, cancel/timeout, busy admission, restart interruption.
5. Run Android compile + full CI where possible; record actual results honestly.
6. Update progress with concrete evidence.

Tests: manager/responder unit tests. Hardware evidence: none yet. Blockers: none.
