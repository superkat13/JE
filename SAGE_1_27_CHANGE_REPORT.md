# Sage Commander 1.27.0 change report

Identity: `com.pineapple.sagecommander.stable` / `1.27.0` / `versionCode 39`

## Unified intelligence checkpoint

- Added a deterministic coordinator that turns each request into a goal, intent, internal
  specialist, selected tool, route hint, verification method, and confidence score.
- Preserved the command engine as the first authority for allowlisted actions, with tablet Brain,
  Dell Forge, and fallback remaining visibly labeled routes.
- Added safe contextual repeat handling for phrases such as `do that again`; typing, installation,
  deletion, memory clearing, network scans, pairing, and revocation are never repeated implicitly.
- Added correction awareness and a visible answer for `what do you understand?` using the wording
  `I understand what you want to accomplish:`.
- Passed the current goal and intent into tablet Brain prompt context without allowing Brain to
  bypass the existing command allowlist.

## Brain and Memory 2.0

- Added pre-load detection for missing, truncated, oversized, size-changed, and non-GGUF model
  files, with a truthful Model Manager re-import path.
- Preserved duplicate-load and duplicate-generation guards, native stage tracing, cancellation,
  first-token metrics, response routing, and the deterministic Brain test.
- Added versioned memory records with category, confidence, source, creation time, normalized key,
  and value while preserving deterministic reads of legacy memory formats.
- Added `This project is...` and `Forget this`, retaining exact duplicate prevention, edit, delete,
  persistence, teaching aliases, preferences, devices, and existing categories.
- Public Forge examples are sanitized; deployment endpoints and paths are supplied only through
  ignored local configuration or environment variables.
- A disclosure regression rejects concrete non-localhost addresses, credential/key patterns,
  certificate fingerprints, device IDs, and tracked private deployment files.

## Verification status

The host intelligence/memory harness and source checks execute before checkpointing. Android
compile, native build, lint, signed APK assembly, signature/package verification, and physical
tablet acceptance are reported only after their corresponding logs exist.
