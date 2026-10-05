# Sage self-reliance lab

This lab checks the existing runtime and the direct self-check command. It is a bounded regression lab, not a claim that Sage can repair or update her own code.

## Run

Use the same JDK 17, Android SDK, pinned wake dependencies and pinned llama.cpp setup as `.github/workflows/verify-sageos2.yml`. From the repository root:

```sh
python3 sageos2/tools/run-self-reliance-lab.py
```

If Gradle is not on PATH, pass `--gradle /absolute/path/to/gradle`. The repository wrapper is the fallback. This command uses JVM/Robolectric tests; it does not install an APK or contact the tablet.

The runner forces fresh execution and writes `sageos2/build/self-reliance-lab/result.json`. A missing, stale, empty, skipped, failed or malformed required suite fails the lab. It never converts an unavailable SDK or compiler into a passing result.

## Evidence covered

| Area | Evidence |
| --- | --- |
| Self-check | Real snapshot details reach the response without a Brain generation; normal conversation routing remains available |
| Health continuity | Findings remain durable, clear when resolved, and respect owner cancellation |
| Runtime | Turn sequencing, watchdog release, tool execution, bounded verification, and recovery replay protection |
| Speech policies | Endpoint decisions, recognizer session ownership, wake reconnect and retry bounds |
| Local API | Loopback contract and request ownership |

The report always says physical device acceptance has not run. Fakes and Robolectric cannot prove microphone quality, actual model latency, battery behavior, real service repair, or success on other apps.

## One short device acceptance pass

After an integrated signed candidate is built and verified for an in-place update:

1. In text chat, send `check yourself`. Save its response; compare the named failures with the diagnostic report.
2. Send one ordinary request immediately afterward. Confirm it is a separate turn and receives its own result.
3. Speak the same self-check request; check wake responsiveness again after the reply and after brief backgrounding.
4. Export one diagnostic report if anything fails. Do not uninstall, clear data or manually interrupt an action that could have an external side effect to create a test failure.

No timed reboot or device failure injection is required for this pass.

## Parallel work ownership

OpenCode owns the command-speech failure lab in issue #58: genuine no-match, no-audio boundary, timeout, cancellation, stale callbacks and capture cleanup. That issue is queued; it is not proof that an OpenCode process is running. Preserve `MAX_UTTERANCE_MS=15000`, modelType, identity, signing and the 217 recovery guard.

Codex owns the direct self-check response and this lab runner. The self-check reports current state and unfinished work. It does not claim to repair a condition merely by reading it. Existing bounded recovery mechanisms remain separate.
