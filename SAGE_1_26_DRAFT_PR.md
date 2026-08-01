# Draft pull request: Sage Commander 1.26.0 reliability and Toolbelt

## Title

`Sage Commander 1.26.0: deterministic speech turns, Brain health, layered targets, and Toolbelt`

## Base and scope

- Repository: `superkat13/JE`
- Intended base: `agent/sage-continuity-v1-24`
- Version: `1.26.0`
- Version code: `38`
- Package: `com.pineapple.sagecommander.stable`

This is draft text only. No branch, commit, push, or pull request was created.

## Summary

- Replace speech lifecycle flags with a turn-owned state machine that rejects duplicate/stale callbacks, debounces wakes, gates media-time capture, fingerprints Sage TTS, and keeps push-to-talk available.
- Add persistent Brain health states, deterministic test UI/voice route, coalesced preload, distinct load/generation watchdogs, native cancellation, and labeled fallback.
- Prioritize semantic screen targeting, then stable target drawer, coarse/refined grid, and a single-container numbered fallback with identity revalidation.
- Add six functional Sage Toolbelt utilities in Workbench: Package Inspector, QR Scanner, File Hasher, Network Snapshot, Media Inspector, and Voice Command Tester.
- Retain update identity and all Sage 1.25 continuity surfaces.

## Verification

- All existing and new regression scripts pass.
- New 1.26 checks: 43; Brain watchdog checks: 15; stabilization: 14; wake source/behavior: 26/14.
- Java compilation and ARM64 native Brain build pass.
- Android lint: 0 errors, 157 warnings.
- APK assembly, v2 signature, package/version, ZIP integrity, alignment, and SHA-256 checks pass.
- Physical-tablet acceptance remains required for acoustic echo/media behavior, 20 live turns, QR camera flow, MediaSession control, and local-model benchmarks.

## Risk and rollback

- Recognition and accessibility lifecycles are high-impact; keep the physical acceptance checklist as a merge gate.
- The QR flow depends on compatible Google Play services.
- The APK is a non-debuggable release variant and uses the preserved owner signing identity.
- Rollback should use the owner's known-good signed APK through Android's supported update/downgrade policy; never uninstall if preserving app data is required.

## Reviewer checklist

- [ ] Inspect the unified diff and release reconstruction patch.
- [ ] Review media authorization and TTS fingerprint rejection.
- [ ] Review load-coalescing, timeout stages, and cancellation.
- [ ] Review semantic identity revalidation and stale-screen refusal.
- [ ] Review installer/link/network confirmation boundaries.
- [ ] Complete physical-tablet checklist before merge or release.
