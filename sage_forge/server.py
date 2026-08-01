"""TLS HTTP service for the first Sage Commander ↔ Sage Forge vertical slice."""

from __future__ import annotations

import json
import ssl
import threading
import time
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit

from .security import PairingGrant, new_device_token, new_identifier, validate_fresh_request
from .store import ForgeStore
from .tools import ToolRunner


MAX_REQUEST_BYTES = 65_536


class ApiError(Exception):
    def __init__(self, status: int, code: str, message: str):
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message


class ForgeApplication:
    def __init__(self, store: ForgeStore, pairing_grant: PairingGrant | None = None):
        self.store = store
        self.runner = ToolRunner(store)
        self._pairing_grant = pairing_grant
        self._pairing_lock = threading.Lock()
        self._pair_failures = 0

    def close(self) -> None:
        self.runner.close()
        self.store.close()

    def pair(self, request: dict[str, Any]) -> dict[str, Any]:
        if set(request) != {"pairing_code", "device_name"}:
            raise ApiError(400, "invalid_pair_request", "pair request fields are invalid")
        name = request.get("device_name")
        code = request.get("pairing_code")
        if not isinstance(name, str) or not (1 <= len(name.strip()) <= 80):
            raise ApiError(400, "invalid_device_name", "device name is invalid")
        if not isinstance(code, str):
            raise ApiError(400, "invalid_pairing_code", "pairing code is invalid")
        with self._pairing_lock:
            grant = self._pairing_grant
            if grant is None or self._pair_failures >= 5 or not grant.accepts(code):
                self._pair_failures += 1
                raise ApiError(403, "pairing_denied", "pairing window is closed or code is invalid")
            device_id = new_identifier("device")
            token = new_device_token()
            self.store.add_device(device_id, name.strip(), token)
            self._pairing_grant = None
        return {
            "schema_version": "1.0",
            "device_id": device_id,
            "device_token": token,
            "trust": "paired_and_revocable",
        }

    def authenticate(self, headers: Any) -> dict[str, Any]:
        authorization = headers.get("Authorization", "")
        if not authorization.startswith("SageToken "):
            raise ApiError(401, "authentication_required", "a paired Sage token is required")
        token = authorization[len("SageToken "):].strip()
        device = self.store.authenticate(token)
        if not device:
            raise ApiError(401, "authentication_failed", "device trust is absent or revoked")
        try:
            timestamp = validate_fresh_request(headers.get("X-Sage-Timestamp", ""))
        except ValueError as error:
            raise ApiError(401, "stale_request", str(error)) from error
        nonce = headers.get("X-Sage-Nonce", "")
        if not self.store.accept_nonce(device["device_id"], nonce, timestamp):
            raise ApiError(409, "replayed_request", "request nonce is invalid or was already used")
        return device

    def create_job(self, device: dict[str, Any], request: dict[str, Any]) -> dict[str, Any]:
        allowed = {"tool_id", "input", "owner_approved", "approval_context"}
        if not isinstance(request, dict) or set(request) - allowed:
            raise ApiError(400, "invalid_job", "job fields are invalid")
        context = request.get("approval_context")
        if not isinstance(context, dict) or context.get("surface") != "sage_commander":
            raise ApiError(403, "approval_missing", "Commander owner-approval context is required")
        try:
            definition = self.runner.registry.resolve(str(request.get("tool_id", "")))
            self.runner.registry.validate_input(definition, request.get("input", {}))
            self.runner.safety_guard.authorize(definition, request)
        except (ValueError, PermissionError) as error:
            raise ApiError(403, "job_denied", str(error)) from error
        job_id = new_identifier("job")
        self.store.create_job(job_id, device["device_id"], definition.tool_id, request["input"])
        self.store.add_log(job_id, "APPROVAL", "Owner approved on paired Sage Commander")
        self.runner.submit(job_id, request)
        return {"job_id": job_id, "status": "queued", "tool_id": definition.tool_id}


