# Sage Core recovery | active engineering handoff

> Historical assignment. Resume from [the October 22 checkpoint](RECOVERY_CHECKPOINT_2026_10_22.md) and the current combined PR #78; implementation and verification have advanced beyond this starting state.

**Owner-approved scope:** Complete real Sage Core persistence and recoverability while avoiding further voice or tablet changes. Read GitHub issue #74 before editing: https://github.com/superkat13/JE/issues/74

**Branch:** `review/sage-core-continuity-recovery`, based on signed/CI-green voice source `c0654c44aac7cf82d596b6485036adb0b7e9c590` (draft PR #73). Do not diverge from this branch or cherry-pick unrelated work. Open a draft PR against `review/voice-command-wake-tail` and keep it draft until verified. Core repair is the sole task.

## First red test already staged

`SageCoreRecoveryRegressionTest.corruptedCurrentCannotOverwriteValidHistoryWhenSaving` reproduces a concrete destructive edge: if `sage_core_v2/current` cannot be decoded, `SharedPreferencesSageCoreStore.current()` silently returns the revision 0 fallback; calling `replace()` then reuses revision 1 and overwrites an intact saved `revision_1`. The new test is expected to fail until fixed. This is an anti-data-loss invariant, not authorization to auto-restore any particular identity.

## Fix as one complete owner-facing system

1. Inspect all existing Core and continuity paths, docs, actual persistence formats and current tests. Build a bounded read-only provenance/status assessment before any mutation, distinguishing missing versus malformed current, valid revision history, prior legacy source, and truly absent history. Public diagnostics contain counts/codes/keys only, never private text.
2. Add validated snapshot recovery with monotonic revisions across damaged current and history, durable/checked two-phase or journaled writes, original bytes preserved on failure and backup, no reused revision keys, restore preview and explicit owner approval. Do not persist the default empty provider merely to hide `core_empty`.
3. Safely assess both Sage 1.33.3 `sage_core/owner_instructions` and `previous_owner_instructions`, plus approved owner continuity packages. Preserve legacy preferences and all other stores. Support previous-only legacy source as a *recovery candidate*, not silent personality assignment. No fabricated facts, no hidden assistant constraints.
4. Make a plain-English Advanced → Identity & continuity screen with safe status/provenance, preview, cancel, restore, review/import owner package, and Copy safe report. Voice and remote connection must not be prerequisites. Keep ordinary Chat unchanged.
5. Integrate genuinely healthy/unhealthy/recoverable states into SelfCare so `core_empty` clears only for a valid saved Core, recoverable history is actionable, and repeated health checks do not spam identical tasks.
6. Prove owner-approved fields persist across restart and reach the next local Brain prompt via `TwinContextRenderer`; don't overstuff token budget. No modification to voice, learned phrases, models, Owner Apps, modes, wake profiles, task history or signing.
7. Run SDK28/33 Robolectric focused scenarios and full `Verify SageOS 2` + native Brain workflow. Fix test failures. Only after green produce reproducible signed candidate matching existing package and cert lineage. Provide commit SHA, run URLs, test totals, artifact ID/size/hash and remaining physical proof. Do not install, merge, uninstall, clear or factory-reset.

**Privacy and safety:** Never copy real Core contents or personal memories into GitHub, logs, CI, PR text or issue comments. No owner device access required for source-side implementation. Owner must review private recovery preview on their tablet later.

**Definition of done:** Proven distinction between missing and corrupt; no historical revision clobber; recoverable sources identified without mutation; explicit owner-approved durable restoration; self-care and actual Brain context agree; full green CI; data-preserving release candidate ready for a later **nonverbal** test. If no real owner-authored source exists, display that fact and offer manual approved import rather than inventing one.
