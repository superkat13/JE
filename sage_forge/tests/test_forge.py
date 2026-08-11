from __future__ import annotations

import json
import platform
import shutil
import socket
import subprocess
import tempfile
import threading
import time
import unittest
from pathlib import Path

from sage_forge.client import ForgeClient, ForgeClientError
from sage_forge.agents import AgentRegistry
from sage_forge.security import PairingGrant, certificate_sha256
from sage_forge.server import ForgeApplication, SageForgeServer
from sage_forge.store import ForgeStore
from sage_forge.tools import SafetyGuard, default_registry


class ForgeVerticalSliceTest(unittest.TestCase):
    def setUp(self) -> None:
        if shutil.which("openssl") is None:
            self.skipTest("openssl is required for the TLS integration test")
        self.temporary = tempfile.TemporaryDirectory()
        root = Path(self.temporary.name)
        self.cert = root / "forge-cert.pem"
        self.key = root / "forge-key.pem"
        subprocess.run([
            "openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
            "-keyout", str(self.key), "-out", str(self.cert), "-days", "1",
            "-subj", "/CN=Sage Forge Test",
        ], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self.store = ForgeStore(root / "forge.db")
        self.application = ForgeApplication(self.store, PairingGrant.create("24681357", 120))
        self.server = SageForgeServer(("127.0.0.1", 0), self.application, self.cert, self.key)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.url = f"https://127.0.0.1:{self.server.server_address[1]}"
        self.client = ForgeClient(self.url, certificate_sha256(self.cert))

    def tearDown(self) -> None:
        if hasattr(self, "server"):
            self.server.shutdown()
            self.server.server_close()
            self.thread.join(timeout=3)
            self.application.close()
        self.temporary.cleanup()

    def test_real_approved_system_information_job_and_revocation(self) -> None:
        paired = self.client.pair("24681357", "Sage Commander tablet")
        self.assertTrue(paired["device_id"].startswith("device_"))
        self.assertEqual("paired_and_revocable", paired["trust"])

        job_id = self.client.system_info()
        deadline = time.time() + 8
        status = self.client.job(job_id)
        while status["status"] in ("queued", "running") and time.time() < deadline:
            time.sleep(0.05)
            status = self.client.job(job_id)
        self.assertEqual("completed", status["status"], status)
        self.assertEqual(100, status["progress"])
        self.assertEqual(platform.system(), status["result"]["operating_system"]["system"])
        self.assertEqual(socket.gethostname(), status["result"]["hostname"])
        self.assertGreaterEqual(len(status["logs"]), 4)
        self.assertTrue(any(entry["level"] == "APPROVAL" for entry in status["logs"]))
        self.assertTrue(any("No shell commands" in entry["message"] for entry in status["logs"]))

        revoked = self.client.revoke()
        self.assertTrue(revoked["revoked"])
        with self.assertRaisesRegex(ForgeClientError, "absent or revoked"):
            self.client.job(job_id)

    def test_certificate_pin_and_pairing_window_are_enforced(self) -> None:
        wrong = ForgeClient(self.url, "0" * 64)
        with self.assertRaisesRegex(ForgeClientError, "pin mismatch"):
            wrong.pair("24681357", "tablet")
        self.client.pair("24681357", "tablet")
        second = ForgeClient(self.url, certificate_sha256(self.cert))
        with self.assertRaisesRegex(ForgeClientError, "window is closed"):
            second.pair("24681357", "second tablet")

    def test_unknown_unapproved_and_arbitrary_jobs_are_denied(self) -> None:
        self.client.pair("24681357", "tablet")
        base = {"input": {}, "owner_approved": True,
                "approval_context": {"surface": "sage_commander", "action": "test"}}
        with self.assertRaisesRegex(ForgeClientError, "not allowlisted"):
            self.client.request("POST", "/v1/jobs", {**base, "tool_id": "shell"})
        with self.assertRaisesRegex(ForgeClientError, "explicit owner approval"):
            self.client.request("POST", "/v1/jobs", {
                **base, "tool_id": "system.info", "owner_approved": False,
            })
        with self.assertRaisesRegex(ForgeClientError, "job fields are invalid"):
            self.client.request("POST", "/v1/jobs", {
                **base, "tool_id": "system.info", "command": "whoami",
            })


class ForgePersistenceAndSchemaTest(unittest.TestCase):
    def test_running_jobs_become_interrupted_after_restart(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "state.db"
            store = ForgeStore(path)
            store.add_device("device_one", "tablet", "secret")
            store.create_job("job_one", "device_one", "system.info", {})
            store.update_job("job_one", status="running", stage="working", progress=50)
            store.close()
            restarted = ForgeStore(path)
            job = restarted.get_job("job_one", "device_one")
            self.assertEqual("interrupted", job["status"])
            self.assertIn("stopped", job["error"])
            restarted.close()

    def test_trusted_registry_declares_required_controls(self) -> None:
        definitions = default_registry().public_definitions()
        self.assertGreaterEqual(len(definitions), 1)
        required = {
            "tool_id", "display_name", "purpose", "supported_platform", "implementation",
            "input_schema", "output_schema", "required_permissions", "risk_level",
            "confirmation_policy", "timeout_seconds", "concurrency_limit", "network_scope",
            "data_leaves_device", "audit_requirements",
        }
        by_id = {definition["tool_id"]: definition for definition in definitions}
        self.assertIn("system.info", by_id)
        for definition in definitions:
            self.assertEqual(required, set(definition))
            self.assertEqual("always", definition["confirmation_policy"])
            self.assertTrue(definition["implementation"].startswith("trusted:"))
            self.assertFalse(definition["input_schema"]["additionalProperties"])
            self.assertFalse(definition["output_schema"]["additionalProperties"])
        definition = default_registry().resolve("system.info")
        with self.assertRaisesRegex(ValueError, "missing tool output fields"):
            default_registry().validate_output(definition, {"schema_version": "1.0"})

    def test_agent_schema_is_declarative_and_forbids_execution_fields(self) -> None:
        schema = json.loads((Path(__file__).parents[1] / "schemas/agent-registry.schema.json").read_text())
        agent = schema["$defs"]["agent"]
        self.assertFalse(agent["additionalProperties"])
        properties = set(agent["properties"])
        self.assertFalse(properties & {"command", "shell", "script", "executable", "code"})
        self.assertIn("requested_tools", properties)

    def test_safety_guard_requires_owner_approval_and_blocks_execution_fields(self) -> None:
        definition = default_registry().resolve("system.info")
        guard = SafetyGuard()
        with self.assertRaisesRegex(PermissionError, "owner approval"):
            guard.authorize(definition, {"tool_id": "system.info", "owner_approved": False})
        with self.assertRaisesRegex(PermissionError, "arbitrary execution"):
            guard.authorize(definition, {
                "tool_id": "system.info", "owner_approved": True, "shell": "whoami",
            })

    def test_agent_requests_cannot_grant_tools_or_embed_code(self) -> None:
        registry = AgentRegistry(default_registry())
        with self.assertRaisesRegex(ValueError, "executable fields"):
            registry.validate({"agent_id": "bad.agent", "shell": "whoami"})
        value = {
            "agent_id": "safe.observer", "display_name": "Safe observer", "role_id": "analyst",
            "domain_ids": ["system"], "description": "Reads approved structured facts",
            "instructions": "Summarize confirmed facts and label uncertainty.",
            "requested_tools": ["system.info", "shell"],
            "input_schema": {"type": "object"}, "output_schema": {"type": "object"},
            "source": {"kind": "built_in", "uri": "sage://safe.observer", "revision": "1"},
            "trust_state": "built_in",
        }
        import hashlib
        value["content_sha256"] = hashlib.sha256(
            json.dumps(value, sort_keys=True, separators=(",", ":")).encode()
        ).hexdigest()
        definition = registry.add(value)
        self.assertEqual(("system.info",), definition.available_tools)
        self.assertNotIn("shell", definition.available_tools)


if __name__ == "__main__":
    unittest.main()
