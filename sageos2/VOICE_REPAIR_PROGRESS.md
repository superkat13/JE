# Voice repair progress

Status: Core scaffolding in place (domain, manager, responder, fault injector, export renderer, wiring in host chain, tests registered). Need to complete orchestration logic (test+repair+retest flow) and add comprehensive tests covering all required cases. Also update progress with actual implementation details.

Base SHA: 1498d57a3896be072e0d4ae360be7c76a5935512
Branch: opencode/voice-self-repair
Current SHA: 70fa508

Completed:
- Domain types, policy, session manager (deadline/cancel/interrupt, bounded steps)
- VoiceRepairResponder for local typed commands ("diagnose my voice", "fix my hearing", "cancel voice repair") with precedence chain integration
- Fault injector interface and AndroidSpeechPort extended with resetRecognizer + fault injection support
- VoiceRepairCapable interface, export renderer
- Wired VoiceRepairResponder into host personal chain (VoiceRepair -> TaskFollowThrough -> SelfCheck -> personalCommands)
- Added unit tests for manager and responder
- Registered tests in run-self-reliance-lab.py

Next:
1. Implement full orchestration: when a repair session is active, capture expected phrase from user input and coordinate test/repair/retest. Need to handle the flow where owner types expected phrase and speaks it.
2. Add integration between session manager and AndroidSpeechPort for actual reset/recreate on supported faults.
3. Write comprehensive tests covering all acceptance cases (recoverable lifecycle fault -> repair -> verified; mismatch stays unverified; wrong transcript; cancel/deadline; late callbacks; busy; restart interruption; test phrases never reach actions; local with Brain unavailable).
4. Add VoiceRepairOrchestrator to coordinate speech port, manager, and produce explicit test export.
5. Update progress with concrete evidence.
6. Create draft PR targeting release/sage-219 with before/after evidence.

Tests: manager/responder tests added (2 classes). Hardware evidence: none yet. Blockers: none.
