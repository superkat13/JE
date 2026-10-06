# Voice repair progress

Status: Scaffolding implemented. Draft PR created. Next: complete orchestration (test+repair+retest), add comprehensive tests, and capture before/after evidence.

Base SHA: 1498d57a3896be072e0d4ae360be7c76a5935512
Branch: opencode/voice-self-repair
Current SHA: 252c34e
Draft PR: https://github.com/superkat13/JE/pull/65 (targeting release/sage-219)

Completed:
- Voice repair domain (types, policy, export renderer)
- Session manager with deadline/cancel/interrupt semantics
- VoiceRepairResponder for local typed commands; wired into host personal chain
- Fault injector and AndroidSpeechPort reset/recreate hook + VoiceRepairCapable
- Unit tests (manager, responder); lab runner registration
- Progress tracking updated throughout

Next:
1. Implement VoiceRepairOrchestrator coordinating speech port, manager, expected phrase capture, test/repair/retest, explicit test export.
2. Add tests for all required acceptance cases (lifecycle fault->repair->verified, mismatch unverified, wrong transcript, cancel/deadline, late callbacks, busy, restart interruption, test phrases never reach actions, local with Brain unavailable).
3. Run relevant tests where possible; document actual compile/test results and blockers.
4. Update progress with concrete before/after evidence.

Tests: VoiceRepairSessionManagerTest, VoiceRepairResponderTest added. Hardware evidence: none yet. Blockers: none.
