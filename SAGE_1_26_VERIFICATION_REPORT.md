# Sage Commander 1.26.0 verification report

Build identity: `1.26.0` / `versionCode 38`  
Package: `com.pineapple.sagecommander.stable`  
APK: `Sage-Commander-1.26.0-reliability-toolbelt-verified.apk`  
APK SHA-256: `46e0cf1d36257653e65f0620713ec0d30878b78c35d0ede390b72fdc306e7687`

## Result

The source compiles, the ARM64 native Brain library builds, the Android APK assembles, and all available host-side regression and packaging checks pass. Physical-tablet audio, camera, MediaSession, local-model performance, and upgrade installation remain owner acceptance checks; they are not represented as having run on this x86 build host.

## Verification matrix

| Check | Result | Evidence |
|---|---:|---|
| Existing regression suite | PASS | All repository regression scripts completed without failure. |
| Conversation state machine | PASS | Executable JVM harness plus source checks. |
| Duplicate final callback | PASS | One dispatch; subsequent final for completed turn rejected. |
| Wake debounce | PASS | Repeated wake callback produces one accepted wake. |
| Stale recognizer generation | PASS | Stale generation rejected before command selection. |
| Media-source authorization | PASS (host logic) | Active playback requires wake or push-to-talk; physical audio remains tablet acceptance. |
| Echo rejection | PASS (host logic) | Recent normalized Sage TTS fingerprint rejected and diagnostic reason asserted. |
| 20-turn conversation sequence | PASS (harness) | Twenty consecutive authorized turns completed without reopen loop. |
| Brain load/generation/cancel/timeout/fallback | PASS (host logic) | Watchdog and routing suite passed 15 checks; runtime model benchmark remains tablet-only. |
| Semantic targets/drawer/grid/numbers | PASS | Identity revalidation, churn stability, grid refinement, and one-container fallback tests passed. |
| Package Inspector | PASS | Source/behavior checks cover identity, hashes, signer data, comparison, and Android-owned install handoff. |
| QR Scanner | PASS (build) | Google Code Scanner integration compiles; camera behavior remains tablet acceptance. |
| File Hasher | PASS | SHA-256, size, copy, share, and cancel controls verified. |
| Network scope/cancellation | PASS | Private local subnet only, maximum `/24`, bounded workers, conservative timeout, and cancellation verified. |
| Media Inspector | PASS (build) | Direct MediaSession controls compiled; physical sessions remain tablet acceptance. |
| Voice Command Tester | PASS | Deliberate final remains inert until Execute. |
| Java compilation | PASS | `compileReleaseJavaWithJavac`, Gradle build successful. |
| Native Brain build | PASS | ARM64 `libsage-brain.so` generated and packaged. |
| Android lint | PASS | 0 errors, 157 warnings. Warnings are retained honestly, predominantly localization/resource and API hygiene items. |
| APK assembly | PASS | Non-debuggable Gradle `assembleRelease`, build successful. |
| Signing | PASS | APK Signature Scheme v2; one signer. |
| Package/version | PASS | Package exact; version name `1.26.0`; code `38`; minimum SDK 26; target SDK 35. |
| ZIP integrity | PASS | `unzip -t`: no errors. |
| ZIP alignment | PASS | `zipalign -c -v 4`: verification successful. |
| SHA-256 | PASS | Digest recorded above and in `SHA256SUMS`. |
| Clean reconstruction | PASS | Applying the release patch to the preserved 1.25.0 baseline reproduced the final source tree exactly, excluding external/build assets. |

## Continuity and update identity

- The stable package identifier is unchanged.
- The APK signer matches the preserved continuity keystore exactly; the public report intentionally omits the certificate digest.
- Wake profile storage, Red Queen, memory, Workbench, repair, accessibility, and Sage Forge continuity classes and routes remain present and their regression checks pass.
- The APK is a non-debuggable release build. The same supplied permanent owner signing identity is explicitly wired to the release variant. It has not been installed or published.

## Native artifact

- Architecture: ELF 64-bit ARM AArch64
- Packaged stripped library size: 4,982,848 bytes
- Packaged stripped library SHA-256: `5c0073a533634fd48a0418e6ced4c08183da846313628d6a65b1c8484b2f0f77`
- Unstripped build library SHA-256: `ce3aa7a32c6c2348e840a7fc8e7c082a569496b3de048cdf5196a5b35f9aca3a`
- llama.cpp source pin: `d73c1d6b22a2d3ecc74c2c9cde354015ee72e862`

## Test totals visible in the suite

- New Sage 1.26 reliability/Toolbelt checks: 43
- Brain watchdog checks: 15
- Stabilization tests: 14
- Wake-profile source checks: 26
- Wake-profile behavior checks: 14
- Additional Forge, Android compatibility, continuity, repair, Workbench, and release suites: all passed

Raw verification logs are included in the `verification` directory beside this report.
