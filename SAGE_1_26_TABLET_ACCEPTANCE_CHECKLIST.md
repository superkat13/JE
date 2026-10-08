# Sage Commander 1.26.0 tablet acceptance checklist

All items below intentionally remain unchecked until the owner runs them on the physical tablet. Start with a backup and confirm the currently installed certificate digest before approving an upgrade install.

## Identity and continuity

- [ ] Verify APK SHA-256 equals `46e0cf1d36257653e65f0620713ec0d30878b78c35d0ede390b72fdc306e7687`.
- [ ] Verify the installed and candidate certificate digests match exactly; keep the digest in the owner's private acceptance record.
- [ ] Perform Android's normal update flow; do not uninstall the existing app first.
- [ ] Confirm app data, saved wake profiles, Red Queen, memory, Workbench, repair bundles, accessibility settings, and Sage Forge pairing remain present.

## Conversation and speech

- [ ] Say one command once; confirm exactly one executable dispatch in diagnostics.
- [ ] Say “hey sage” once; confirm exactly one accepted wake event.
- [ ] Play a video/podcast/song containing command-like speech; confirm no command runs without a new wake or push-to-talk.
- [ ] Let Sage speak a command-like acknowledgement; confirm diagnostics record an echo rejection and no dispatch.
- [ ] Confirm media pauses/ducks only during authorized capture and restores afterward.
- [ ] Complete 20 consecutive conversation turns without an automatic reopen loop.
- [ ] Confirm push-to-talk works during media playback and after a recognition error.

## Brain

- [ ] Run a cold **Test Sage Brain** and save its full persistent report.
- [ ] Run a warm test and compare load duration and generation speed.
- [ ] Confirm model name/file/quantization/size/hash are correct.
- [ ] Confirm every answer is visibly labeled command engine, tablet Brain, Dell Forge, or fallback.
- [ ] Exercise Cancel and confirm generation stops promptly.
- [ ] Induce a load failure and a generation timeout separately; confirm the exact stage and deterministic fallback.

## Layered screen control

- [ ] Test “Tap Play,” “Open Downloads,” and “Choose the second video” on representative apps.
- [ ] Open target drawer, allow harmless content churn, and confirm identities/ordinals stay stable.
- [ ] Test coarse grid, “Top right,” and “Refine”; confirm final action is identity-revalidated.
- [ ] Show numbers; confirm one stable overlay remains until selection, clear, real navigation, or timeout.
- [ ] Navigate before selecting a stale target and confirm Sage refuses the action safely.

## Toolbelt

- [ ] Inspect a known APK and independently compare package/version/hashes/signer.
- [ ] Approve installer handoff and confirm Android still requires its own confirmation.
- [ ] Scan text and URL QR codes; confirm links require explicit confirmation.
- [ ] Hash a known file; compare SHA-256; test Copy, Share, and Cancel.
- [ ] Confirm Network Snapshot refuses public/non-local scope, cancels immediately, and compares saved snapshots accurately.
- [ ] Confirm Media Inspector reports the real active app/title/state and supported controls work.
- [ ] Record a Voice Command Tester phrase and confirm nothing executes until Execute is pressed.

## Final decision

- [ ] Review Android lint's 157 warnings and accept or remediate them.
- [ ] Record tablet model, Android version, date, tester, and outcome.
- [ ] Explicitly approve or reject commit, push, draft PR creation, installation, and publication as separate owner actions.
