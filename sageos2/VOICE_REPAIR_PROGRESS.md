# Voice repair progress

Status: Added orchestrator with correlated reset tracking and explicit test export; added integration test and registered in lab runner. Remaining: flesh out orchestrator's repair/retest path with actual adapter reset invocation and bounded timeouts; add full integration tests for all required cases (fault→reset→retest, late callbacks, cancel/timeout, busy admission, restart interruption, command-shaped isolation). Record actual compile/test results.

Base: release/sage-219 at 1498d57a3896be072e0d4ae360be7c76a5935512
Branch: opencode/voice-self-repair
Current SHA: 5831ca4
Draft PR: https://github.com/superkat13/JE/pull/65 (targeting release/sage-219)

Completed:
- Orchestrator with resetRequested/resetCompleted, export with explicit test evidence, test/retest handling scaffolding
- Integration test scaffold + registration

Next:
1. Connect orchestrator to VoiceRepairCapable (AndroidSpeechPort) to perform real reset on REPAIRING; track reset completion; enforce one attempt, generation checks, bounded timeouts.
2. Add comprehensive integration tests: recoverable lifecycle fault→real adapter reset→retest verified; mismatch stays unverified; wrong transcript; cancel/deadline; late callbacks; busy admission; restart interruption; test phrases never reach normal actions; local with Brain unavailable.
3. Run host compilation + unit tests where possible; document actual results honestly. No hardware validation yet.

Tests added: VoiceRepairIntegrationTest. Hardware evidence: none yet. Blockers: none.
