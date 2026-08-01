"""Trusted local tool registry, safety guard, and runner."""

from __future__ import annotations

import os
import platform
import shutil
import socket
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from typing import Any, Callable

from .store import ForgeStore


Progress = Callable[[str, int, str], None]


@dataclass(frozen=True)
class ToolDefinition:
    tool_id: str
    display_name: str
    purpose: str
    supported_platforms: tuple[str, ...]
    implementation: Callable[[dict[str, Any], Progress, Callable[[], bool]], dict[str, Any]]
    input_schema: dict[str, Any]
    output_schema: dict[str, Any]
    required_permissions: tuple[str, ...]
    risk_level: str
    confirmation_policy: str
    timeout_seconds: int
    concurrency_limit: int
    network_scope: str
    data_leaves_device: bool
    audit_requirements: tuple[str, ...]


class ToolRegistry:
    def __init__(self) -> None:
        self._tools: dict[str, ToolDefinition] = {}

    def register(self, definition: ToolDefinition) -> None:
        if definition.tool_id in self._tools:
            raise ValueError(f"duplicate tool ID: {definition.tool_id}")
        if definition.risk_level not in {"low", "moderate", "high", "prohibited"}:
            raise ValueError("invalid tool risk level")
        if definition.confirmation_policy not in {"always", "first_use", "never"}:
            raise ValueError("invalid confirmation policy")
        if not (1 <= definition.timeout_seconds <= 3600):
            raise ValueError("invalid tool timeout")
        if not (1 <= definition.concurrency_limit <= 8):
            raise ValueError("invalid tool concurrency")
        for label, schema in (("input", definition.input_schema),
                              ("output", definition.output_schema)):
            if not isinstance(schema, dict) or schema.get("type") != "object":
                raise ValueError(f"{label} schema must describe an object")
            properties = schema.get("properties")
            required = schema.get("required", [])
            if not isinstance(properties, dict) or not isinstance(required, list):
                raise ValueError(f"{label} schema properties or required fields are invalid")
            if not set(required) <= set(properties):
                raise ValueError(f"{label} schema requires undeclared fields")
        self._tools[definition.tool_id] = definition

    def resolve(self, tool_id: str) -> ToolDefinition:
        try:
            return self._tools[tool_id]
        except KeyError as error:
            raise PermissionError(f"tool is not allowlisted: {tool_id}") from error

    def validate_input(self, definition: ToolDefinition, value: Any) -> dict[str, Any]:
        return self._validate_object(definition.input_schema, value, "tool input")

    def validate_output(self, definition: ToolDefinition, value: Any) -> dict[str, Any]:
        return self._validate_object(definition.output_schema, value, "tool output")

    @staticmethod
    def _validate_object(schema: dict[str, Any], value: Any,
                         label: str) -> dict[str, Any]:
        if not isinstance(value, dict):
            raise ValueError(f"{label} must be an object")
        properties = set(schema.get("properties", {}))
        required = set(schema.get("required", []))
        unknown = set(value) - properties
        missing = required - set(value)
        if unknown and schema.get("additionalProperties") is False:
            raise ValueError(f"unknown {label} fields: {sorted(unknown)}")
        if missing:
            raise ValueError(f"missing {label} fields: {sorted(missing)}")
        return value

    def public_definitions(self) -> list[dict[str, Any]]:
        return [
            {
                "tool_id": tool.tool_id,
                "display_name": tool.display_name,
                "purpose": tool.purpose,
                "supported_platform": list(tool.supported_platforms),
                "implementation": f"trusted:{tool.implementation.__module__}.{tool.implementation.__name__}",
                "input_schema": tool.input_schema,
                "output_schema": tool.output_schema,
                "required_permissions": list(tool.required_permissions),
                "risk_level": tool.risk_level,
                "confirmation_policy": tool.confirmation_policy,
                "timeout_seconds": tool.timeout_seconds,
                "concurrency_limit": tool.concurrency_limit,
                "network_scope": tool.network_scope,
                "data_leaves_device": tool.data_leaves_device,
                "audit_requirements": list(tool.audit_requirements),
            }
            for tool in self._tools.values()
        ]


class SafetyGuard:
    def authorize(self, definition: ToolDefinition, request: dict[str, Any]) -> None:
        if definition.risk_level == "prohibited":
            raise PermissionError("prohibited tools cannot run")
        if request.get("owner_approved") is not True:
            raise PermissionError("explicit owner approval is required")
        if request.get("tool_id") != definition.tool_id:
            raise PermissionError("tool identity changed during authorization")
        if any(key in request for key in ("command", "shell", "script", "executable")):
            raise PermissionError("arbitrary execution fields are forbidden")
        current = platform.system().lower()
        if current not in definition.supported_platforms:
            raise PermissionError(f"tool does not support platform: {current}")


