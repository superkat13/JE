# Command-Speech Repair Handoff — SageOS2 build 213

**STATUS: COMPILED AND TESTED. 36/36 host tests pass. Rebased onto 36c6a08, zero conflicts.**
**COMMITTED as 6c64c32 on `repair/command-speech-213`. NOT PUSHED, NO PR — blocked on auth.**
**STILL NOT RUN ON A DEVICE, and no Android/Gradle build has run (aapt2 is x86-64 only).**
**Two real test defects were found and fixed — see §0.1. This is the first real verification
this repair has ever had.**

The shell is back (see §2, the blocker is resolved). A JDK and a standalone Kotlin compiler were
installed to get real results. Everything below §0.1 is the *original* handoff text, preserved
unchanged as the historical record; where §0.1 contradicts it, §0.1 wins.

### 0.0 Reopening this working environment — the launch command

The §2 blocker was **not** a wedged PTY. `opencode` was being exec'd directly, and that binary is
a **glibc** build while Termux is musl/bionic, so it aborted during dynamic linking before it ever
opened a shell. That is why `read`/`write`/`edit` (in-process) worked while every subprocess spawn
(`echo`, `bash`, `ripgrep`) died — and why §2's diagnosis blamed a `do_wait` on a dead child, which
was a symptom of the loader failing, not the cause.

**Launch opencode through the bundled glibc loader, not directly:**

```sh
cd /data/data/com.termux/files/home
./.opencode/ld-musl-aarch64.so.1 --library-path "$PWD/.opencode" ./.opencode/opencode
```

Verified this session: this command reproduces and `... opencode --version` reports `1.18.33`
(marker `.opencode/.opencode-termux-version` = `1.18.33-0`). Invoking `./.opencode/opencode`
directly still fails with `Error loading shared library libstdc++.so.6` and a cascade of
`Error relocating ... symbol not found` — so the loader prefix is required, not optional.

The loader, `libstdc++.so.6`, and `libgcc_s.so.1` all live in `~/.opencode/`, which is why
`--library-path "$PWD/.opencode"` is what makes the relocation succeed. Do not move or delete that
directory. No other fix (restart, `pkill -f 'bash -l'`, new session) is needed or useful.

**Corroborating evidence found in the repo root:** an untracked 8.7 MB file named `core`, which is
an ARM aarch64 ELF core dump whose `file` output reads `from 'tar -xzf
.../opencode/bin/ripgrep-15.1.0-aa...'`. That is the crashed ripgrep extraction — the direct cause
of §2's `ripgrep execution failed`, i.e. the `glob`/`grep` tool failures. It is left untracked and
must never be committed. On Termux there is also no `/usr/bin/env`, so repo scripts that start with
`#!/usr/bin/env bash` (e.g. `sageos2/tools/fetch-kws-deps.sh`) fail with `bad interpreter` — run
them as `bash <script>` rather than editing the shebang.

### 0.2 Second verified session — real AAR, real Android jar, committed

§0.1 verified the code against **hand-written stubs**. That gap is now closed: the real
dependencies are obtainable locally, so the compile is against the genuine API.

**The AAR is fetchable after all.** §0.1 / Correction 3 said the sherpa AAR "is not on this
machine" and treated restoring it as a prerequisite for CI. It is not a manual drop — the repo
already pins and checksum-verifies it:

```sh
cd /data/data/com.termux/files/home/sage-work
bash sageos2/tools/fetch-kws-deps.sh   # not ./tools/... — no /usr/bin/env on Termux
```

This pulls `sherpa-onnx-1.13.7.aar` plus the KWS model, verifies both against pinned SHA256s
(`c4ef49e3…` AAR, `f170013b…` model), and exports the bpe vocab. It succeeded here, and
`sageos2/app/libs/sherpa-onnx-1.13.7.aar` is now present. It is correctly `.gitignore`d, as is
`sageos2/.deps/` and `sageos2/app/src/main/assets/sherpa-kws/`, so none of it can leak into a
commit.

**Use Kotlin 2.1.21, not an older compiler.** The root `build.gradle.kts` pins
`org.jetbrains.kotlin.android` 2.1.21 and `sageos2` inherits Kotlin through AGP 9.1.1's built-in
support. Compiling the main source set with Kotlin 1.9.24 produces a false failure:

