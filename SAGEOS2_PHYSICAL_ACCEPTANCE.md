# SageOS 2 physical tablet acceptance

This runbook is for the **first meaningful signed SageOS 2 candidate** on the VASOUN L10_T05. It is not a version carousel. If a hardware-specific defect is found, repair the same `sageos-2` codebase, rerun CI, and produce another candidate only after the defect is understood.

## Before touching the tablet

The candidate is eligible only when all of these are true:

- ordinary `Verify SageOS 2` is green for the application source;
- signed-candidate workflow is green;
- package is `com.pineapple.sagecommander.stable`;
- versionCode is greater than the installed 1.x version;
- signing certificate is the known 2026 release certificate under the proven Android 13 lineage;
- APK contains arm64 `libsage-brain.so`, sherpa JNI, and ONNX Runtime;
- verification report and SHA-256 are preserved with the APK;
- the APK is a signed release candidate, **not a debug APK**.

## Important first-install rule

**Do not uninstall the currently installed Sage first.**

The first test is whether Android accepts SageOS 2 as an in-place update under the existing signing lineage. Uninstalling first would erase exactly the app-private identity/data/model continuity this test is meant to preserve.

If Android refuses the update, stop at the refusal screen and record the exact message. Do not uninstall, clear data, factory reset, unlock the bootloader, or improvise around the error.

## Timed-screen warning

The normal APK acceptance pass should not require a timed bootloader screen.

Any later step involving reboot-to-bootloader, recovery, unlock confirmation, countdown, auto-selection, or destructive confirmation is a **different SageOS system-image stage**. Warn the owner before that screen appears and give the exact selection in advance.

## Pass 1 — installation and continuity

1. Note the currently installed Sage version.
2. Install the verified SageOS 2 signed APK as an update.
3. Confirm Android does not ask to uninstall the existing package.
4. Launch Sage.
5. Confirm the SageOS 2 cockpit opens.
6. Open **Health**.
7. Confirm every capability is reported truthfully as ACTIVE / AVAILABLE / UNAVAILABLE rather than assumed.
8. Root should remain unavailable on this normal APK unless the SageOS system daemon has actually been installed later.
9. Check whether the existing private Brain model was preserved.
10. If Brain reports the GGUF is missing, use **Import brain model** once. Do not repeatedly reinstall the APK to solve a missing model.

## Pass 2 — text and local Brain

Use text first because it removes microphone/TTS variables.

1. Send a simple factual message.
2. Confirm a local Brain response appears in Chat.
3. Send at least five varied text turns.
4. Confirm typed replies remain silent rather than unexpectedly speaking.
5. Confirm conversation history remains visible.
6. Open Sage Core and verify the expected twin identity / owner content survived or is editable.
7. Close Sage normally, reopen it, and confirm history/Core continuity.

If the Brain hangs, errors, or disappears, run **Share diagnostic report** before changing anything.

## Pass 3 — push-to-talk

1. Tap **Push to talk**.
2. Speak one short command.
3. Confirm recognition closes cleanly after the command.
4. Confirm Sage answers through the same runtime/history used by text.
5. Repeat several times, including one deliberate silence and one recognition error.
6. Verify Sage returns to wake-only state instead of getting stranded in command listening.

## Pass 4 — wake handoff

Repeat this sequence several times, not once:

1. Say **Sage**.
2. Confirm Sage says **Yes**.
3. Speak the command without repeating the wake word.
4. Confirm wake capture yielded the microphone before command recognition began.
5. Confirm the response completes and Sage returns to wake-only listening.

Then test the busy acknowledgement:

1. Start a deep Brain turn.
2. While Sage is still thinking, say **Sage**.
3. Confirm the lightweight thinking acknowledgement occurs.
4. Confirm the original Brain turn continues rather than being cancelled or duplicated.

## Pass 5 — modes

1. Say **sage glitch**.
2. Confirm Red Queen activates as a mode/profile of the same Sage.
3. Confirm history, Sage Core, and twin continuity remain the same identity.
4. Return to the normal Sage profile from the Modes panel.
5. Confirm there is no second memory/personality silo.

## Pass 6 — deterministic Android control

After confirming Accessibility is genuinely ACTIVE:

1. Open an Owner App by its owner-defined alias.
2. Back.
3. Home.
4. Recents.
5. Notifications.
6. Quick settings.
7. Scroll and swipe in a harmless screen.
8. Adjust volume.

These actions should not wait for the LLM.

## Pass 7 — recovery

1. Start a long/deep turn.
2. Interrupt Sage by closing the app or allowing Android to kill/recreate the process.
3. Reopen Sage.
4. Confirm unfinished work appears as recoverable rather than silently vanishing.
5. Confirm no previous privileged/device side effect automatically replays.
6. Resume the task explicitly from Tasks.

Then perform one ordinary tablet reboot and repeat the continuity check.

## Pass 8 — diagnostics escape hatch

Test this while the Brain is healthy first:

- type or say **share diagnostic report**;
- confirm Android's share sheet opens;
- inspect the report;
- confirm it includes runtime/Brain/wake/capability/task/trace health;
- confirm it does not dump conversation contents, Sage Core contents, Owner App details, or Chicken Tonight authorization details by default.

This command must remain deterministic so it can still work when the Brain is unhealthy.

## Pass 9 — Chicken Tonight trigger contract

Without a usable stored scope:

1. Wake Sage normally.
2. Say **Do you feel like chicken tonight?**
3. Confirm there is no spoken reply.
4. Confirm the workflow does not become active without usable scope.

With an appropriate stored test scope:

1. Repeat the exact trigger.
2. Confirm the trigger remains silent.
3. Confirm only the configured workflow/scope becomes active.
4. Confirm the trigger does not behave as blanket permission for unrelated root/network actions.

## Pass 10 — background survival

Do not judge wake reliability only during the first minute after launch.

- lock/unlock the tablet;
- use other apps normally;
- leave Sage in the background;
- verify wake still works after ordinary Android background management;
- verify no duplicate foreground runtime is created;
- verify battery/background handling does not cause a wake/restart loop.

If wake fails later but worked immediately after launch, collect the diagnostic report before force-stopping/reinstalling.

## Root / SageOS system-image boundary

Passing this APK run does **not** mean Sage has root.

The VASOUN L10_T05 evidence currently shows a locked production boot chain with green Verified Boot. The later SageOS promotion therefore requires a separate device-specific process that establishes:

- exact bootloader/unlock mechanism;
- exact partition map;
- slot/A-B status;
- AVB/vbmeta relationships;
- recovery/fastboot/download-mode behavior;
- a verified stock-restoration path;
- a staged/rollback plan;
- the platform-signed Sage app + `sage_rootd` + SELinux policy inside the system image.

Do not substitute Shizuku, a generic `su` wrapper, or an unrestricted LLM-as-root process for that design.

## Failure handling

For any failure:

1. Stop changing unrelated settings.
2. Record the exact visible symptom and clock time.
3. Use **Share diagnostic report** if available.
4. Preserve the report.
5. Identify the failed layer: installation / model / wake / recognizer / Brain / TTS / capability / continuity / Android lifecycle.
6. Repair that layer in the same `sageos-2` branch.
7. Add a regression test or CI assertion where possible.
8. Re-run automated gates before another physical candidate.

The acceptance criterion is repeated real-tablet behavior, not merely a successful APK build.
