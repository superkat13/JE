# Voice repair progress

Base reviewed: opencode/voice-self-repair at 6e5f22941feed87f45be6e54ed3ed41aebae5ea1.
Target release remains release/sage-219; installed APK 219 has not changed.

## Completed in Codex controller implementation

Replaced the placeholder orchestrator with an executable controller using a dedicated VoiceDiagnosticPort contract. It acquires an idle window, invokes capture, classifies results, requests exactly one reset for supported lifecycle faults, waits for reset completion, and invokes a same-phrase retest. Only a matching retest is verified repair. An initial match is HEALTHY with no repair claimed. Cancellation, scheduled deadline, interruption, duplicate callbacks and retired-session callbacks invalidate ownership and release the adapter.

Validation: Kotlin host compilation succeeded. JUnitCore: 111 tests, zero failures (35 voice-repair tests including 16 controller/adapter contract tests; 76 existing runtime/task tests). These are injected-adapter tests, NOT Android microphone acceptance or full Gradle CI. The earlier two integration scaffold tests have been replaced with actual capture/reset/retest assertions.

## Exact next implementation boundary for OpenCode

1. Implement VoiceDiagnosticPort on the Android side. This is an exclusive diagnostic channel, not a second call to SpeechPort.attach (which would replace the runtime listener). Acquire only an idle runtime/microphone window. Suspend wake during that window. Correlate capture/reset/release callbacks on the main thread and ensure release restores the previous listening mode without stealing a successor's microphone. reset must call actual recognizer teardown/recreation and report completion/failure. Do not map completion to posting a Handler runnable.
2. Connect typed repair entry to expected-phrase input and the controller after the local reply finishes. Show readiness before the owner speaks, a visible cancellation action, actual before/after words/error/timing, and explicit export. Test phrases must never reach SageCommandRouter or normal speech listeners. The current host still has no startRepair callback and must remain honest until this wiring works.
3. Persist interrupted/unverified state and bounded history; the in-memory controller does not implement process-restart persistence. onChanged provides a session snapshot, not proof that persistence occurred.
4. Compile the actual Android adapter and host. Add Android integration tests for listener isolation, actual reset/recreation and mic ownership. Run full CI. Then provide one tablet acceptance procedure. Do not publish this intermediate controller-only state as a numbered APK.

Controller API: startRepair(expectedPhrase) returns false when admission is busy; cancel() and interrupt() release ownership; createExport() returns explicit test evidence. Constructor requires VoiceDiagnosticPort, VoiceRepairSessionManager and RuntimeScheduler; the scheduler must execute its deadline on the same serialized thread as the port. No Brain is required. Expected phrases are immutable within a session (cancel and start another to change one).

No hardware evidence or successful self-repair on the owner's tablet has been claimed. The original ASR accuracy problem remains unverified.