def collect_system_info(_: dict[str, Any], progress: Progress,
                        cancelled: Callable[[], bool]) -> dict[str, Any]:
    progress("Reading operating system identity", 15, "Using Python platform APIs")
    if cancelled():
        raise InterruptedError("cancelled by owner")
    uname = platform.uname()
    progress("Reading host resources", 45, "Counting CPUs and local storage")
    if cancelled():
        raise InterruptedError("cancelled by owner")
    disk = shutil.disk_usage(os.path.abspath(os.sep))
    hostname = socket.gethostname()
    addresses: list[str] = []
    try:
        addresses = sorted({entry[4][0] for entry in socket.getaddrinfo(hostname, None)
                            if entry[4] and entry[4][0]})
    except socket.gaierror:
        pass
    progress("Preparing structured result", 80, "No shell commands were executed")
    if cancelled():
        raise InterruptedError("cancelled by owner")
    result = {
        "schema_version": "1.0",
        "observed_at": int(time.time()),
        "hostname": hostname,
        "operating_system": {
            "system": uname.system,
            "release": uname.release,
            "version": uname.version,
            "machine": uname.machine,
        },
        "python": {"version": sys.version.split()[0], "implementation": platform.python_implementation()},
        "cpu": {"logical_count": os.cpu_count()},
        "storage_root": {"total_bytes": disk.total, "used_bytes": disk.used, "free_bytes": disk.free},
        "local_addresses": addresses,
        "confidence": {
            "operating_system": "confirmed by local platform API",
            "hostname": "confirmed by local socket API",
            "addresses": "observed DNS/socket results; interface ownership not inferred",
        },
    }
    progress("Complete", 100, "Structured system information ready")
    return result


def default_registry() -> ToolRegistry:
    registry = ToolRegistry()
    registry.register(ToolDefinition(
        tool_id="system.info",
        display_name="Dell system information",
        purpose="Return harmless operating-system, host, CPU, storage, and local-address facts",
        supported_platforms=("windows", "linux", "darwin"),
        implementation=collect_system_info,
        input_schema={"type": "object", "additionalProperties": False,
                      "properties": {}, "required": []},
        output_schema={
            "type": "object",
            "additionalProperties": False,
            "properties": {
                "schema_version": {}, "observed_at": {}, "hostname": {},
                "operating_system": {}, "python": {}, "cpu": {},
                "storage_root": {}, "local_addresses": {}, "confidence": {},
            },
            "required": [
                "schema_version", "observed_at", "hostname", "operating_system",
                "python", "cpu", "storage_root", "local_addresses", "confidence",
            ],
        },
        required_permissions=("read_local_system_metadata",),
        risk_level="low",
        confirmation_policy="always",
        timeout_seconds=15,
        concurrency_limit=1,
        network_scope="local_device",
        data_leaves_device=True,
        audit_requirements=("owner_approval", "job_lifecycle", "result_recipient"),
    ))
    return registry


class ToolRunner:
    def __init__(self, store: ForgeStore, registry: ToolRegistry | None = None,
                 safety_guard: SafetyGuard | None = None):
        self.store = store
        self.registry = registry or default_registry()
        self.safety_guard = safety_guard or SafetyGuard()
        self._pool = ThreadPoolExecutor(max_workers=2, thread_name_prefix="sage-forge")
        self._limits: dict[str, threading.BoundedSemaphore] = {}

    def submit(self, job_id: str, request: dict[str, Any]) -> None:
        definition = self.registry.resolve(request.get("tool_id", ""))
        self.registry.validate_input(definition, request.get("input", {}))
        self.safety_guard.authorize(definition, request)
        self._pool.submit(self._run, job_id, definition, request["input"])

    def _run(self, job_id: str, definition: ToolDefinition, tool_input: dict[str, Any]) -> None:
        limit = self._limits.setdefault(
            definition.tool_id, threading.BoundedSemaphore(definition.concurrency_limit)
        )
        if not limit.acquire(timeout=definition.timeout_seconds):
            self.store.update_job(job_id, status="failed", stage="Concurrency limit", progress=0,
                                  error="tool concurrency limit timed out")
            return
        started = time.monotonic()
        try:
            self.store.update_job(job_id, status="running", stage="Safety checks passed", progress=5)
            self.store.add_log(job_id, "INFO", f"Allowlisted tool started: {definition.tool_id}")

            def cancelled() -> bool:
                return self.store.cancellation_requested(job_id)

            def progress(stage: str, percent: int, message: str) -> None:
                if time.monotonic() - started > definition.timeout_seconds:
                    raise TimeoutError("allowlisted tool exceeded its timeout")
                if cancelled():
                    raise InterruptedError("cancelled by owner")
                self.store.update_job(job_id, stage=stage, progress=max(0, min(100, percent)))
                self.store.add_log(job_id, "INFO", message)

            result = definition.implementation(tool_input, progress, cancelled)
            result = self.registry.validate_output(definition, result)
            self.store.update_job(job_id, status="completed", stage="Complete", progress=100,
                                  result=result)
            self.store.add_log(job_id, "INFO", "Job completed and result stored")
        except InterruptedError as error:
            self.store.update_job(job_id, status="cancelled", stage="Cancelled", error=str(error))
            self.store.add_log(job_id, "NOTICE", "Owner cancellation completed")
        except Exception as error:  # boundary: details are sanitized before storage
            detail = f"{type(error).__name__}: {str(error)[:500]}"
            self.store.update_job(job_id, status="failed", stage="Failed", error=detail)
            self.store.add_log(job_id, "ERROR", detail)
        finally:
            limit.release()

    def close(self) -> None:
        self._pool.shutdown(wait=True, cancel_futures=True)
