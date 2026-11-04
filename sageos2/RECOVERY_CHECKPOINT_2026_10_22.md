# Sage recovery checkpoint — 2026-10-22

## Resume here

Continue the combined work in [draft PR #78](https://github.com/superkat13/JE/pull/78), branch `review/sage-voice-core-integration-20261014`. Its verified starting HEAD is **`bc428f671fde96293e51e7c99d5801776082489c`**. This integrates Core recovery from #75, command-onset correction from #77, the real Android diagnostic adapter, and selected owner-visible hearing-workflow work from #76.

Use one active continuation of #78. Fetch and compare the current remote SHA before pushing. Do not restart older OpenCode/Jules branches, copy #76's scaffold adapter over the working adapter, or treat the old handoff's first failing test as the current implementation state. Use the current PR head and its checks for live verification status. `CORE_RECOVERY_HANDOFF.md`, `VOICE_SELF_REPAIR_HANDOFF.md`, and earlier progress documents remain historical requirements/evidence, not instructions to rebuild completed work.

OpenCode and Jules contributed historical work now present in this lineage, including the recovered Jules adapter. Neither a direct connector nor a runnable OpenCode/Jules CLI is available in this session. Today's independent review uses the available multi-agent coding/audit tools. No new external-agent run is claimed.

## Independently verified starting evidence

| Gate | Evidence at exact `bc428f6` |
| --- | --- |
| Android build, lint and tests | [Verify SageOS 2 run 38849043938](https://github.com/superkat13/JE/actions/runs/38849043938), verify job `117772790336`: success; **748 tests, 0 failures, 0 errors, 0 skipped**. Latest successful attempt completed October 18. |
| Native Brain inference/sanitizers | Same verify run, native job `117772789514`: success. |
| Reproducible signed candidate | [Run 39177305172](https://github.com/superkat13/JE/actions/runs/39177305172), signed job `117823113022`: success on October 18, two independent release builds and payload comparisons; native job `117822522857` also passed. |
| Candidate | Artifact `12155077396`, `SageOS-2.0.0-build-219-candidate`; package `com.pineapple.sagecommander.stable`, versionCode `219`, versionName `2.0.0`. |
| APK bytes / SHA-256 | `42482064` / `9e45047db141d4f07f11cb13b01d2abd2d04589a287340279151dbc0757c8bb7` |
| Artifact ZIP bytes / SHA-256 | `20135948` / `a5986e8db7dd1f86d23218bd8dfef27d450755c9d30180717dce1d4e25a293d5` |
| Android 13+ certificate | `e2e3e2cabd3372d6073643b35dc94b5fb62e32c200f9e236d4b9f1e403f61b6e`; rotation minimum SDK `33`. |
| Android 24–32 legacy certificate | `99e0a7c655cdefb3bb4ac85e5961d19358ee0ffdb3dce9b3a145f9cbcda78d35` |

The downloaded ZIP and extracted APK were independently rehashed on October 22; both match the workflow records. Local copies are in `/workspace/sage-evidence/`. The candidate artifact currently expires November 1. These are candidate files, not evidence of installation or physical success.

Several materially different candidates all display **build 219**. Identify every test result with source SHA and APK SHA-256; a screenshot of `219` cannot establish which fixes are installed.

## What remains unproved

The owner clarified on October 22 that the central problem is **hearing the words but responding or acting incorrectly**. The supplied diagnostic also contains a separate speech-began → recognizer-error-7 turn, before any Brain request, and Core revision 0. It does not contain the original words or answer, so it cannot establish the exact semantic failure. Keep recognition, interpretation, execution and identity recovery as distinct acceptance gates.

The latest recorded physical voice evidence is [PR #71's October 10 report](https://github.com/superkat13/JE/pull/71#issuecomment-6103106133): two trials lost the speech prefix; another returned no speech. Wrong transcripts correctly failed without an unsupported reset. Later patches address premature ready prompts, blanket 400-ms sample discard, and post-acknowledgment command onset. CI does not establish their efficacy on the VASOUN L10_T05 Android 13 microphone/driver/native recognizer.

No physical acceptance of the combined `bc428f6` candidate is recorded in the inspected threads. Installed package/checksum/signing compatibility and private Brain/Core continuity remain to be established. [Master issue #21](https://github.com/superkat13/JE/issues/21) was last updated September 24 and cannot describe the October integration state.

The last external code review covered `704474c`, not final `bc428f6`. Thirteen review threads are still open; many refer to earlier code. Later commits address the final review's gated listening assertion and queued Core-resume/hearing-admission deadlock. Reconcile each finding against current code and regression evidence; neither an unresolved flag nor an old green SHA proves today's result.

## Finite recovery plan

1. **Review the source fixes together.** The October 22 continuation below follows independent routing, Brain-context, microphone and Core reviews, with failing-before regression evidence. Keep these fixes together on the active continuation and preserve meaningful assertions.
2. **Verify every changed SHA.** Run focused regressions and full Android/native verification for the resulting commit. Record exact checkout SHA and actual test totals. If source changes, the existing signed `bc428f6` APK no longer represents that source; prepare any replacement through the unchanged release gates only when authorized.
3. **Establish what is on the tablet.** Read installed package/version/signing lineage and APK checksum where accessible, without changing app data. Compare to the selected candidate. Keep a clear stop condition for signature mismatch, uninstall prompts, or incompatible in-place update. Installation remains a separate owner-authorized action under the existing release instructions.
4. **Prove understanding before repeating microphone trials.** Start with a typed multi-part request and confirm every requested result, rather than accepting that the app merely opened. Repeat the same request by voice and compare the recognized words and actual result. Separately check one simple device command, ordinary Brain reply, wake → acknowledgment → command, follow-up, and cancellation followed by a fresh turn. Use the dedicated hearing test only when recognition differs; wait for the actual ready indication. Stop a reproducible failure and preserve its evidence instead of repeating the same trial indefinitely.
5. **Verify identity continuity independently.** Inspect safe Core source status, distinguishing absent/corrupt current, verified history, legacy source, and no recoverable source. Preview and explicitly approve any recovery/import in the owner UI; never invent identity or promote a fallback as restoration. Confirm revision persists after restart, `core_empty` resolves for a valid reason, and the next real local Brain request uses the approved Core. Check history, memories, tasks, wake profiles, owner apps and the existing GGUF remain present.
6. **Close with evidence.** Refresh the active PR/master checkpoint with the final source/APK hashes, automated results, physical route-by-route outcomes and remaining limitations. Keep one agreed candidate and one acceptance record. Merge/release decisions follow the established owner authorization; compilation alone does not close hardware acceptance.

Preserve package/signing lineage, installed data, the private Brain file and untracked `core`. No uninstall, clear-data/reset, model replacement, private-data publication, or speculative repeated repair loop is part of this plan.

## October 22 continuation

The current continuation addresses source defects demonstrated with regressions: compound requests being reduced to a single app-open action, punctuation lost before command execution, clipped action-tool instructions, and missing original-goal/result evidence between reasoning steps. It also closes unsafe Core editor/record-validation paths and corrects unsupported speech-reset classification and incomplete explicit-test exports. Automated verification must cover the final combined commit; these source changes alone do not prove the owner's tablet behavior is repaired.

Diagnostics now identify the compiled source revision, including a dirty marker for local modifications. Unchanged periodic self-care findings still reconcile, but no longer fill the recent trace on every five-minute check. Old signed APK hashes above identify the starting candidate only and must never be relabelled as this continuation.

Before-fix evidence: voice/routing regressions produced 8 assertion failures in 35 tests; original Core source produced 6 failures in 8 SDK28/33 tests; the original runtime failed the new goal-continuation test. These are reproduced defects, not hardware acceptance. Final Android/native checks and candidate hashes belong to the exact current PR head. Requests that exceed the context ceiling remain saved and require a smaller fresh request; neither automatic recovery nor a stale manual selection can execute a shortened stored goal.
