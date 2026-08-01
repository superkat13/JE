"""Strict, non-executable agent registry for imported/generated AgentSpec data."""

from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass
from typing import Any

from .tools import ToolRegistry


AGENT_FIELDS = {
    "agent_id", "display_name", "role_id", "domain_ids", "description", "instructions",
    "requested_tools", "input_schema", "output_schema", "source", "content_sha256", "trust_state",
}
FORBIDDEN_FIELDS = {"command", "commands", "shell", "script", "executable", "code", "patch"}


@dataclass(frozen=True)
class AgentDefinition:
    value: dict[str, Any]
    available_tools: tuple[str, ...]


class AgentRegistry:
    """Stores declarative agent data; it cannot register or execute a tool."""

    def __init__(self, tools: ToolRegistry):
        self.tools = tools
        self._agents: dict[str, AgentDefinition] = {}

    def validate(self, value: Any) -> AgentDefinition:
        if not isinstance(value, dict) or set(value) != AGENT_FIELDS:
            unknown = set(value) - AGENT_FIELDS if isinstance(value, dict) else set()
            if unknown & FORBIDDEN_FIELDS:
                raise ValueError("agent definitions cannot contain executable fields")
            raise ValueError("agent definition fields are invalid")
        agent_id = value["agent_id"]
        role_id = value["role_id"]
        if not isinstance(agent_id, str) or not re.fullmatch(r"[a-z][a-z0-9_.-]{2,95}", agent_id):
            raise ValueError("agent ID is invalid")
        if not isinstance(role_id, str) or not re.fullmatch(r"[a-z][a-z0-9_]{1,63}", role_id):
            raise ValueError("role ID is invalid")
        if value["trust_state"] not in {"built_in", "owner_reviewed", "untrusted", "disabled"}:
            raise ValueError("agent trust state is invalid")
        requested = value["requested_tools"]
        if not isinstance(requested, list) or any(not isinstance(item, str) for item in requested):
            raise ValueError("requested tools must be tool ID strings")
        available: list[str] = []
        for tool_id in requested:
            try:
                self.tools.resolve(tool_id)
                available.append(tool_id)
            except PermissionError:
                continue
        canonical = dict(value)
        declared_hash = canonical.pop("content_sha256")
        actual_hash = hashlib.sha256(
            json.dumps(canonical, sort_keys=True, separators=(",", ":")).encode("utf-8")
        ).hexdigest()
        if declared_hash != actual_hash:
            raise ValueError("agent content SHA-256 does not match")
        return AgentDefinition(dict(value), tuple(available))

    def add(self, value: dict[str, Any]) -> AgentDefinition:
        definition = self.validate(value)
        agent_id = value["agent_id"]
        if agent_id in self._agents:
            raise ValueError("duplicate agent ID")
        self._agents[agent_id] = definition
        return definition

    def get(self, agent_id: str) -> AgentDefinition | None:
        return self._agents.get(agent_id)
