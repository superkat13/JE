# Voice repair progress

Status: Added voice repair domain (types + policy). Next: implement VoiceRepairSessionManager (state machine with bounded steps, deadline/cancel, late callbacks, restart interruption semantics) and a VoiceRepairResponder to handle typed local commands ("diagnose my voice", "fix my hearing"). Need to ensure we don't re-enter coordinator from router.resolve.

Base SHA: 1498d57a3896be072e0d4ae360be7c76a5935512
Branch: opencode/voice-self-repair
Current SHA: ea8baa2

Completed:
- Domain types (VoiceRepairState, Cause, Action, Session, TestResult, Export) and policy (canApplyRepair, command-like guard, failure set, timeouts/max attempts).

Next:
1. Implement VoiceRepairSessionManager (pure logic, testable) to orchestrate DIAGNOSING/TESTING/REPAIRING/RETESTING with 1-attempt rule, deadline, cancel, restart interruption marker.
2. Implement VoiceRepairResponder (personal responder) that accepts local typed commands and produces replies; respects build219 constraint (no coordinator re-entry). Hooks into speech port for test capture? Or works via adapter.
3. Add injectable repair adapter interface on AndroidSpeechPort (or wrapper) to reproduce lifecycle/start faults and to perform reset/recreate under policy.
4. Wire responder into host chain; ensure test phrases never reach normal actions (guard isCommandLike).
5. Add tests and register in lab runner.
6. Update progress after each step.

Tests: none yet. Hardware evidence: none yet. Blockers: none.