```
AndroidSpeechPort.kt:341:13: error: variable 'ERROR_SERVER_DISCONNECTED_COMPAT' must be initialized
```

That is **pre-existing, correct code** — `ERROR_SERVER_DISCONNECTED_COMPAT` is a `const val`
declared *after* the `LOCAL_BACKEND_FAILURES` initializer that uses it. Kotlin 1.9.24 rejects that
forward reference; 2.1.21 accepts it. Confirmed with a 12-line isolated repro, which fails on
1.9.24 and passes on 2.1.21. Do not "fix" it — it is a toolchain-version artifact, and the real
build is on 2.x.

**Final verification, all four green, on the committed tree:**

| # | Check | Classpath | Result |
|---|---|---|---|
| 1 | 6 speech test/policy files compile | Kotlin 2.1.21 | **exit 0** |
| 2 | 36 unit tests | JUnit 4.13.2 | **OK (36 tests), 0 failures** |
| 3 | **All 99 main source files** (97 kt + 2 java) | real API-35 `android.jar` + **real** `classes.jar` from the AAR | **exit 0, 407 classes** |
| 4 | `SageSherpaRecognitionService.java` | `javac -Xlint:all` + **real** AAR | **exit 0, zero warnings** |

Check 3 is the one that closes §0.1's gap. It also covers `AndroidSpeechPort.kt`, the one edited
file that §0.1 could only audit by eye, because it needs the Android SDK to compile. Check 4 no
longer depends on invented sherpa signatures. Only a single `R` stub was needed (for
`BrainModelImportActivity.kt`, which references `R.string`); its values are irrelevant to
compilation. `android.jar` was taken from the official `platform-35_r02.zip`.

**The Android/Gradle build genuinely cannot run here, and this is now proven rather than assumed.**
CI's sanctioned command is `gradle -p sageos2 testDebugUnitTest lintRelease assembleDebug`, but
AGP 9.1.1 ships `aapt2` for `linux` and `osx` only — there is no Linux arm64 artifact:

| `aapt2-9.1.1-14792394-<platform>.jar` | HTTP |
|---|---|
| `-linux` | 200 |
| `-osx` | 200 |
| `-linux-arm64` | **404** |
| `-linux-aarch64` | **404** |

The `linux` binary is `Advanced Micro Devices X86-64`, and executing it here fails with
`cannot execute binary file: Exec format error` on this aarch64 device. Since both
`testDebugUnitTest` and `assembleDebug` need aapt2 for resource processing (and
`unitTests.isIncludeAndroidResources = true`), the Gradle path is closed on this machine. **CI is
the only place the Android build can be verified**, and per instruction **no release APK was
built** — `assembleDebug` in CI is the project's own debug candidate, not a release artifact.

**Baseline and #46.** `origin/sageos-2` is at `36c6a08`, and local `HEAD` was already on it —
**0 ahead, 0 behind**, nothing to rebase. `36c6a08` "Integrate verified stable Brain identity
prefix" *is* the merge of PR #46, so the Brain repair is already incorporated and needed no
rebasing. As §1 predicted, there is no file overlap with this repair. Note the fetch refspec in
this clone is pinned to `repair/native-wake-model-212`, so `git fetch origin sageos-2` alone does
**not** update `origin/sageos-2`; use
`git fetch origin '+refs/heads/sageos-2:refs/remotes/origin/sageos-2'`.

**Commit `6c64c32`** on `repair/command-speech-213`, on top of `36c6a08`, 7 files / +924 / −22
(the six edits plus this handoff). `core` was deliberately left untracked.

**BLOCKED: push and PR.** `git push` fails with `could not read Username for
'https://github.com'`. There are no credentials in this environment: no `~/.git-credentials`, no
`~/.config/gh/hosts.yml`, no `GH_TOKEN`/`GITHUB_TOKEN` in the environment, and `gh auth status`
reports not logged in. So the branch is committed locally but **not pushed and no PR exists**. To
finish, someone must run `gh auth login` (or supply a token) and then:

```sh
cd /data/data/com.termux/files/home/sage-work
git push -u origin repair/command-speech-213
gh pr create --draft --base sageos-2 --head repair/command-speech-213 \
  --title "Fix command-speech endpoint gate and guard cancelled worker teardown" \
  --body SAGEOS2_COMMAND_SPEECH_213_HANDOFF.md
```

