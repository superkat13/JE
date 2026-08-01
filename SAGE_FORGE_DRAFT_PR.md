# Draft pull request: Sage Commander 1.25.0 with owner-controlled Sage Forge

## Summary

Adds a production-verifiable first Sage Commander ↔ Sage Forge vertical slice without changing package or signing identity. The tablet remains the approval/controller surface; the owner-run Dell service performs only locally registered jobs behind pinned TLS, revocable trust, strict schemas, deterministic safety checks, durable logs, and explicit confirmation.

Also integrates the previously completed 1.25 Workbench/self-repair foundation and retains the 1.24.2 overlay, speech, memory, and appearance repairs.

## What changed

- Add Sage Forge Python service, TLS identity generation, SQLite state, strict Tool Runner/Safety Guard, declarative Agent Registry, schemas, and security/integration tests.
- Add Workbench pairing/job screen with Android Keystore token protection, progress/log display, result persistence, cancellation, and revocation.
- Add Workbench/voice entry points for Forge.
- Audit `frontier-ai/llm-agent-factory` at revision `505aa09857889bc679f2b914e2c33527051c37a8`; copy no upstream code/data/model while provenance remains ambiguous.
- Add architecture, pairing protocol, threat model, license notice, verification report, completion status, root-cause report, and tablet checklist.
- Make both CI reconstruction paths apply and test Workbench + Forge.

## Security invariants

- No arbitrary shell/command/script/executable field is accepted from jobs or agents.
- Agent tool requests cannot register or grant tools.
- Pairing and every job require explicit local owner action.
- No silent install, permission grant, public scan, exploit, persistence, evasion, merge, release, or code replacement path is added.
- Forge binds only an exact loopback/private address and exposes no generic execution endpoint.

## Verification

- All Sage source regression programs passed.
- Forge suite passed 8/8, including real pinned-TLS system information and post-revocation denial.
- Deterministic source reconstruction passed.
- Java compilation, lint, native Brain compilation, and release APK assembly passed.
- Lint: 0 errors, 105 warnings (reported by category in `SAGE_FORGE_VERIFICATION_REPORT.md`).
- APK ZIP integrity, 16 KiB alignment, v2/v3 signature, package/version, signer continuity, Brain library, manifest activity, and DEX Forge markers passed.
- Candidate SHA-256: `8115109c841fe279c51318050ffcf91cb421c1b47a10cb7c55159f325839da3b`.

## Review focus

1. Pairing pin/hostname UX and Dell firewall instructions.
2. Tool-registry authority boundary and AgentSpec executable-field rejection.
3. SQLite trust/replay/job ownership behavior.
4. Android activity lifecycle, reconnect polling, result persistence, and revocation.
5. Upstream audit/licensing decision before any future direct Agent Factory import.

## Not included

No commit, push, merge, release, publication, installation, public-target scan, arbitrary execution, binary artifact API, automatic interrupted-job replay, Agent Factory dataset bundling, or Windows privileged collector is included.
