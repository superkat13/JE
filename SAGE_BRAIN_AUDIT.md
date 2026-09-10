# Inherited Sage Brain identity audit

Date: 2026-09-10
Repository: `superkat13/JE`
Development line: `sageos-2`
Installed model path: `files/brain/sage-brain.gguf`

## Result and evidence boundary

The exact inherited Brain cannot yet be identified from the repository or the host. The GGUF is deliberately owner data in the private files directory of the installed package, not a Git object or an APK asset. No Android device is currently visible to ADB, and no copy of `sage-brain.gguf` exists under `/home/kat`. Therefore every model-identity field below remains **UNKNOWN** rather than being inferred from a filename, an old recommendation, or an issue comment.

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

## Replacement and benchmark gate

Candidate models may be researched only after this table is exact. Each candidate must then be compared with the inherited Brain using the same prompts, llama.cpp build, thread count, 2,048-token operating context, thermal state, and real VASOUN 8 GB tablet. Required measures are Sage identity/personality retention, natural conversation, instruction following, deterministic and structured tool calling, memory/context use, first-token and total latency, resident/peak RAM, cold startup, second-turn warm latency, cancellation, repeated-turn stability, and thermal behavior.

No replacement is justified by leaderboard scores, parameter count, model age, or family reputation. It must materially win the Sage-specific physical test without exceeding safe memory/startup/stability limits. Until then, the inherited GGUF remains untouched.
