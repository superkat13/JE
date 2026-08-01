# Sage Forge Dell and authorized-network diagnostics plan

Only `system.info` is enabled in Forge 0.1. The following registry entries are design-approved candidates, not executable or advertised tools in this release. Each implementation must be bundled locally, content-hashed, schema validated, cancel-aware, rate limited, and called only through the Tool Runner and deterministic Safety Guard.

## Planned fixed collectors

| Proposed tool ID | Evidence source and output | Scope / confirmation | Status |
|---|---|---|---|
| `network.tcp_inventory` | Async TCP connect results for an owner-confirmed private subnet/host; IP, port, latency, observed state | Current private subnet only by default; always confirm exact range; low concurrency/rate | Deferred |
| `network.service_observe` | Conservative protocol greeting or standard negotiation; raw bounded evidence plus inferred product/version/confidence | Only ports found by an approved inventory; never exploit/probe credentials | Deferred |
| `network.tls_inspect` | Standard TLS handshake: leaf/chain subjects, SANs, validity, key/signature, negotiated protocol/cipher | Explicit private target; no downgrade forcing beyond safe compatibility checks | Deferred |
| `network.http_headers` | One bounded HEAD/GET response, status, redirect chain, headers, security-header presence | Explicit private URL; no form submission, auth, cookies, or crawling | Deferred |
| `network.dns_lookup` | System resolver A/AAAA/PTR answers, resolver errors, elapsed time | Hostname or private IP; reverse lookup allowed; no bulk enumeration | Deferred |
| `windows.smb_visibility` | Windows SMB client/API-visible shares and endpoints; no share mounting or authentication attempts | Local Dell or explicit private host; always confirm remote target | Deferred |
| `windows.listening_ports` | Windows IP Helper API / signed bundled collector correlating local address/port/PID/process path/hash | Local Dell; owner confirmation before exposing process paths to tablet | Deferred |
| `windows.services` | Service Control Manager name, display name, state, start type, signed path evidence | Local Dell read-only | Deferred |
| `windows.startup` | Documented Run keys, Startup folders, scheduled-logon entries with source/path/hash | Local Dell read-only; label incompleteness | Deferred |
| `windows.event_summary` | Windows Event Log API bounded summaries for selected operational/security channels | Exact channels/time window shown; sensitive fields redacted | Deferred |
| `windows.firewall_status` | Windows Firewall API profiles, enabled/default states, bounded rule summary | Local Dell read-only; no rule change in diagnostic tool | Deferred |
| `windows.defender_status` | Microsoft Defender API/cmdlet fixed fields: service, engine/signature versions, protection states | Local Dell read-only | Deferred |
| `windows.defender_scan` | Launch only documented Quick/Custom scan mode with fixed validated path | High visibility, always confirm; progress/cancel where Windows permits | Deferred |
| `windows.software_inventory` | MSI/registry package inventory without invoking Win32_Product; name/version/publisher/source | Local Dell read-only | Deferred |
| `windows.patch_status` | Windows Update API installed/pending update facts and last scan evidence | Local Dell read-only; never install automatically | Deferred |
| `file.hash` | Streaming SHA-256/SHA-512 of one owner-selected local file with size/path evidence | Exact canonical path; read only; always confirm if result leaves Dell | Deferred on Forge; implemented locally in Commander |
| `baseline.capture` | Canonical JSON snapshot of approved collectors, schema version, timestamp, host ID, content hash | Owner-selected modules; encrypted local persistence | Deferred |
| `baseline.compare` | Field-aware added/removed/changed facts, especially newly exposed ports/services | Two owner-selected snapshots; no collection side effect | Deferred |
| `cve.lookup` | Query only by sufficiently identified product/version/CPE; return source/date/match confidence | Explicit egress confirmation; never infer a CVE from port number alone | Deferred |

## Required result contract

Every observation must include `evidence`, `confidence`, `severity`, `explanation`, `remediation`, collection timestamp, collector/version, scope, and errors/limitations. Facts directly returned by OS/network APIs are labeled confirmed observations. Device type, operating system, product, and service identity remain inferences unless protocol or local API evidence is adequate.

## Windows execution boundary

Prefer native Windows APIs from signed/local code. Where a Microsoft PowerShell cmdlet is the only stable interface, Forge will invoke a bundled, reviewable fixed script by content hash with an enumerated parameter schema. The agent/job packet cannot provide script text, executable paths, command fragments, switches, pipelines, environment changes, working directories, or redirections. Tool output is parsed into a strict JSON schema before it can be stored or transferred.

## Network safety defaults

- Derive and display the current private subnet, cap the default at one `/24`, and require confirmation before any packets are sent.
- Refuse globally routable targets by default; any future exception requires a separate owner policy outside agent data.
- Conservative concurrency, per-host delay, bounded ports, short timeouts, byte caps, immediate cooperative cancellation, and no hidden/background scheduling.
- No credential attempts, password guessing, exploit execution, persistence, evasion, denial of service, arbitrary payloads, or automatic tool download/authority.
