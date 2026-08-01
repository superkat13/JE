# Sage Forge threat model

## Assets

Sage user data, Android app continuity/signing identity, Forge device token, Dell TLS private key, source repositories, build signing material, job results/logs and authorized network observations.

## Trust boundaries and controls

| Threat | Implemented control | Residual risk / next control |
|---|---|---|
| LAN interception or fake Forge | TLS 1.2+, exact cert pin, normal hostname/SAN verification | Owner can copy the wrong pin; future QR transfer should display both target and pin |
| Pairing guessing | random eight-digit code, explicit short window, five-failure cap, one successful use | Eight digits is not a password; pinning and short local approval are mandatory |
| Stolen tablet token | Android Keystore AES-GCM; Forge stores only token hash; revocation | A compromised unlocked tablet can act as Sage; add hardware attestation as optional signal |
| Request replay | ±120-second clock window and durable one-use nonce | Dell/tablet clock skew can reject valid jobs; UI should expose clock diagnosis |
| Downloaded agent executes code | strict AgentSpec fields; executable fields forbidden; requested tools intersect local registry | Prompt injection can still propose bad actions; owner confirmation and Safety Guard remain authoritative |
| Arbitrary command/tool abuse | no generic command endpoint; local callable registry; strict inputs; platform/risk/confirmation checks | Future shell-like build tools require fixed subcommands and repository path confinement |
| Public scanning | no network scanner registered; server bind restricted to private/loopback IP | Future scanner must derive private subnet, show range, rate limit and refuse public ranges |
| Secret/log leakage | token hashes only in SQLite; server suppresses request-line logs; bounded sanitized job messages | Tool outputs can contain private facts; add field-level redaction before repair export |
| Cross-device job access | every job lookup/cancel includes authenticated `device_id` | Multi-owner role policy is deferred |
| Resource exhaustion | 64 KiB request cap, 256 KiB Android response cap, timeouts and per-tool concurrency | Job retention/quotas and server-wide rate limits are required before more tools |
| Crash/interrupted mutation | running jobs become `interrupted`; current tool is read-only | Mutating tools need idempotency keys, transaction journals and rollback plans |
| Malicious model/vector cache | no upstream models/data/caches bundled; no untrusted deserialization | Future retrieval must use non-executable formats and content hashes |
| Forge exposed by firewall | exact private interface bind; TLS/auth required | Owner must review Windows Firewall profile/rule; service discovery is deferred |
| Supply-chain/license contamination | exact source revision audited; no upstream code/data/model copied | Obtain explicit license/provenance clarification before direct reuse |

## Explicitly prohibited operations

The current schema and runner provide no credential guessing, exploit execution, persistence, evasion, denial of service, public-target scanning, arbitrary script, agent-defined executable, silent install, silent permission grant, automatic merge or automatic release path. Adding a schema record alone cannot create an implementation.

## Security tests executed

The Forge suite covers wrong certificate pins, consumed pairing windows, unknown tool IDs, missing approval, injected command fields, declarative-agent executable fields, requested-but-unregistered tools, restart interruption, real result/log persistence and post-revocation rejection.