Opening it will also trigger `verify-sageos2.yml` on the PR, which is what actually runs
`testDebugUnitTest` and closes the Android-build gap above. **Watch that run before trusting the
host results** — the host tests cannot see resource processing, dexing, or the native KWS/Brain
CMake build, all of which CI does exercise.

---

## 0.1 Verified session — 2026-09-28, shell restored

### What was actually run, and what it proved

| Check | Command | Result |
|---|---|---|
| 4 policy/gate files compile | `kotlinc 1.9.24` (JDK 17) | **clean, no errors** |
| 36 host tests | `JUnitCore` on 3 classes | **OK (36 tests), 0 failures** |
| Java→Kotlin interop | real `javac` on a harness mirroring lines 204-231 | **clean** |
| Full 396-line service file | real `javac -Xlint:all` against hand-written Android+sherpa stubs | **clean, zero warnings** |
| Rebase onto `36c6a08` | `git stash push -u` → `checkout -B` → `stash pop` | **clean, 0 conflicts** |

The 36 tests are the 32 new ones plus the 4 pre-existing `CommandRecognizerPolicyTest` tests,
which confirm backend selection is unchanged. Everything was re-run *after* the rebase and stayed
green.

Toolchain used, since none of it was present: `pkg install openjdk-17` (17.0.20), Kotlin compiler
1.9.24 from the JetBrains release zip, and JUnit 4.13.2 + Hamcrest 1.3 from Maven Central. The
Gradle path in Correction 2 remains **unusable** on this machine for a separate reason — there is no
Android SDK, no NDK and no `sageos2/app/libs/sherpa-onnx-1.13.7.aar`, so
`:app:testDebugUnitTest` and `:app:assembleDebug` both still cannot run. The stub-based `javac`
route sidesteps the Android dependency entirely, which is why it was worth building.

**What the stubs do and do not prove.** They prove the service file is syntactically valid Java,
that every call into `CommandEndpointPolicy` type-checks, and that the `@JvmStatic` surface
resolves from Java. They do **not** prove the sherpa API behaves as the code assumes, because the
stub signatures were written by reading the call sites, not from the real AAR. A signature that
differs from the real sherpa AAR would not be caught this way. That remains a genuine gap.

### Correction 4 — two tests were wrong, and one was hiding a real inconsistency

The compile passed, so the handoff's central fear did not materialise. Running the tests did, and
it found defects that inspection across two prior sessions had missed.

**Defect 1 — `CommandEndpointPolicyTest.whitespaceOnlyTextIsStillEmpty` was asserting behaviour
the policy did not have.** The test expected `onChunk(true, "   ", true)` to be `FINISH_EMPTY`
("whitespace counts as empty"), but the policy used `!text.isEmpty()`, so `"   "` was
`FINISH_WITH_TEXT`. This is a genuine inconsistency, not merely a bad test: the service classifies
with `onWindowEnd`, and both functions must agree on what "empty" means or a whitespace-only
transcript could be emitted as a real result. **Fixed in the policy, not the test** — `isEmpty()`
became `isBlank()` in both `onChunk` and `onWindowEnd`, so the invariant is now actually enforced.

This is a real behaviour change, narrowly scoped: it only affects text that is entirely
whitespace. In production `clean()` (line 365) already trims and collapses runs before the policy
sees anything, so no shipped path changes outcome. It closes the invariant rather than fixing an
observed bug.

**Defect 2 — `RecognitionSessionGateTest.freshGateAcceptsNothing` asserted a property the gate
never had.** It asserted a fresh gate rejects token `0`, but `current` starts at `0L`, so
`isCurrent(0L)` is `true` by definition. Checked against the pre-refactor original: the old
`recognitionSession = 0L` + `session != recognitionSession` had exactly the same semantics, and
no real turn ever passes 0 because the counter is only ever read after `++recognitionSession`. So
the test was wrong, and "fixing" the gate to satisfy it would have been a behaviour change to a
deliberately behaviour-preserving refactor. **Fixed the test** to assert the property that
actually matters: a fresh gate has no open turn, and the token before the current one is rejected.
`isCurrent(0L)` returning `true` is preserved, matching the original exactly.

### Correction 5 — three stale numbers in the handoff

- The handoff says "30 new tests" in three places. The actual count is **32** (21 in
  `CommandEndpointPolicyTest`, 11 in `RecognitionSessionGateTest`), plus 4 pre-existing
  `CommandRecognizerPolicyTest` = **36 total**.
