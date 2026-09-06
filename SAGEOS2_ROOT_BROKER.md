# SageOS 2 root broker design

## Goal

Give Sage real root-backed system capability without running the large AI application, speech engines, browser/tooling, or network-facing code as uid 0.

## Process split

`Sage app/runtime` -> typed `RootBrokerRequest` -> `Sage root broker` -> Android/Linux privileged operation -> typed result + audit ID.

The broker is intentionally small. It should have no LLM, no speech recognition, no web client, no arbitrary plugin loader, and no UI.

## Initial privileged operations

- package install/uninstall/enable/disable for owner-selected packages
- protected Android settings writes that legitimately require privileged authority
- reboot/shutdown/recovery requests
- file owner/group/mode operations for Sage-owned system integration
- controlled system-service restart where needed for SageOS maintenance
- root broker health/version query

Operations should be represented as typed requests rather than string-concatenated shell commands. If a future operation truly requires shell execution, add a dedicated reviewed operation rather than creating a universal `run anything` RPC.

## Authorization and audit

- Accept requests only from the Sage package/signing identity or platform-assigned UID.
- Validate operation type and arguments before execution.
- Return an audit ID for every request.
- Keep a bounded local audit log with timestamp, caller UID, operation, result code, and duration. Do not log secrets or large content payloads.
- Deny unknown operations by default.

## Android integration destination

The final SageOS image should start the broker from init and place it in its own SELinux domain. The client transport can be Binder/AIDL or a protected local socket; choose after the tablet image/build route is proven. The client contract in `sageos2/.../root/RootBroker.kt` stays transport-independent.

## Development builds

Normal APK builds contain only the client contract and an unavailable implementation. They must report root as unavailable until a real broker handshake succeeds. No Shizuku fallback.
