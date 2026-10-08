# Sage Forge Dell/tablet acceptance checklist

Use only the owner's Dell and trusted private LAN.

## Install continuity

- [ ] Record installed Sage package, version code, and signer before installing.
- [ ] Install the candidate through Android's normal confirmation UI without uninstalling Sage.
- [ ] Confirm package remains `com.pineapple.sagecommander.stable`, version `1.25.0` / code `37`.
- [ ] Confirm existing memories, wake profiles, Brain settings, Red Queen, diagnostics, translations, lessons, media responses, appearance, and tablet controls remain present.

## Pairing

- [ ] Give the Dell a stable private address and limit any Windows Firewall rule to Private profile/local subnet.
- [ ] Generate the Forge identity for that exact IP/DNS name; protect the private key.
- [ ] Start Forge with a five-minute pairing window and compare the full 64-hex certificate SHA-256 on both devices.
- [ ] Confirm a wrong pin is rejected and a wrong code is rejected.
- [ ] Approve the pairing sheet and confirm the code cannot pair a second device after success.
- [ ] Close/reopen Sage and confirm the paired state reconnects without entering the token again.

## Real system-information job

- [ ] Review the exact `system.info` target, permission, returned fields, and reversibility in Sage's confirmation sheet.
- [ ] Approve once and observe queued/running/completed state, progress, and structured logs.
- [ ] Confirm the returned hostname/OS/CPU/storage/address data matches the Dell and is stored on the tablet.
- [ ] Start another job and press Cancel immediately; confirm the UI reports cancellation or an already-completed terminal result, never a stuck spinner.
- [ ] Stop Forge during an active request, restart it, and confirm the job becomes `interrupted` rather than silently rerunning.

## Revocation and safety

- [ ] Revoke from the tablet while Forge is reachable; confirm the tablet deletes local trust only after Forge confirms.
- [ ] Confirm subsequent job/status calls are denied and re-pairing requires a new Dell-local code.
- [ ] Confirm no shell, arbitrary command, public scan, permission grant, install, source change, or hidden background operation is exposed.
- [ ] Review Forge SQLite/log retention and remove test data only through an owner-approved future maintenance procedure; do not delete production data as part of this acceptance pass.

## Existing regressions

- [ ] Number markers survive same-app content/window churn, returning to Sage, and follow-up cancellation.
- [ ] Markers clear on click, scroll, package navigation, successful selection, manual clear, and configured timeout.
- [ ] Voice final results outrank stale partials; one low-quality retry occurs without repeated beeps.
- [ ] `Remember that my dog's name is …` saves/acknowledges once, survives restart, recalls deterministically, rejects duplicate echo, and clears fully with `clear memory`.
