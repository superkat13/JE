"""Reference pinned-TLS client used for Forge integration tests and Dell smoke tests."""

from __future__ import annotations

import hashlib
import http.client
import json
import secrets
import ssl
import time
from typing import Any
from urllib.parse import urlsplit

from .security import normalize_fingerprint


class ForgeClientError(RuntimeError):
    pass


class ForgeClient:
    def __init__(self, base_url: str, certificate_fingerprint: str,
                 token: str | None = None):
        parsed = urlsplit(base_url)
        if parsed.scheme != "https" or parsed.query or parsed.fragment or parsed.path not in ("", "/"):
            raise ValueError("Forge URL must be an HTTPS origin without a path")
        self.host = parsed.hostname or ""
        self.port = parsed.port or 443
        if not self.host:
            raise ValueError("Forge host is required")
        self.fingerprint = normalize_fingerprint(certificate_fingerprint)
        self.token = token

    def request(self, method: str, path: str, value: dict[str, Any] | None = None,
                authenticated: bool = True) -> dict[str, Any]:
        body = None if value is None else json.dumps(value, separators=(",", ":"), sort_keys=True).encode("utf-8")
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.check_hostname = False
        context.verify_mode = ssl.CERT_NONE
        connection = http.client.HTTPSConnection(self.host, self.port, context=context, timeout=10)
        connection.connect()
        certificate = connection.sock.getpeercert(binary_form=True) if connection.sock else b""
        observed = hashlib.sha256(certificate).hexdigest()
        if not secrets.compare_digest(observed, self.fingerprint):
            connection.close()
            raise ForgeClientError("Forge certificate pin mismatch")
        headers = {"Accept": "application/json"}
        if body is not None:
            headers["Content-Type"] = "application/json"
        if authenticated:
            if not self.token:
                raise ForgeClientError("paired token is required")
            headers.update({
                "Authorization": f"SageToken {self.token}",
                "X-Sage-Timestamp": str(int(time.time())),
                "X-Sage-Nonce": secrets.token_urlsafe(18),
            })
        connection.request(method, path, body=body, headers=headers)
        response = connection.getresponse()
        raw = response.read()
        connection.close()
        try:
            result = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise ForgeClientError(f"Forge returned invalid JSON ({response.status})") from error
        if not 200 <= response.status < 300:
            raise ForgeClientError(f"Forge rejected request ({response.status}): {result.get('message', result)}")
        return result

    def pair(self, pairing_code: str, device_name: str) -> dict[str, Any]:
        result = self.request("POST", "/v1/pair", {
            "pairing_code": pairing_code,
            "device_name": device_name,
        }, authenticated=False)
        self.token = result["device_token"]
        return result

    def system_info(self) -> str:
        result = self.request("POST", "/v1/jobs", {
            "tool_id": "system.info",
            "input": {},
            "owner_approved": True,
            "approval_context": {"surface": "sage_commander", "action": "system information"},
        })
        return result["job_id"]

    def job(self, job_id: str) -> dict[str, Any]:
        return self.request("GET", f"/v1/jobs/{job_id}")

    def cancel(self, job_id: str) -> dict[str, Any]:
        return self.request("POST", f"/v1/jobs/{job_id}/cancel", {})

    def revoke(self) -> dict[str, Any]:
        return self.request("POST", "/v1/devices/current/revoke", {})
