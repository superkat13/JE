# Sage Commander 1.25.0 + Sage Forge 0.1 verification

Verified locally on 2026-07-31 from branch `agent/sage-continuity-v1-24`. No commit, push, merge, release, publication, installation, or device-permission change was performed.

## Release identity

| Check | Verified value | Result |
|---|---|---|
| Android package | `com.pineapple.sagecommander.stable` | Pass |
| Version | `1.25.0` | Pass |
| Version code | `37` | Pass |
| Minimum Android | API 26 | Pass |
| Target / compile SDK | API 35 / API 35 | Pass |
| Signers | 1 | Pass |
| Certificate continuity | Candidate signer exactly matched the preserved continuity keystore; public digest omitted | Pass |
| Previous-candidate certificate | Exact SHA-256 match | Pass |
| Brain library | `lib/arm64-v8a/libsage-brain.so`, 4,981,016 bytes | Pass |
| Signed APK size | 77,845,951 bytes | Pass |
| Signed APK SHA-256 | `8115109c841fe279c51318050ffcf91cb421c1b47a10cb7c55159f325839da3b` | Pass |

The package, signer, and version continuity are compatible with an Android replacement install. A physical install-over test was not possible because no Android device or emulator was connected; that check remains a tablet acceptance item.

## Executed verification

| Verification | Actual outcome |
|---|---|
| Python syntax compilation | Pass for all Forge modules, patch generators, and source tests |
| Existing Sage source regressions | Pass: all 13 `sage_tests/test_*.py` programs against the generated application tree |
| Forge TLS/service/security suite | Pass: 8/8 tests, including a real TLS `system.info` job and revocation |
| Deterministic reconstruction | Pass: independently regenerated Java, manifest, resources, and Gradle source are byte-identical |
| Java compilation | Pass: `:app:compileDebugJavaWithJavac` |
| Gradle Android unit tests | `:app:testDebugUnitTest` completed as `NO-SOURCE`; this repository contains no Gradle/JUnit unit-test sources |
| Android lint | Pass with no errors and 105 warnings |
| Native Brain build | Pass: CMake/NDK `RelWithDebInfo`, arm64 Sage Brain present |
| Release assembly | Pass: `:app:assembleRelease` |
| Release signing | Pass: APK Signature Scheme v2 and v3, one expected signer |
| ZIP integrity | Pass: `unzip -t` reports no errors |
| 16 KiB APK alignment | Pass: `zipalign -c -P 16 -v 4` |
| Manifest identity | Pass via `aapt dump badging` |
| Forge application inclusion | Pass: manifest contains non-exported `SageForgeActivity`; DEX contains client/activity/store and `system.info` markers |

## Honest lint totals

| Lint ID | Count |
|---|---:|
| `SetTextI18n` | 87 |
| `ObsoleteSdkInt` | 10 |
| `ApplySharedPref` | 4 |
| `ChromeOsAbiSupport` | 1 |
| `CustomX509TrustManager` | 1 |
| `DefaultLocale` | 1 |
| `MissingApplicationIcon` | 1 |
| **Total** | **105** |

`CustomX509TrustManager` is the Forge leaf-certificate pin verifier. The Android client does not replace the hostname verifier, so normal HTTPS hostname/SAN verification remains active in addition to the exact SHA-256 pin. The other warnings pre-existed or are non-blocking localization/compatibility work; none was relabeled as zero issues.

## Forge evidence

- Pairing uses a 30–900 second one-use window, an eight-digit random code, five-attempt cap, TLS 1.2+, exact certificate pin, and normal Android hostname verification.
- Forge stores only a SHA-256 token digest. Commander stores the token under Android Keystore AES-GCM.
- Authenticated calls require an active token, a timestamp within ±120 seconds, and a durable one-use nonce.
- The real vertical slice calls Python platform/socket/disk APIs on the Forge host. It executes no shell, returns structured JSON, persists progress/logs/results in SQLite, supports cancellation, reconnect polling, interruption marking, and revocation.
- Unknown tools, missing approval, invalid tool output, command/shell/script/executable fields, executable AgentSpec fields, requested but unregistered tools, reused pairing windows, pin mismatch, and revoked tokens are covered by passing tests.

## Build limitation

The signed candidate uses the repository's preserved Pineapple Juice Studio signing identity. Its private key was used only through the existing configured keystore. No key material is included in the release artifact or reports.
