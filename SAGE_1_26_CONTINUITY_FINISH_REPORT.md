# Sage Commander 1.26.0 continuity-finish report

Target identity: `1.26.0` / `versionCode 38` / `com.pineapple.sagecommander.stable`

## Brain failure boundary

The historical tablet evidence proves that the selected Qwen file was found and that the old
native request produced zero counted tokens before the 30-second watchdog cancelled it. Because
that build did not persist a native stage, it cannot honestly distinguish a blocked initial
`llama_decode` from sampling an immediate end token. The last provable boundary is therefore
**inference start -> before first token**; claiming a narrower historical stage would invent a
measurement that was never recorded.

The finish patch records the current native stage at model verification, model load, context
creation, prompt formatting, inference start, token sampling, first token, generation,
cancellation, and completion. A subsequent on-tablet run will therefore report the exact failing
stage instead of collapsing it into a generic timeout. The timeout remains 30 seconds.

## Completed source changes

- Reject a second generation while one is active; existing concurrent load callers still join one
  load operation.
- On cancellation, atomically request stop and clear the llama context memory after native decode
  returns control.
- Treat an empty output as an error containing the last native stage and actual generated-token
  count.
- Run the deterministic prompt `Reply with exactly: Brain online.` and require at least one native
  generated token plus the displayed response `Brain online.` to pass.
- Persist the displayed test response, generated-token count, native stage, model identity, size,
  inferred quantization, hash, route, load time, first-token latency, tokens/second, RAM, timeout
  stage, and exact error in the Brain health report.
- Store categorized memories while decoding all legacy memory rows; add deterministic inspect,
  exact edit, and exact delete paths with duplicate rejection and one acknowledgement.
- Retarget supervised repair packets and validation to the dedicated recovery branch and preserve
  the package, version, and permanent signing identity requirements.

## Verification status

The finish patch has source regressions and a deterministic clean-reconstruction gate. Host source
tests and patch replay must pass before checkpointing. Android compilation, signed assembly, and
physical-tablet Brain/audio acceptance are separate checks and must not be reported as executed
until their logs exist.
