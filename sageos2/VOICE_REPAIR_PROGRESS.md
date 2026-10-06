# Voice repair progress

Status: Domain, session manager, responder, and fault injector added. Next: wire responder into host chain (before/with personal responders), add repair/reset hooks to AndroidSpeechPort (or adapter) to support reset/recreate under policy, and design how to invoke repair when lifecycle/start faults occur. Must not re-enter coordinator from router.resolve.

Base SHA: 1498d57a3896be072e0d4ae360be7c76a5935512
Branch: opencode/voice-self-repair
Current SHA: 225fd3e

Completed: domain types, policy, session manager (deadline/cancel/interrupt), responder for typed commands, fault injector.

Next:
1. Wire VoiceRepairResponder into SageRuntimeHost personal chain (or via router composition) so "diagnose/fix voice" commands are local and Brain-independent. Avoid coordinator re-entry from router.resolve.
2. Add VoiceRepairCapable interface or extend AndroidSpeechPort to support a bounded reset/recreate operation (invalidate sessions, cancel/destroy recognizer once, recreate under same backend policy, reconcile listening mode, bounded by timeout). Do not destroy usable text channel/TTS host.
3. Implement the test+repair+retest orchestration flow (manager + responder + port integration) with explicit test phrase handling.
4. Add tests for the required cases (lifecycle fault -> repair -> verified; mismatch stays unverified if no retest success; wrong transcript; cancel/deadline; late callbacks; busy; restart interruption; test phrases never reach actions; local with Brain unavailable).
5. Register new test suites in run-self-reliance-lab.py.
6. Update progress, commit useful work, draft PR to release/sage-219.

Tests: none yet. Hardware evidence: none yet. Blockers: none.
