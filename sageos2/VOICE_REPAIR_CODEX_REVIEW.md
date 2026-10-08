# Codex review of PR 65

Reviewed parent: 99036db6b8d5c8b35a33b0ab53321eeaa85f2ce7.
Installed build remains 219. This review does not deliver microphone self-repair or a new APK.

## Corrected in this review

- The owner's exact request, `fix your hearing`, and polite variants are local repair intents. Repair intents take precedence over personality responses.
- The responder cannot claim a test/repair started without a connected controller. Its default response explicitly says the workflow is not connected and nothing changed. A controller entry callback is now available; the host deliberately does not connect an unfinished controller.
- Fixed nonexistent SpeechRecognizer import; restored PendingSpeech removed by the reset patch; declared the reset interface on AndroidSpeechPort. Reset refuses a live command capture and ignores shutdown instances. Android compilation/behavior still needs CI.
- Session ownership rejects retired callbacks and terminal-state revival, refuses a second simultaneous session, enforces deadlines on reads, and requires matching retest evidence after a repair before SUCCESS. No timer or persistence is implied by this manager.
- Silence and wrong words are not reasons to blindly reset a recognizer.
- Removed the misleading test claiming test utterance isolation from an assertion that unrelated text simply returns null. Isolation still needs a real integration test.

## Actual validation

The first compile exposed the nonexistent SpeechRecognizer import. After correction, Kotlin host compilation and JUnitCore execution passed 95 tests: 19 voice-repair tests and 76 existing runtime/task tests. Tests include the production command router selecting LOCAL_SAGE for the exact owner request. This is not an Android Gradle/CI run and does not compile AndroidSpeechPort or SageRuntimeHost. No microphone or tablet acceptance was performed.

## OpenCode continuation: implement, do not stop at this review

Integrate the Codex review branch into opencode/voice-self-repair while preserving other work. Continue PR 65 against release/sage-219; do not publish this intermediate state as an update.

1. Implement the capture/repair/retest controller and connect startRepair only when it can actually run. Reserve an idle runtime window, defer microphone changes until after the local response, and prevent wake or normal turns from taking the diagnostic microphone. Busy requests must explain the blocker.
2. Capture the owner's expected phrase in a dedicated diagnostic input path. Route all test results directly to that session, never through normal command routing, including command-shaped phrases. Cover this with an integration test.
3. Replace the fire-and-forget reset API with a correlated completion/failure contract on the main thread. Keep generation checks, bounded timeouts, one attempt, cancellation, shutdown guards, resource release and listening-mode restoration. Do not count reset requested as reset completed.
4. Persist interrupted/unverified sessions and a bounded history. Perform the same-phrase retest; report actual words/error/backend/timing. Export only explicit test evidence, without putting transcripts into ordinary traces.
5. Run the complete fault -> real adapter reset -> retest test path, late callbacks, cancellation, timeout, busy admission and restart cases. Run Android compilation and full CI; host-only tests do not cover host/adapter wiring.

The manager's SUCCESS guard checks supplied evidence. It does not prove the adapter ran. That proof must come from the controller/adapter integration tests and then the tablet procedure. Keep the existing model, identity, signing and unrelated settings intact.
