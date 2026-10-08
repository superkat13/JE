from __future__ import annotations

import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from sage_forge import tools
from sage_forge.autonomy_transport import collect_autonomy_dispatch, collect_autonomy_result


class AutonomyTransportTests(unittest.TestCase):
    def project(self, root: Path) -> None:
        git = root / ".git"
        (git / "refs" / "heads").mkdir(parents=True)
        (git / "HEAD").write_text("ref: refs/heads/sage-autonomy-v1-30\n", encoding="utf-8")
        (git / "refs" / "heads" / "sage-autonomy-v1-30").write_text(
            "0123456789abcdef0123456789abcdef01234567\n", encoding="utf-8"
        )

    @staticmethod
    def progress(*_args):
        return None

    @staticmethod
    def not_cancelled():
        return False

    def test_registry_exposes_only_typed_autonomy_jobs(self):
        registry = tools.default_registry()
        dispatch = registry.resolve("developer.autonomy_dispatch")
        result = registry.resolve("developer.autonomy_result")
        self.assertEqual(set(dispatch.input_schema["properties"]), {"job_id", "fingerprint", "order"})
        self.assertEqual(set(result.input_schema["properties"]), {"job_id"})
        self.assertNotIn("path", dispatch.input_schema["properties"])
        self.assertNotIn("command", dispatch.input_schema["properties"])
        self.assertEqual(dispatch.confirmation_policy, "always")
        self.assertEqual(result.confirmation_policy, "always")

    def test_dispatch_writes_only_fixed_outbox(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            self.project(root)
            with patch.dict(os.environ, {"SAGE_FORGE_PROJECT_ROOT": str(root)}):
                value = collect_autonomy_dispatch(
                    {
                        "job_id": "sage_0123456789abcdef",
                        "fingerprint": "a" * 64,
                        "order": "THE COMPANY ORDER FROM SAGE\nRepair one bounded defect.",
                    },
                    self.progress,
                    self.not_cancelled,
                )
            self.assertEqual(value["status"], "queued_for_developer")
            self.assertEqual(value["project_branch"], "sage-autonomy-v1-30")
            self.assertTrue((root / ".sage/autonomy/outbox/sage_0123456789abcdef.json").is_file())
            self.assertTrue((root / ".sage/autonomy/outbox/sage_0123456789abcdef.md").is_file())
            packet = json.loads((root / value["outbox_json"]).read_text(encoding="utf-8"))
            self.assertEqual(packet["job_id"], "sage_0123456789abcdef")
            self.assertEqual(packet["fingerprint"], "a" * 64)
            self.assertEqual(packet["order_sha256"], value["order_sha256"])

    def test_result_waits_then_accepts_matching_structured_result(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            self.project(root)
            with patch.dict(os.environ, {"SAGE_FORGE_PROJECT_ROOT": str(root)}):
                waiting = collect_autonomy_result(
                    {"job_id": "sage_fedcba9876543210"}, self.progress, self.not_cancelled
                )
                self.assertEqual(waiting["status"], "waiting")
                target = root / ".sage/autonomy/results/sage_fedcba9876543210.json"
                target.parent.mkdir(parents=True)
                target.write_text(json.dumps({
                    "job_id": "sage_fedcba9876543210",
                    "status": "ready",
                    "summary": "Build and regression pass completed",
                    "commit": "abc123",
                    "tests": "all requested checks passed",
                    "apk": "Sage-Commander-1.30.0.apk",
                    "blocker": "",
                    "notes": "physical glass verification still required",
                }), encoding="utf-8")
                ready = collect_autonomy_result(
                    {"job_id": "sage_fedcba9876543210"}, self.progress, self.not_cancelled
                )
            self.assertEqual(ready["status"], "ready")
            self.assertEqual(ready["result"]["commit"], "abc123")
            self.assertEqual(len(ready["result_sha256"]), 64)

    def test_result_rejects_cross_job_or_unknown_fields(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            self.project(root)
            target = root / ".sage/autonomy/results/sage_1111111111111111.json"
            target.parent.mkdir(parents=True)
            with patch.dict(os.environ, {"SAGE_FORGE_PROJECT_ROOT": str(root)}):
                target.write_text(json.dumps({
                    "job_id": "sage_2222222222222222", "status": "ready"
                }), encoding="utf-8")
                with self.assertRaises(ValueError):
                    collect_autonomy_result(
                        {"job_id": "sage_1111111111111111"}, self.progress, self.not_cancelled
                    )
                target.write_text(json.dumps({
                    "job_id": "sage_1111111111111111", "status": "ready", "path": "/tmp/elsewhere"
                }), encoding="utf-8")
                with self.assertRaises(ValueError):
                    collect_autonomy_result(
                        {"job_id": "sage_1111111111111111"}, self.progress, self.not_cancelled
                    )


if __name__ == "__main__":
    unittest.main()
