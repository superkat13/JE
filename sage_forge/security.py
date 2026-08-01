"""Pairing and request-authentication primitives for Sage Forge."""

from __future__ import annotations

import hashlib
import hmac
import secrets
import ssl
import time
from dataclasses import dataclass
from pathlib import Path


MAX_CLOCK_SKEW_SECONDS = 120


def sha256_hex(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def certificate_sha256(path: Path) -> str:
    pem = path.read_text(encoding="ascii")
    der = ssl.PEM_cert_to_DER_cert(pem)
    return sha256_hex(der)


def normalize_fingerprint(value: str) -> str:
    normalized = value.lower().replace(":", "").strip()
    if len(normalized) != 64 or any(ch not in "0123456789abcdef" for ch in normalized):
        raise ValueError("certificate fingerprint must be 64 hexadecimal characters")
    return normalized


@dataclass(frozen=True)
class PairingGrant:
    code_digest: str
    expires_monotonic: float

    @classmethod
    def create(cls, code: str, lifetime_seconds: int) -> "PairingGrant":
        if not (6 <= len(code) <= 32) or not code.isdigit():
            raise ValueError("pairing code must contain 6 to 32 digits")
        if not (30 <= lifetime_seconds <= 900):
            raise ValueError("pairing window must be between 30 and 900 seconds")
        return cls(sha256_hex(code.encode("ascii")), time.monotonic() + lifetime_seconds)

    def accepts(self, code: str) -> bool:
        if time.monotonic() > self.expires_monotonic:
            return False
        return hmac.compare_digest(self.code_digest, sha256_hex(code.encode("utf-8")))


def new_pairing_code() -> str:
    return f"{secrets.randbelow(100_000_000):08d}"


def new_device_token() -> str:
    return secrets.token_urlsafe(32)


def new_identifier(prefix: str) -> str:
    return f"{prefix}_{secrets.token_hex(12)}"


def validate_fresh_request(timestamp: str, now: int | None = None) -> int:
    try:
        parsed = int(timestamp)
    except (TypeError, ValueError) as error:
        raise ValueError("request timestamp is invalid") from error
    current = int(time.time()) if now is None else now
    if abs(current - parsed) > MAX_CLOCK_SKEW_SECONDS:
        raise ValueError("request timestamp is outside the allowed clock window")
    return parsed