- The handoff's §3 line counts are now stale for two files after the fixes above:
  `CommandEndpointPolicy.kt` is still 63 lines, but `RecognitionSessionGateTest.kt` is 97 → **106**
  (the doc comment on the corrected test). Everything else is byte-identical.
- The handoff's §1 baseline is stale. Local `HEAD` was `bfd8733` on branch
  `opencode/f0-f1-candidate213`, a **shallow** clone (`.git/shallow` pins `bfd8733`), and it is
  *not* the same commit as `36c6a08`. `36c6a08` is 36 commits ahead of it, and `bfd8733` is an
  ancestor of it. The two speech files are identical across that range, which is why the rebase
  was conflict-free. The repo is now on `repair/command-speech-213` at `36c6a08`.

### One design note, reported not changed

`SageSherpaRecognitionService.hasSpeechEnergy` (line 330) is a private local duplicate of
`CommandEndpointPolicy.hasSpeechEnergy`. The arithmetic matches exactly — 1-in-4 subsample mean
absolute value against `180L`, with the same `Math.max(1, count / 4)` divide-guard — so behaviour is
correct and the `@JvmStatic` copy is currently only exercised by tests. Routing the service through
the shared copy would be tidier, but it changes a file's shape for no behavioural gain, so it is
left alone and noted here.

---

## 0. Resumed session addendum — still blocked, plus three corrections

A later session re-read this handoff and re-verified it against the tree. Nothing has been
compiled or executed. All six files are present and byte-counts match §3 exactly
(63 / 396 / 30 / 347 / 187 / 97 lines), so no edit was lost.

**§2 blocker is unchanged.** The shell is still dead. Re-confirmed this session:
`echo alive; pwd` timed out at 20 s with zero stdout, and `glob`/`grep` still return
`ripgrep execution failed`. `read`/`edit` still work. **Restarting opencode is still the
required user action, and it is still the first thing to do.**

**Correction 1 — a real compile error was found by inspection and fixed.**
`CommandEndpointPolicyTest.kt` line 158 read:

```kotlin
CommandEndpointPolicy.onChunk(endpoint, text = "", began)
```

Kotlin forbids a positional argument after a named one, so this was a hard compile error
("Mixing named and positioned arguments is not allowed") that would have failed the whole
test compilation. It is now all-positional:

```kotlin
CommandEndpointPolicy.onChunk(endpoint, "", began)
```

This is precisely the defect class §4 warned about. It was the only such violation: every
other call in the tree is consistently all-named or all-positional — notably
`startRecognition(capturedTurnId, capturedGeneration, allowLocal = false)` in
`AndroidSpeechPort.kt:290`, which is valid because both positional args precede the named one.
**This is still not a substitute for a compiler.** The rest of the 30 tests remain unverified.

**Correction 2 — the §4 build commands are wrong and would not work.** `:sageos2:app:...` is
not a valid task path. `sageos2/` is its own Gradle root (`rootProject.name = "SageOS2"`,
`include(":app")`), not a subproject of the root build. The correct form is:

```sh
cd /data/data/com.termux/files/home/sage-work
./gradlew -p sageos2 :app:testDebugUnitTest \
  --tests '*CommandEndpointPolicyTest' --tests '*RecognitionSessionGateTest' \
  --tests '*CommandRecognizerPolicyTest'
```

Note the root `settings.gradle.kts` declares `include(":app")` but there is no
`~/sage-work/app/` directory, so the root build is vestigial; `VERIFY_JE_BUILD.sh` still
assumes that dead layout. Only `sageos2/` is buildable.

**Correction 3 — the sherpa AAR is absent, so `testDebugUnitTest` will still fail after the
shell returns.** `sageos2/app/libs/` does not exist, but `app/build.gradle.kts:44` declares
`implementation(files("libs/sherpa-onnx-1.13.7.aar"))`, and `.gitignore:11` ignores
`sageos2/app/libs/sherpa-onnx-*.aar`. The AAR is intentionally untracked and is not on this
machine. Unit tests compile against `main`, so `:app:compileDebugJavaWithJavac` must first
succeed, and `SageSherpaRecognitionService.java` imports `com.k2fsa.sherpa.onnx.*` from that
AAR. **Restoring the shell alone is not sufficient — the AAR must be supplied too.** Note the
30 new tests are pure-JVM and touch no Android or sherpa code, so they could be compiled and
run standalone with `kotlinc` plus a JUnit 4.13.2 jar, bypassing the Android module entirely.
That is the cheapest way to get real test results.