class ForgeRequestHandler(BaseHTTPRequestHandler):
    server_version = "SageForge/0.1"
    sys_version = ""

    @property
    def app(self) -> ForgeApplication:
        return self.server.application  # type: ignore[attr-defined]

    def log_message(self, fmt: str, *args: Any) -> None:
        # Avoid request details/tokens in default stderr logs. Forge job logs are structured.
        return

    def do_GET(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        self._dispatch("GET")

    def do_POST(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        self._dispatch("POST")

    def _dispatch(self, method: str) -> None:
        try:
            path = urlsplit(self.path).path
            if method == "GET" and path == "/v1/health":
                self._send(200, {"service": "sage-forge", "version": "0.1.0", "tls": True})
                return
            if method == "POST" and path == "/v1/pair":
                self._send(200, self.app.pair(self._read_json()))
                return
            device = self.app.authenticate(self.headers)
            if method == "GET" and path == "/v1/tools":
                self._send(200, {"tools": self.app.runner.registry.public_definitions()})
                return
            if method == "POST" and path == "/v1/jobs":
                self._send(HTTPStatus.ACCEPTED, self.app.create_job(device, self._read_json()))
                return
            if path.startswith("/v1/jobs/"):
                parts = path.strip("/").split("/")
                if len(parts) not in (3, 4):
                    raise ApiError(404, "not_found", "endpoint not found")
                job_id = parts[2]
                if method == "GET" and len(parts) == 3:
                    job = self.app.store.get_job(job_id, device["device_id"])
                    if not job:
                        raise ApiError(404, "job_not_found", "job not found")
                    self._send(200, job)
                    return
                if method == "POST" and len(parts) == 4 and parts[3] == "cancel":
                    accepted = self.app.store.request_cancel(job_id, device["device_id"])
                    if not accepted:
                        raise ApiError(409, "cancel_not_available", "job is absent or already finished")
                    self.app.store.add_log(job_id, "NOTICE", "Cancellation requested by owner")
                    self._send(202, {"job_id": job_id, "cancel_requested": True})
                    return
            if method == "POST" and path == "/v1/devices/current/revoke":
                if not self.app.store.revoke(device["device_id"]):
                    raise ApiError(409, "already_revoked", "device was already revoked")
                self._send(200, {"device_id": device["device_id"], "revoked": True})
                return
            raise ApiError(404, "not_found", "endpoint not found")
        except ApiError as error:
            self._send(error.status, {"error": error.code, "message": error.message})
        except (json.JSONDecodeError, UnicodeDecodeError):
            self._send(400, {"error": "invalid_json", "message": "request body is not valid JSON"})
        except Exception:
            self._send(500, {"error": "internal_error", "message": "Forge could not complete the request"})

    def _read_json(self) -> dict[str, Any]:
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError as error:
            raise ApiError(400, "invalid_length", "content length is invalid") from error
        if length <= 0 or length > MAX_REQUEST_BYTES:
            raise ApiError(413, "body_size", "request body size is invalid")
        body = self.rfile.read(length)
        value = json.loads(body.decode("utf-8"))
        if not isinstance(value, dict):
            raise ApiError(400, "invalid_json_type", "request JSON must be an object")
        return value

    def _send(self, status: int, value: dict[str, Any]) -> None:
        body = json.dumps(value, sort_keys=True, separators=(",", ":")).encode("utf-8")
        self.send_response(int(status))
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(body)


class SageForgeServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def handle_error(self, request: Any, client_address: Any) -> None:
        # A client that rejects a certificate pin closes before sending HTTP. That is expected.
        return

    def __init__(self, address: tuple[str, int], application: ForgeApplication,
                 certificate: Path, private_key: Path):
        super().__init__(address, ForgeRequestHandler)
        self.application = application
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(str(certificate), str(private_key))
        self.socket = context.wrap_socket(self.socket, server_side=True)
