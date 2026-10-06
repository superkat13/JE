# OpenCode implementation handoff: one real voice-repair loop

## Ownership and starting state
Owner authorized OpenCode to carry sustained implementation; Codex owns diagnosis, review and release integration. This branch begins at release/sage-219 1498d57a3896be072e0d4ae360be7c76a5935512 (PR #64). Build 219 is installed. Preserve unrelated work. Implement here or on a child branch; draft PR targets release/sage-219. Do not merge sageos-2 or publish another numbered APK; Codex coordinates release after review.

Current implementation status: NOT STARTED. This commit contains the assignment, not a working repair.
Keep sageos2/VOICE_REPAIR_PROGRESS.md current at every meaningful checkpoint: commit, completed work, exact next command/step, test results, unresolved blocker. Commit useful partial work before context/session exhaustion. Read the handoff before resuming; do not restart the investigation.

## Owner goal
Typed conversation must let Sage diagnose a voice failure, execute a supported repair, retest, and report evidence. Deliver one complete loop rather than another health label or settings page. No new model, cloud service, general autonomous coder, identity reconstruction or lab redesign in this assignment.

## Evidence already established
- Build 219 owner self-check and unfinished-task listing work on the tablet.
- Export reports local Sherpa dependencies ready and native Brain available. Recorded voice turns produced nonempty transcripts, but exact words were omitted; recognition accuracy remains unknown.
- Two recorded inference turns took roughly 45–49 seconds for 40 output tokens. This is separate from microphone/recognizer recovery.
- Core revision zero and unconfigured Chicken Tonight are separate blockers, not established causes of speech failure.
- Verified base: 414 CI tests; all 20 configured lab suites present (226 tests subset). No successful self-repair demonstrated on hardware.

## Concrete code seams inspected by Codex
Paths below are relative to sageos2/app/src/main/java/.
- com/pineapple/sageos2/speech/AndroidSpeechPort.kt:
  handleResults receives exact transcript but diagnostic emits only nonempty=true.
  ensureRecognizer reuses an existing same-backend recognizer.
  handleRecognitionError already performs one local-to-Android fallback; preserve this budget.
  stopInput invalidates callbacks and cancels; shutdown sets destroyed=true and closes wake/TTS permanently.
  Do not implement repair by calling shutdown and reusing that instance.
- speech/SpeechPort.kt (under com/pineapple/sageos2/): no repair operation in interface.
- speech/RecognitionSessionGate.kt: token invalidation for stale callback rejection.
- runtime/SageRuntimeHost.kt and runtime/SageRuntime.kt: hook local text routing and deferred actions. Do not re-enter the coordinator from router.resolve; build219 queues recovery until reply finishes for that reason.
- diagnostics/DiagnosticReport.kt: current ordinary export intentionally omits conversation text.
- capability/AndroidCapabilityBroker.kt: Forge status/tool/job plumbing is not proof of Android source edit/build/install capability.
- maintenance/SageSelfCheckResponder.kt: status-only; leave check-yourself semantics unchanged.

## Required behavior
1. A local typed request such as "diagnose my voice" or "fix my hearing" starts a bounded voice-repair session, including when Brain is unavailable. Explain what will happen and offer a visible cancel.
2. Ask the owner to type the expected short phrase once and speak it during the visible test. Show what was actually recognized alongside expected text, recognizer/backend errors and elapsed times. A test utterance must never execute as an ordinary device command or tool action (including phrases such as "delete..." or "send...").
3. Correlate one session through capture, final transcript/error and completion. Separate no-speech, wrong transcript, recognizer lifecycle failure, missing permission/model and slow downstream inference. Do not infer correctness from nonempty=true or claim a missing Core explains ASR.
4. First repair actuator: reset/recreate an unhealthy command recognizer under existing permission/backend policy, without destroying the usable text channel/TTS host, clearing data, installing anything, altering models or changing calibrated microphone settings. Apply only for supported lifecycle/start faults, not automatically for every transcript mismatch.
5. Reset runs on Android's required thread, invalidates prior session callbacks, releases resources once, is bounded by timeout/cancel and cannot interrupt unrelated active work silently. Reconcile desired listening mode after success/failure. A late repair callback cannot reclaim a successor's microphone.
6. Run the same phrase check after the repair. Report success only from the retest. Reset completion alone = attempted, unverified. If no live microphone sample is possible in tests, explicitly distinguish injected fault recovery from device acceptance.
7. If mismatch persists or no supported repair applies, say the specific unresolved cause/evidence, stop retrying, and prepare ONE local export for development. Include expected/recognized test text only in that explicit test export; keep normal diagnostics redacted. No ambient/raw audio recording, no credential capture, no automatic network upload.
8. Session state survives an app interruption as interrupted/unverified; never label it repaired or blindly repeat a repair on restart. Keep a small bounded repair history. Do not persist arbitrary conversation beyond the explicit test.
9. Present human language: what failed, what Sage actually changed, what retest observed, what remains blocked. No IDs or terminal commands for the owner.

## Acceptance gates
- Reproduce a recoverable recognizer lifecycle/start failure before the change with a realistic injected adapter fault.
- Drive the production repair orchestration through fail -> actual adapter reset/recreation -> successful recognition result -> verified outcome. Merely flipping a status boolean fails acceptance.
- Verify wrong transcript stays mismatch, reset without retest stays unverified, missing permission/model produces a concrete blocker, repeated failure stops after one repair attempt.
- Verify cancellation, deadline, late callbacks, busy runtime admission, restart interruption, and test phrases never reaching normal actions.
- Verify local entry and reporting with Brain unavailable.
- Include new suites in run-self-reliance-lab.py; run full Android suite through the configured CI path (target release/sage-219 does not itself trigger sageos-2-only PR workflows). Use existing authorized dispatch if available; otherwise provide exact compile/test results and leave final CI to Codex. No claim of full CI based on a custom classpath.
- Do not dilute/assert away failing behavior. Explain each production defect vs fixture correction.
- Final handoff: branch+SHA, draft PR, before/after evidence, actual test runs, remaining limits, and ONE short tablet procedure.

## Completion boundary
Implement and test the code; do not stop at this document, a plan, a generic recommendation or a diagnostic counter. Continue until a reviewable draft PR exists or a concrete unavailable capability prevents progress. Hardware acceptance remains pending until owner runs the short procedure. A simulated repaired lifecycle fault does not establish that the owner's original recognition problem is fixed.
