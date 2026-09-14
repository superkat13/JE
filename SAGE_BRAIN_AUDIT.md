# Inherited Sage Brain identity audit

Date: 2026-09-10
Repository: `superkat13/JE`
Development line: `sageos-2`
Installed model path: `files/brain/sage-brain.gguf`

## Result and evidence boundary

The exact inherited Brain cannot yet be identified from the repository or the host. The GGUF is deliberately owner data in the private files directory of the installed package, not a Git object or an APK asset. At the 2026-09-10 audit no Android device was visible to ADB, and no copy of `sage-brain.gguf` exists under `/home/kat`. Therefore every model-identity field below remains **UNKNOWN** rather than being inferred from a filename, an old recommendation, or an issue comment.

### Live tablet preflight, 2026-09-13

The VASOUN L10_T05 is now ADB-authorized. Its installed Sage package is still versionCode 205 / versionName 2.0.0. The installed public APK has SHA-256 `9e2f30be52f1a1a15fd4a422b57695a8c727c212879c910002c2820199b5261e`, exactly the documented candidate-205 artifact. `apksigner` verifies the Android 13 signer as `e2e3e2cabd3372d6073643b35dc94b5fb62e32c200f9e236d4b9f1e403f61b6e`, with the documented legacy signer for API 24–32. The installed app reports Brain READY and says a GGUF exists in private storage, but its 205 model screen has no saved import name/size/hash and no embedded metadata display.

Android denies `run-as` because this is a non-debuggable release package, and direct shell access to `/data/user/0/com.pineapple.sagecommander.stable` is denied. The model remains **unidentified**; the APK's own hash is not the GGUF hash. No model bytes were copied, changed, or benchmarked. A read-only owner-triggered identity action has been added to the existing version-206 recovery source. It parses metadata and tensor descriptors in place and hashes the same open file descriptor on a worker thread. It must pass signed in-place update gates before it can be used on this installation; the installed 205 cannot expose these facts through ADB alone.

This is a hard benchmark gate: no alternative-model benchmark and no model replacement is authorized until the installed file has been read non-destructively and used as the baseline.

| Required field | Exact current value | Evidence |
|---|---:|---|
| Model family | **UNKNOWN** | Installed GGUF unavailable |
| Embedded model name | **UNKNOWN** | Installed GGUF unavailable |
| Architecture | **UNKNOWN** | Installed GGUF unavailable |
| Parameter count | **UNKNOWN** | Installed GGUF unavailable |
| Quantization/file type | **UNKNOWN** | Installed GGUF unavailable |
| Trained context length | **UNKNOWN** | Installed GGUF unavailable |
| Tokenizer | **UNKNOWN** | Installed GGUF unavailable |
| Embedded chat template | **UNKNOWN** | Installed GGUF unavailable |
| Exact file size | **UNKNOWN** | Installed GGUF unavailable |
| SHA-256 | **UNKNOWN** | Installed GGUF unavailable |
| Metadata/provenance fields | **UNKNOWN** | Installed GGUF unavailable |

Repository prose that calls the inherited model “approximately 1.71 GB” or names/recommends a particular family is not metadata evidence and must not be promoted into this table.

## What is confirmed about the loader

- SageOS 2 keeps the stable package `com.pineapple.sagecommander.stable` and the same private path, so an in-place update is designed to retain the file.
- The current native runtime is pinned to llama.cpp commit `d73c1d6b22a2d3ecc74c2c9cde354015ee72e862`.
- Sage requests a 2,048-token runtime context, 512-token batch, 256-token micro-batch, CPU execution, and at most 24 generated tokens. The runtime request is not proof of the GGUF's trained context length.
- Prompt formatting first asks llama.cpp for the model's embedded chat template and applies it with `llama_chat_apply_template`. A plain system/User/Assistant fallback is used only when no usable model template is available.
- A valid `GGUF` magic number is checked during import. This does not establish architecture, tokenizer compatibility, tensor types, or template correctness.

## Compatibility decision that must follow inspection

The inspection must report at least:

1. GGUF container version and alignment.
2. `general.architecture`, `general.name`, `general.file_type`, quantization version, file type distribution, and all `general.*` or source/provenance fields.
3. Tensor count, summed parameter count, and tensor-type distribution.
4. The architecture-specific context-length field, for example `<architecture>.context_length`.
5. Tokenizer model, pre-tokenizer, BOS/EOS/padding IDs, add-BOS/add-EOS flags, token count, and the complete embedded `tokenizer.chat_template` value or its hash plus safely escaped text.
6. Whether the pinned llama.cpp revision implements that architecture, tokenizer pre-type, tensor types, and chat-template behavior.
7. A cold load and one-token generation on arm64 before claiming runtime compatibility.

Compatibility must be evaluated against the exact pinned llama.cpp source used by Sage, not merely against a newer desktop `llama-cli`.

## Non-destructive acquisition

When the owner's tablet is connected and authorizes ADB, inspect the existing package data without uninstalling, clearing data, importing, or replacing anything. Prefer streaming only the GGUF header and tensor descriptors through a bounded parser; obtain exact byte size and SHA-256 separately. If `run-as` is denied for the release package, add an owner-triggered in-app “Brain identity report” that reads the file in place on a worker thread and shares metadata only. Do not weaken package sandboxing or copy the model into public storage merely for convenience.

The smallest device-side facts needed are:

```text
package: com.pineapple.sagecommander.stable
path:    files/brain/sage-brain.gguf
size:    exact bytes
sha256:  exact lowercase digest
header:  GGUF metadata plus tensor descriptors, no tensor payload required
```

For the current 206 recovery source, open **Settings → Advanced → Local replies & device access → Open local Brain model**, then tap **Inspect installed Brain (read-only)**. The action runs on a worker thread, shows byte size and SHA-256, enables **Copy Brain identity report**, and emits numbered `SageBrainIdentity` logcat parts only after the tap. Capture those parts over authorized ADB, reconstruct them in order between `BEGIN` and `END`, and compare the report against the host inspector's field meanings. The report summarizes tokenizer arrays by count, encoded-item digest, and preview; it does not emit tensor payload or owner conversation data. Do not tap **Choose GGUF model** during this audit.

## Replacement and benchmark gate

Candidate models may be researched only after this table is exact. Each candidate must then be compared with the inherited Brain using the same prompts, llama.cpp build, thread count, 2,048-token operating context, thermal state, and real VASOUN 8 GB tablet. Required measures are Sage identity/personality retention, natural conversation, instruction following, deterministic and structured tool calling, memory/context use, first-token and total latency, resident/peak RAM, cold startup, second-turn warm latency, cancellation, repeated-turn stability, and thermal behavior.

No replacement is justified by leaderboard scores, parameter count, model age, or family reputation. It must materially win the Sage-specific physical test without exceeding safe memory/startup/stability limits. Until then, the inherited GGUF remains untouched.