---

## 1. Repository

| | |
|---|---|
| Local path | `/data/data/com.termux/files/home/sage-work` |
| Remote | `superkat13/JE` (GitHub) |
| Base branch | `sageos-2` |
| **Current baseline** | **`36c6a08`** — supersedes the previously cited `88a02d4` |
| Intended branch | `repair/command-speech-213` (not yet created) |
| Branch state | no branch created; all work sits **uncommitted in the working tree** |

### Incorporating the new baseline

The work was written against the tree as found (`88a02d4`-era). Baseline is now `36c6a08`.
Preserve the edits when rebasing:

```sh
cd /data/data/com.termux/files/home/sage-work
git stash push -u -m command-speech-213   # -u also stashes the 4 new untracked files
git fetch origin sageos-2
git checkout -B repair/command-speech-213 origin/sageos-2
git log --oneline -1                      # confirm 36c6a08
git stash pop
```

If `git stash pop` reports conflicts in the six files below, resolve by hand in this priority:
keep the `36c6a08` version of anything unrelated to speech, keep the edits in §3, and re-apply
the `SageSherpaRecognitionService` loop rewrite from §3.2 as a whole block rather than as a
line-level merge.

### Concurrent work — do not touch

PR #46 = `repair/stable-brain-prefix-213` -> `sageos-2`, "Stabilize durable identity prompt
prefix to avoid unnecessary prefill". It touches prompt budgeting, request policy, identity
rendering and runtime/startup continuation, and its own description states *"Speech recognition
remains separate work."* No file overlap with this repair. Do not rebase onto it, and do not
edit Brain/identity/runtime code that it owns.

---

## 2. Execution blocker — exact error

The bash tool is bound to a single persistent PTY session, and that session is wedged.

Verbatim tool error, repeated ~12 times across every timeout from 15s to 100s, for `echo`,
`printf`, `true`, `id`, `pwd`, `git rev-parse HEAD`, `git --version`:

```
shell tool terminated command after exceeding timeout 20000 ms. If this command is expected to
take longer when is not waiting for interactive input, retry with a larger timeout value in
milliseconds.
```

Zero stdout in every case. `echo`/`true` are builtins that return in microseconds, so a full
timeout is not consistent with a slow command.

`glob` and `grep` also fail, with a different error, because they spawn a subprocess:

```
ripgrep execution failed
```

`read`, `write` and `edit` continue to work because they run in-process. That is why the source
investigation and these edits were possible at all.

### Root cause, from `/proc`

| Evidence | Value |
|---|---|
| `/proc/2413/cmdline` | `/data/data/com.termux/files/usr/bin/bash -l` |
| `/proc/2413/status` | `State: S (sleeping)`, `Threads: 1` |
| `/proc/2413/wchan` | `do_wait` |
| `/proc/2413/stat` | `tty_nr=34816`, **`tpgid=7452`** |
| `/proc/7452/cmdline` | `/data/data/com.termux/files/home/.opencode/opencode` |

The shell is blocked in a kernel `do_wait` on a foreground child that never exits, so every later
command queues behind it. The terminal's foreground process group is `7452` — the opencode process
itself — so the agent process holds the terminal while the shell sits behind it. The stuck child
is not visible: `/proc` exposes only PIDs 2413, 2496 and 7452, and
`/proc/2413/task/2413/children` does not exist in this namespace.

A freshly spawned subagent hits the identical failure, so this is the shared session, not
per-agent state. There is no available tool to signal PID 2413, kill its child, or request a new
shell.

**User action required:** restart opencode so the PTY is recreated. Alternatively, from a
*separate* Termux session: `pkill -f 'bash -l'`. It cannot be done from the blocked session
itself.

---

## 3. Every changed and new file

Six files, all under the repo root. Paths are repo-relative.

### 3.1 New — `sageos2/app/src/main/java/com/pineapple/sageos2/speech/CommandEndpointPolicy.kt`

