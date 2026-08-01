# Sage Forge pairing protocol 1.0

## Preconditions

- Forge binds only an exact loopback or RFC1918/private address selected by the owner.
- The Forge certificate contains that exact IP/DNS value as a subject alternative name.
- Commander receives the certificate SHA-256 out of band from the Dell screen/terminal.
- The owner explicitly starts Forge with `--open-pairing 30..900`; Forge displays a random eight-digit, one-use code locally.

## Exchange

1. Commander opens TLS 1.2+ and accepts the server only when both normal HTTPS hostname verification and leaf-certificate SHA-256 pinning succeed.
2. Commander shows exact target, permissions, egress and reversibility, then the owner approves.
3. Commander sends `POST /v1/pair` with `device_name` and `pairing_code`. The code is never stored on Commander.
4. Forge compares a digest in constant time, limits a window to five failures, consumes the grant after one successful pairing, creates a random device ID and 256-bit-class URL-safe token, and stores only `SHA-256(token)`.
5. Forge returns the token once inside the pinned TLS channel. Commander encrypts it with a non-exportable Android Keystore AES-GCM key.

## Authenticated requests

Commander sends `Authorization: SageToken <token>`, `X-Sage-Timestamp` (Unix seconds), and a cryptographically random `X-Sage-Nonce`. Forge accepts only active token hashes, timestamps within ±120 seconds, and never-before-seen nonces. Nonces expire from storage after five minutes. TLS provides message integrity and confidentiality; the nonce/timestamp rejects accidental or malicious application-layer replay.

## Revocation and recovery

`POST /v1/devices/current/revoke` sets `revoked_at` transactionally and deletes device nonces before success is returned. Commander deletes its pairing preferences and Keystore entry only after Forge confirms revocation. If the Dell cannot be reached, Commander explicitly says trust was not confirmed revoked; the owner can delete/revoke the device in Forge's SQLite administration tooling in a future release. Re-pairing always requires a new locally opened pairing window and new certificate verification.

## Certificate lifecycle

Set `SAGE_FORGE_HOST` only in the owner's local environment, then run `python -m sage_forge.create_identity`. The script refuses overwrites and generates a server-auth certificate with a matching SAN. A changed private address/name or replaced certificate requires explicit re-pairing. The private key never leaves the owner-controlled Forge device.
