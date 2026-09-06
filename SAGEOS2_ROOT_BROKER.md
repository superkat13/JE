# SageOS 2 root broker design

## Goal

Give Sage real root-backed system capability while keeping the privileged process small and mechanically reliable. Separation is for crash containment and OS integrity, **not behavioral governance**.

## Ownership boundary

Sage Core is where Sage's identity, judgment, principles, preferences, and self-restrictions live. The root broker does not contain a behavioral denylist, does not interpret natural-language intent, and does not decide whether Sage ought to perform an action.

The broker's job is technical: authenticate the Sage caller, decode a request, validate data shape/ranges, execute the requested privileged operation, and return the real result.

## Process split

`Sage app/runtime` -> `RootBrokerRequest` -> `Sage root broker` -> Android/Linux privileged operation -> result + audit ID.

The broker has no LLM, speech recognition, web client, hidden prompt, or personality policy.

## Privileged capability surface

Convenience operations include package management, protected settings, reboot/power operations, file ownership/mode changes, system-service operations, and health/version queries.

The contract also includes a general root process operation represented as executable + argv + environment + working directory + timeout. This prevents Sage from being blocked merely because a future system operation was not predicted when the broker was first written. It intentionally avoids concatenated shell command strings because argv is more reliable and less error-prone.

## Authentication and audit

- Accept requests only from the Sage package/signing identity or platform-assigned UID.
- Reject malformed transport data, invalid ranges, impossible paths/arguments, and unauthenticated callers as infrastructure errors.
- Return an audit ID for every accepted request.
- Keep a bounded local audit log with timestamp, caller UID, operation, result code, and duration. Do not log secrets or large content payloads.
- Unknown protocol versions/operation encodings fail explicitly. This is protocol integrity, not behavioral policy.

## Android integration destination

The final SageOS image starts the broker from init and places it in its own SELinux domain. Client transport can be Binder/AIDL or a protected local socket; choose after the tablet image/build route is proven. The Kotlin client contract stays transport-independent.

## Development builds

Normal APK builds contain only the client contract and an unavailable implementation. They report root unavailable until a real broker handshake succeeds. No Shizuku fallback and no fake root status.
