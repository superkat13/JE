# Sage Commander 1.29.0 bounded Brain repair

## Scope and preserved continuity

This slice advances the deterministic source chain to `1.29.0` / version code `41` while
preserving package `com.pineapple.sagecommander.stable`, the permanent signing configuration,
app-private data, memories, imported models, wake profiles, Red Queen state, Workbench, Toolbelt,
permissions, and Sage Forge pairing. The required signer certificate remains
`99e0a7c655cdefb3bb4ac85e5961d19358ee0ffdb3dce9b3a145f9cbcda78d35`.

## Confirmed failure and root cause

The reported Qwen3-1.7B-Q8_0 file loads and creates a context. Generation then remains at
`inference_start` with zero generated tokens until the unchanged 30-second watchdog fires.
In this implementation the first `llama_decode` processes the entire formatted prompt before
sampling can begin. The old deterministic test incorrectly included Sage's full command policy,
intent context, memories, and conversation history, making this a prompt-prefill timeout rather
than a token-sampling failure. The pinned llama.cpp CPU abort callback was also unset, so
cancellation could not interrupt an in-progress prefill.

## Repair

- The health test uses the exact user prompt `Reply with exactly: Brain online.` through a
  dedicated minimal prompt path with greedy sampling and `/no_think`; it never injects memory or
  conversation state.
- Success still requires at least one real native generated token and the exact displayed text.
  No fallback is accepted as a pass.
- The native CPU abort callback now observes the existing cancellation flag during
  `llama_decode`; the watchdog remains 30 seconds.
- Diagnostics add prompt token count and prompt-prefill duration while retaining model, file,
  size, quantization, hash, route, load time, first-token latency, tokens/second, token count,
  RAM, stage, and exact error.
- The owner-started model manager offers a pinned Qwen3-1.7B Q4_K_M candidate when the Q8 model is
  too slow for the tablet. It downloads directly over HTTPS from an immutable repository revision,
  resumes with HTTP Range, reports progress, cancels without discarding resumable bytes, checks
  free storage, and requires exact byte size and SHA-256 before atomically replacing the active
  model. Manual GGUF import remains available.

## Honest acceptance boundary

Host regressions and deterministic reconstruction can prove source behavior and native API
compatibility. They cannot prove physical tablet first-token latency or exact Qwen output. Physical
Brain success remains unclaimed until the owner runs **Sage, test your brain** on the real tablet
and records the resulting persistent Brain report.
