# Sage 1.25.0 candidate modified-file ledger

Generated build directories, Gradle caches, Python bytecode, temporary process files, the reconstructed source ZIP, and signed artifacts are verification outputs, not release-source changes.

## Release-chain and CI

| File | Reason |
|---|---|
| `.github/workflows/build-sage-v1-24-continuity.yml` | Reconstruct, test, build, inspect, and publish the Workbench + Forge candidate as a workflow artifact only. |
| `.github/workflows/sage-approved-repair.yml` | Validate constrained repair packets and reconstruct through Workbench + Forge before tests/build; no merge/release/install. |
| `SAGE_1_24_CHANGELOG.md` | Carry the release history through 1.24.2 and the 1.25 Workbench foundation. |
| `sage_patches/number_overlay_release_v1_24_1.py` | Apply the same-package accessibility window-churn correction. |
| `sage_patches/sage_release_v1_24_2.py` | Apply overlay diagnostics/timeouts, speech quality handling, deterministic memory, and appearance controls. |
| `sage_patches/self_repair_foundation_v1_25.py` | Generate repair bundle, authority dashboard, repair packet, voice commands, and 1.25 identity. |
| `sage_patches/workbench_v1_25.py` | Generate Workbench shell, package inspection/install handoff, network map, shared operations/confirmations, and device tools. |
| `sage_patches/sage_forge_v1_25.py` | Generate Forge Android store/client/activity plus Workbench, manifest, and voice integration. |

## Forge companion source

| File | Reason |
|---|---|
| `sage_forge/__init__.py` | Package/version boundary. |
| `sage_forge/__main__.py` | Private-address Forge CLI, one-use pairing window, TLS server lifecycle. |
| `sage_forge/create_identity.py` | Fixed-argument local TLS certificate/SAN generator. |
| `sage_forge/security.py` | Pairing grants, random tokens/IDs, pin normalization, freshness checks. |
| `sage_forge/store.py` | Durable SQLite trust, replay, jobs, logs, results, cancellation, restart state. |
| `sage_forge/tools.py` | Trusted Tool Registry, Safety Guard, Tool Runner, real `system.info` collector. |
| `sage_forge/server.py` | Bounded TLS JSON API, authentication, job ownership, cancel/revoke routes. |
| `sage_forge/client.py` | Pinned-TLS reference/smoke-test client. |
| `sage_forge/agents.py` | Strict non-executable AgentSpec validation and local-tool intersection. |
| `sage_forge/config/trusted-tools.json` | Auditable declaration of the sole enabled tool. |
| `sage_forge/schemas/tool-registry.schema.json` | Complete trusted-tool policy contract. |
| `sage_forge/schemas/agent-registry.schema.json` | Declarative, provenance-hashed agent contract. |
| `sage_forge/schemas/job-request.schema.json` | Explicit owner-approved job request contract. |
| `sage_forge/tests/__init__.py` | Forge test package marker. |
| `sage_forge/tests/test_forge.py` | TLS vertical slice, revocation, pin, pairing, safety, schema, and restart tests. |
| `sage_forge/README.md` | Dell install/run/pair/test/firewall instructions. |

## Regression tests

| File | Reason |
|---|---|
| `sage_tests/test_android_compat_v1_21.py` | Preserve package/version assertions through 1.25. |
| `sage_tests/test_brain_watchdog_v1_22.py` | Preserve Brain and release identity assertions through 1.25. |
| `sage_tests/test_continuity_report_v1_24.py` | Preserve continuity/package/version assertions through 1.25. |
| `sage_tests/test_stabilization_v1_21.py` | Preserve overlay behaviors and current release identity. |
| `sage_tests/test_wake_profiles_and_recognition_v1_23.py` | Preserve wake/recognition behavior and current release identity. |
| `sage_tests/test_number_overlay_release_v1_24_1.py` | Verify the overlay window-churn fix remains in the generated app. |
| `sage_tests/test_sage_release_v1_24_2.py` | Cover overlay clear reasons, speech retry/final selection, memory, appearance, and identity. |
| `sage_tests/test_self_repair_v1_25.py` | Cover repair bundles, authority states, schema/security constraints, and identity. |
| `sage_tests/test_workbench_v1_25.py` | Cover functional Workbench vertical slices and safety boundaries. |
| `sage_tests/test_sage_forge_v1_25.py` | Confirm Forge code is generated, wired into app/manifest/voice/CI, and preserves TLS/identity controls. |

## Schemas, audit, and reports

| File | Reason |
|---|---|
| `sage_repair/repair-packet.schema.json` | Constrained owner-approved code-defect request schema. |
| `SAGE_SELF_REPAIR_ARCHITECTURE.md` | Supervised repair flow and artifact-only GitHub pipeline. |
| `SAGE_SELF_REPAIR_THREAT_REVIEW.md` | Repair threat analysis and mitigations. |
| `SAGE_AGENT_FACTORY_AUDIT.md` | Exact upstream component/dependency/license/compatibility audit and decision matrix. |
| `SAGE_FORGE_ARCHITECTURE.md` | Tablet/Dell boundaries, API, state model, and staged future capabilities. |
| `SAGE_FORGE_PAIRING_PROTOCOL.md` | Pairing, authentication, replay, revocation, and certificate lifecycle. |
| `SAGE_FORGE_THREAT_MODEL.md` | Forge assets, threats, controls, residual risks, and prohibitions. |
| `SAGE_FORGE_DELL_DIAGNOSTICS_PLAN.md` | Safe fixed-tool plan for requested Dell/Windows/network collectors and confidence contracts. |
| `THIRD_PARTY_NOTICES.md` | Upstream revision, visible authorship/license metadata, and no-copy decision. |
| `SAGE_1_25_ROOT_CAUSE_REPORT.md` | Confirmed overlay/speech/memory causes, assumptions, and related CI defect. |
| `SAGE_FORGE_VERIFICATION_REPORT.md` | Actual build/test/lint/signing/package/alignment/integrity evidence. |
| `SAGE_FORGE_COMPLETION_STATUS.md` | Honest complete/partial/deferred capability inventory. |
| `SAGE_FORGE_TABLET_ACCEPTANCE_CHECKLIST.md` | Real Dell/tablet install, pairing, job, revoke, and regression checks. |
| `SAGE_FORGE_DRAFT_PR.md` | Review-ready pull request draft; no PR was opened. |
| `SAGE_FORGE_MODIFIED_FILES.md` | This release-source ledger. |
