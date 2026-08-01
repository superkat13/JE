# Sage Commander 1.26.0 Brain benchmark report

## Build-host result

The native Brain compiled successfully for ARM64 and was packaged into the verified APK. The build host is x86_64 and did not have the owner's selected GGUF model or an Android tablet runtime, so it cannot produce honest load duration, first-token latency, tokens per second, generated-token count, or tablet RAM measurements. Those fields are explicitly **not benchmarked on this host**.

| Item | Result |
|---|---|
| Native target | ARM64 / AArch64 ELF shared library |
| Packaged library | `lib/arm64-v8a/libsage-brain.so` |
| Packaged size | 4,982,848 bytes |
| Packaged SHA-256 | `5c0073a533634fd48a0418e6ced4c08183da846313628d6a65b1c8484b2f0f77` |
| llama.cpp source pin | `d73c1d6b22a2d3ecc74c2c9cde354015ee72e862` |
| Local model | Not supplied to build host |
| Runtime load duration | Not benchmarked on host |
| Runtime first-token latency | Native first-token timestamp is instrumented; no tablet/model measurement was available on host |
| Runtime generation speed | Not benchmarked on host |
| Runtime RAM | Not benchmarked on host |

## Instrumented tablet test

Open **Workbench → Test Sage Brain**, or say **“Sage, test your brain.”** The persistent report records:

- active route and status;
- model name, private model filename, inferred quantization, exact file size, and verification hash;
- load state and duration;
- prompt-start timestamp;
- native first-token latency;
- native generation duration, actual generated token count, and tokens per second;
- currently available RAM and Sage process RAM usage;
- cancellation state;
- exact timeout/error stage.

The deterministic prompt asks for one short health-confirmation sentence. A selected local model is preloaded through a load-coalescing gate, so concurrent requests join the active load rather than starting another. The load watchdog is 15 seconds. The generation watchdog remains 30 seconds, cancels the current native request, invalidates stale callbacks, records `TIMED_OUT`, and returns a labeled fallback rather than silence.

Response labels are `command engine`, `tablet Brain`, `Dell Forge`, or `fallback`. Persistent indicator states are `OFF`, `LOADING`, `LOCAL_READY`, `DELL_READY`, `THINKING_LOCAL`, `THINKING_DELL`, `FALLBACK_USED`, `TIMED_OUT`, and `ERROR`.

## Required tablet benchmark record

Before release installation is approved, capture the Test Brain report after a cold load and again after a warm request. Record the model filename and hash, tablet model/OS, load time, generation duration/speed, available RAM, and whether cancellation returns control within the watchdog window.