63 lines. Pure `object`, no Android imports, so it unit-tests on the JVM. Exports
`DEFAULT_MAX_UTTERANCE_MS = 15_000L`, `SPEECH_ONSET_ENERGY = 180L`, `MIN_PEAK_ABS = 32`, enums
`ChunkAction { CONTINUE, FINISH_WITH_TEXT, FINISH_EMPTY }` and
`WindowEnd { RESULTS, NO_MATCH, AUDIO_ERROR, SUPPRESSED }`, plus `@JvmStatic` `onChunk`,
`shouldContinue`, `onWindowEnd`, `hasSpeechEnergy`.

`onChunk(endpointReached, text, speechBegan)`:
- no endpoint -> `CONTINUE`
- endpoint + non-empty text -> `FINISH_WITH_TEXT`
- endpoint + empty text + **speech already began** -> `FINISH_EMPTY`  *(the fix)*
- endpoint + empty text + no onset yet -> `CONTINUE`  *(regression guard)*

### 3.2 Modified — `sageos2/app/src/main/java/com/pineapple/sage/SageSherpaRecognitionService.java`

378 -> 396 lines. Local sherpa `RecognitionService`. Changes:

1. added `import com.pineapple.sageos2.speech.CommandEndpointPolicy;`
2. added `private static volatile String lastCompletion = "";` — values `endpoint` / `budget` /
   `cancelled`; surfaced in `runtimeDetail()` as `", last turn ended by " + lastCompletion`.
   Carries **no audio and no transcript text**.
3. hoisted `boolean endpointReached` and `boolean speechBegan` above the `try` so `finally` can
   read them (the previous `began` was declared inside the `try` and would not have been in scope)
4. onset gate now sets `speechBegan`
5. **the endpoint rewrite** — replaced
   `if (recognizer.isEndpoint(stream) && !text.isEmpty()) { finalText = text; break; }`
   with a `CommandEndpointPolicy.onChunk(...)` dispatch that also breaks on `FINISH_EMPTY`
6. added `if (stopRequested.get()) return;` after the read loop, before the tail drain, so an
   explicit stop never emits a late transcript or late error
7. final classification routed through `CommandEndpointPolicy.onWindowEnd(...)`, which returns
   `SUPPRESSED` for a stop
8. `finally` now guards `if (worker == Thread.currentThread()) worker = null;`

### 3.3 New — `sageos2/app/src/main/java/com/pineapple/sageos2/speech/RecognitionSessionGate.kt`

30 lines. Pure `class`; `current`, `next()`, `invalidate()`, `isCurrent(session)`. Extracted so
stale-callback rejection is JVM-testable. Behaviour-preserving refactor, not a behaviour change.

### 3.4 Modified — `sageos2/app/src/main/java/com/pineapple/sageos2/speech/AndroidSpeechPort.kt`

347 lines, unchanged length. Eight sites; counter -> gate, 1:1, mutation order preserved:

| Line | From | To |
|---|---|---|
| 31 | `private var recognitionSession = 0L` | `private val sessions = RecognitionSessionGate()` |
| 196 | `val session = ++recognitionSession` | `val session = sessions.next()` |
| 218 | `recognitionSession += 1` | `sessions.invalidate()` |
| 244 | `recognitionSession += 1` | `sessions.invalidate()` |
| 254 | `if (session != recognitionSession)` | `if (!sessions.isCurrent(session))` |
| 258 | `recognitionSession += 1` | `sessions.invalidate()` |
| 274 | `if (session != recognitionSession)` | `if (!sessions.isCurrent(session))` |
| 282 | `recognitionSession += 1` | `sessions.invalidate()` |

`EXTRA_PARTIAL_RESULTS = false` (line 201) is deliberately left as is. See §5.

### 3.5 New — `sageos2/app/src/test/java/com/pineapple/sageos2/speech/CommandEndpointPolicyTest.kt`

187 lines, 20 tests.

### 3.6 New — `sageos2/app/src/test/java/com/pineapple/sageos2/speech/RecognitionSessionGateTest.kt`

97 lines, 10 tests.

---

## 4. Proposed fix and tests — NOT RUN

### The diagnosis

`code=7` is `SpeechRecognizer.ERROR_NO_MATCH`, emitted locally at
`SageSherpaRecognitionService.java:218`. On this device it means: the recording window expired,
real PCM energy was present, and the decoder produced no final text. It is *not* the
`ERROR_AUDIO` path, which requires `totalSamples == 0 || peakAbs < 32` — so the audio handoff
worked and decoding returned nothing.

