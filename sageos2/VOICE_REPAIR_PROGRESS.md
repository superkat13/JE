# Voice repair progress

Branch: codex/voice-repair-host, based on opencode/voice-self-repair at 3b4340e4e50bb147b9779e715a53a542a38a7296.
Draft PR: https://github.com/superkat13/JE/pull/76 (target opencode/voice-self-repair).
Implementation checkpoint: 5c6a2cb9c2be603ca664e35dac1b353eb11c0478.

Completed host work:
- Local request opens dedicated expected-phrase input; it never enters normal command routing.
- Deferred, bounded idle admission waits for the local reply to finish. Runtime input and recovery admission are reserved during capture/reset/retest.
- Visible speak prompts, controller cancellation, Activity interruption, actual recognized words, backend/error/timing evidence, completed repair and retest results.
- Private diagnostic-only persistence, interrupted/unverified restart recovery and ten-session history; explicit local document-picker export includes test words and evidence.
- Added workflow, runtime isolation and persistence tests; added suites to the lab and enabled the existing full Android CI workflow for this PR target.
- OpenCode-owned adapter, Android speech port, repair-capable interface and adapter tests untouched. VoiceDiagnosticPort, identity, models, signing and owner data unchanged.

Actual validation:
- git diff --check passed at the first implementation checkpoint.
- Local integration lab could not start: repository gradlew is not executable, and no installed Gradle was present.
- Downloaded Gradle through the existing bootstrap; local compileDebugKotlin failed because its Java 25 Gradle daemon disappeared before compilation completed. No local compilation/test success claimed.
- First CI run failed at compileDebugKotlin: an IntRange takeLast call was invalid. Corrected it to takeLast on a List. No tests executed on this attempt. Run: https://github.com/superkat13/JE/actions/runs/38284930544. Integration lab runs before full testDebugUnitTest, lintRelease and assembleDebug.

Concrete integration dependency:
Latest fetched OpenCode branch still contains scaffold VoiceDiagnosticAdapter.capture (no microphone capture). This prevents real adapter fail/reset/retest evidence. No adapter implementation substituted here. Device acceptance NOT RUN.

Exact next step: inspect CI job 114930902012, fix production/test failures, then fetch opencode/voice-self-repair again and integrate OpenCode's completed adapter when available. Record final CI totals and final SHA here.

Short tablet procedure (after completed adapter integration): type “fix your hearing”, enter a short expected phrase in the separate test box, tap Start test, speak only when prompted, repeat for a prompted retest, inspect both recognized transcripts and repair evidence. Cancel a second test and confirm normal Talk works again. Save the explicit local test export if unresolved. This procedure has not been run on hardware.
