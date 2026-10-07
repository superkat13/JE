# Voice repair progress

Status: Merged codex/voice-repair-review (73215cc) into branch. Review corrected routing/readiness/guards and added review doc. Next: implement capture→repair→retest controller and connect properly, with correlated completion/failure contracts and explicit test routing.

Base: release/sage-219 at 1498d57a3896be072e0d4ae360be7c76a5935512
Branch: opencode/voice-self-repair
Current SHA: b93e0e1
Draft PR: https://github.com/superkat13/JE/pull/65 (targeting release/sage-219)

Completed (merged review):
- Routing/readiness corrections in VoiceRepairResponder; clearer "workflow not connected" behavior
- Session manager guards for retired callbacks/terminal-state revival, one-session rule, deadlines, retest evidence before SUCCESS
- AndroidSpeechPort fixes (reset interface, PendingSpeech preserved, guards)
- Policy updated; tests adjusted

Next (per Codex review):
1. Implement capture/repair/retest controller; connect startRepair only when runnable; reserve idle runtime window; defer mic changes; prevent wake/normal turns from taking diagnostic mic; explain busy blockers.
2. Dedicated diagnostic input path for expected phrase; route test results to that session only (never normal routing); add integration test for command-shaped phrases isolation.
3. Replace fire-and-forget reset with correlated completion/failure contract on main thread; generation checks, bounded timeouts, one attempt, cancellation, shutdown guards, resource release, listening-mode restoration; do not count reset requested as completed.
4. Persist interrupted/unverified sessions + bounded history; perform same-phrase retest; report words/error/backend/timing; export only explicit test evidence (no transcripts in ordinary traces).
5. Add integration tests for fault→real adapter reset→retest, late callbacks, cancel/timeout, busy admission, restart; run Android compile + full CI where possible; document actual results.

Tests: existing voice-repair unit tests included. Hardware evidence: none yet. Blockers: none.