Both observed failures reconcile with the loop running to its full cap rather than any timeout
race. `MAX_UTTERANCE_MS = 15_000`, measured from `started` set after `startRecording()`:

| turn | speech began | failed | delta | implied window open | onset lag |
|---|---|---|---|---|---|
| 11 | 1790602936283 | 1790602950210 | 13,927 ms | 1790602935210 | 1,073 ms |
| 12 | 1790602958975 | 1790602972513 | 13,538 ms | 1790602957513 | 1,462 ms |

**Root cause of the latency:** the endpoint branch was gated on `!text.isEmpty()`. A no-match turn
is by definition empty-text, so the endpoint branch was unreachable precisely when it was needed
and every failure was forced to burn the entire 15 s window. The gate is now decoupled from
text, but still gated on observed speech onset, because sherpa's stock `rule1` can fire on
*leading* silence and would otherwise turn an ordinary quiet start into an instant no-match.

**Why it is safe:** the tail drain (`inputFinished` + decode + `getResult` + the `lastText`
fallback at line 221) runs identically. Only *when* it runs changed. The same `finalText` reaches
the same classification, roughly 11 s sooner, and the microphone is released correspondingly
sooner. Note the drain still happens *before* `stopMicrophone()` (line 222, after lines
216-221); an earlier draft of this handoff claimed the microphone was closed before the drain,
which is not what the code does. The repair did not move that call, and the shortened turn
bounds the total hold either way.

### Reducing failed-turn delay vs improving recognition accuracy — kept separate

This change reduces failed-turn delay only. It cannot change recognition accuracy, because it
never re-decodes and never alters the text handed to the classifier.

It does **not** explain or fix the underlying question of *why* the decoder emitted nothing across
15 s of real audio. Note that line 221's `if (finalText.isEmpty()) finalText = lastText;`
fallback means a `code=7` result proves the decoder produced **zero** text for the entire turn.
That remains open and is the more important unknown.

### Explicit non-changes

- `MAX_UTTERANCE_MS` stays 15,000 — not shortened without evidence. Pinned by a test.
- `modelType` stays `"zipformer"` — unchanged.
- `EXTRA_PARTIAL_RESULTS = false` stays. The fix does **not** rest on the idea that discarded
  partial callbacks disable native endpointing. Endpointing is native inside the service loop via
  `recognizer.isEndpoint(stream)`, independent of Android's partial-result plumbing.
- No wake engine, wake model, Brain, SageRuntime, core/migration, signing, version or release
  file was edited.

### Tests — NOT RUN

30 new host JUnit tests, all pure-JVM with no Android dependency.

`CommandEndpointPolicyTest` covers: no-endpoint keeps reading; endpoint+text finishes with text;
endpoint+empty+onset finishes empty (the regression); endpoint+empty+no-onset keeps reading;
whitespace-only text counts as empty; budget unchanged at 15,000; continues inside budget; stops
at budget; `stopRequested` ends the loop inside budget; `stopRequested` suppresses late
emission; SUPPRESSED outranks recovered text; non-empty text emits results; genuine no-match;
no samples is audio error; too-quiet is audio error; peak exactly at threshold is not an audio
error; onset gate loud/quiet; zero count does not divide by zero; **the two observed turn shapes
complete at ~4,100 ms instead of the 15,000 ms cap**; and text recovered by the tail flush is
still emitted.

`RecognitionSessionGateTest` covers: fresh gate accepts nothing; `next` increases; current token
accepted; `invalidate` retires; stale result after new turn rejected; stale error after cancel
rejected; double-invalidate still rejects; callback many turns late still rejected; the exact
port mutation order; consuming a result retires the token so it cannot fire twice; fallback
restart rejects the failed local session.

**No test has been executed. There are no results.** Two arithmetic errors were found and fixed
by hand during authoring (an off-by-one chunk index and a divide-by-zero-adjacent assertion);
that is exactly the class of defect a compiler catches and could not be caught here. **Assume a
compile error is possible**, most likely in the Kotlin `object` / `@JvmStatic` call from the Java
service, or in `shouldContinue`'s default parameter (it carries no `@JvmOverloads` and is
intentionally not called from Java — only from the tests).

### Second cancellation-safety defect, also fixed

