# Sage Forge architecture

## Release boundary

Sage Commander remains `com.pineapple.sagecommander.stable`, version `1.25.0` / code `37`. Forge is a separate, owner-run Python 3.12 service on the Dell. It does not install itself, grant Android authority, change Sage code, or expose a generic shell.

The implemented vertical slice is:

1. The owner starts Forge on an exact private/loopback address and opens a 30–900 second, one-use pairing window.
2. Commander verifies the Forge certificate's SHA-256 pin and the HTTPS hostname/IP subject alternative name.
3. The owner reviews Sage's confirmation sheet and submits the displayed one-time code.
4. Forge returns a random device token once; Forge stores only its SHA-256, while Commander encrypts the token with Android Keystore AES-GCM.
5. The owner reviews a second confirmation for `system.info`.
6. Forge validates the device token, timestamp, one-use nonce, tool allowlist, strict empty input, platform, risk and approval fields.
7. Forge executes Python platform APIs—no shell—then validates the structured output schema while storing progress and logs in SQLite.
8. Commander polls the job, displays logs/progress, and stores the structured result.
9. The owner can cancel an active job or revoke trust; revocation deletes Forge nonces and disables the token, then Commander deletes its Keystore key.

## Modules

| Module | Responsibility | Trust boundary |
|---|---|---|
| `sage_forge/server.py` | TLS HTTP endpoints, body limits, auth, pairing and ownership checks | Treats every network byte as untrusted |
| `sage_forge/security.py` | pairing grant, random IDs/tokens, cert fingerprint, clock window | No agent input reaches cryptographic configuration |
| `sage_forge/store.py` | SQLite devices, token hashes, nonces, jobs, logs, results and restart recovery | Transactional durable state; no secrets in logs |
| `sage_forge/tools.py` | trusted definitions, input validation, deterministic Safety Guard and Tool Runner | Only local Python registrations can execute |
| `sage_forge/agents.py` | non-executable AgentSpec validation and tool-request intersection | Agent request cannot register/grant a tool |
| `SageForgeClient.java` | pinned TLS API calls and replay headers | Uses the stored Keystore token only for paired Forge |
| `SageForgeStore.java` | Android Keystore AES-GCM token protection and result persistence | Token is never stored in plaintext preferences |
| `SageForgeActivity.java` | pairing/approval/progress/log/result/revoke UI | All sensitive actions use Sage's shared owner confirmation |

## API and state

| Method/path | Authorization | Result |
|---|---|---|
| `GET /v1/health` | TLS only | service/version health, no host data |
| `POST /v1/pair` | pinned TLS + active one-use code | one-time device token and revocable device ID |
| `GET /v1/tools` | paired token + fresh timestamp + nonce | sanitized trusted registry metadata |
| `POST /v1/jobs` | paired request plus explicit Commander approval context | queued job ID |
| `GET /v1/jobs/{id}` | paired job owner only | progress, stage, logs, status and structured result |
| `POST /v1/jobs/{id}/cancel` | paired job owner only | cancellation request |
| `POST /v1/devices/current/revoke` | currently paired device | irreversible trust revocation for that token |

Job states are `queued`, `running`, `completed`, `failed`, `cancelled`, and `interrupted`. On process restart, any `running` job becomes `interrupted` with explicit evidence. Automatic replay is intentionally deferred until each tool declares idempotence and recovery policy.

## Future Dell capability layers

1. Repository tools: Sage-repository-only checkout inspection, isolated branch creation, validated patch application, Gradle/native build, tests/lint, APK/signing/package/ZIP/alignment verification and artifact hashing. Every mutating tool gets a fixed implementation, path confinement and separate owner approval.
2. Local models and retrieval: Dell-only embeddings over owner-selected Sage source/reports/docs; non-executable vector cache; strict source hashes; result ranking as advice only.
3. Windows baseline: fixed collectors for listening ports/processes, services, startup, event summaries, firewall, Defender status/approved scan, installed software, patch status and hashes.
4. Authorized local network: private-subnet-only TCP inventory, conservative banners, TLS/HTTP/DNS/SMB visibility, baseline/change alerts, stop/cancel, confidence labels and CVE lookup only after adequate product/version evidence.

These future capabilities are not registered in `trusted-tools.json`, so they cannot be requested or executed in this release.

## Operational framework

All future tools must use the existing lifecycle: validated request → deterministic Safety Guard → concurrency semaphore → bounded timeout → progress/log callbacks → structured output → terminal state. Artifacts will be content-addressed, size-limited and bound to the requesting device/job. Downloaded agents may request a tool ID but cannot provide code, implementation paths, shell, patches, desktop actions, network scopes or permission grants.