`finally` previously ran `worker = null` unconditionally. A turn retiring while a successor was
already running would null the *successor's* reference; the `ERROR_RECOGNIZER_BUSY` guard in
`onStartListening` would then pass and two `AudioRecord`s would contend for the microphone.
Now identity-guarded, matching what `SherpaWakeWordEngine` already does at its own `finally`.

### Next steps — shell is back, host tests green, rebase done

Steps 1-3 of the original plan are **done**; see §0.1. Current state: branch
`repair/command-speech-213` at `36c6a08`, six files modified/untracked in the working tree, 36/36
host tests passing, nothing committed.

Still outstanding, in order:

```sh
cd /data/data/com.termux/files/home/sage-work
# 1. Android build still impossible here: no Android SDK, no NDK, no sherpa AAR in
#    sageos2/app/libs/. Needs a machine that has them, or the AAR supplied.
# 2. Once the SDK + AAR are available:
./gradlew -p sageos2 :app:testDebugUnitTest \
  --tests '*CommandEndpointPolicyTest' --tests '*RecognitionSessionGateTest' \
  --tests '*CommandRecognizerPolicyTest'
# 3. Then, only if the NDK/CMake toolchain is present:
./gradlew -p sageos2 :app:assembleDebug
# 4. Device run: read lastCompletion from runtimeDetail() after a failing turn. This is the
#    decisive check and it is the whole point of the change - see 5.3.
# 5. commit, push, open DRAFT PR. Do not merge. Do not build/distribute an APK.
```

Step 4 is the one that actually matters. Everything in §0.1 is host-side proof that the code
compiles and the policy is self-consistent; only a device turn can show whether the endpoint gate
shortens the failure as §4 predicts.

`CommandRecognizerPolicyTest` is included above deliberately: it is the pre-existing test for
`CommandRecognizerPolicy` and must still pass, proving backend-selection behaviour is unchanged.

---

## 5. Remaining limitations

1. ~~**Nothing is verified.** No compile, no test run, no device run.~~ **SUPERSEDED by §0.1:**
   everything is compiled and the 36 host tests pass. What is still unverified is the Android
   integration (§5.6) and the sherpa API surface (§0.1), neither of which is reachable without an
   Android SDK and the real AAR.
2. **The decoder-empty question is unexplained.** This change makes the failure fast; it does not
   make the recognizer work. `code=7` proves zero tokens across the whole turn.
3. **The single decisive device check:** read `lastCompletion` from `runtimeDetail()` after a
   failing turn. If it reports `budget` and not `endpoint`, the leading-silence reasoning is
   wrong and the real cause is upstream of the endpoint gate. Re-evaluate before trusting §4.
4. **Mic contention is unseparated.** The `RecognitionService` runs in-process with
   `AndroidSpeechPort`, and the wake engine's `stop()` bounds its join at only
   `STOP_JOIN_MS = 1_500L`. Real contention behaviour cannot be distinguished from
   "decoded nothing" without a device run. This overlaps the wake engine's territory, so it is
   reported, not edited.
5. **Endpoint-config question left open.** `config.setEndpointConfig(OnlineRecognizerKt.getEndpointConfig())`
   uses sherpa's stock default. Tuning it was deliberately avoided without device evidence.
6. **Physical-device acceptance outstanding.** No VASOUN L10_T05 run was performed. `SageSherpaRecognitionService`
   cannot be host-tested at all — it needs the native sherpa model pack and a real microphone.
7. **No fabricated data anywhere.** No transcripts, no synthetic transcripts, no APK, no signing
   keys, no uninstall or data clearing, no owner data / private GGUF / Sage identity touched.

---

## 6. Discard commands — REFERENCE ONLY, DO NOT RUN

Listed only so the recovery options are written down. The repair is to be kept.

```sh
# reference only
git checkout -- sageos2/
rm sageos2/app/src/main/java/com/pineapple/sageos2/speech/CommandEndpointPolicy.kt
rm sageos2/app/src/main/java/com/pineapple/sageos2/speech/RecognitionSessionGate.kt
rm sageos2/app/src/test/java/com/pineapple/sageos2/speech/CommandEndpointPolicyTest.kt
rm sageos2/app/src/test/java/com/pineapple/sageos2/speech/RecognitionSessionGateTest.kt
```

Safer, if a diff is wanted first: `git stash push -u -m command-speech-213` (keeps everything,
recoverable with `git stash pop`).
