from __future__ import annotations

import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import sage_forge  # noqa: F401 - installs the additive registry extension
from sage_forge import tools
from sage_forge.developer_tools import collect_developer_runtime, collect_project_snapshot
from sage_forge.termux_tools import collect_termux_status


class DeveloperToolTests(unittest.TestCase):
    def progress(self, *_args):
        return None

    def test_registry_exposes_only_typed_empty_input_jobs(self):
        registry = tools.default_registry()
        for tool_id in (
            "developer.runtime_inventory",
            "developer.project_snapshot",
            "android.termux_status",
        ):
            definition = registry.resolve(tool_id)
            self.assertEqual(definition.input_schema["properties"], {})
            self.assertFalse(definition.input_schema.get("additionalProperties", True))
            self.assertEqual(definition.confirmation_policy, "always")
            self.assertEqual(definition.risk_level, "low")
            self.assertEqual(definition.concurrency_limit, 1)

    def test_safety_guard_rejects_command_fields_before_execution(self):
        registry = tools.default_registry()
        definition = registry.resolve("developer.project_snapshot")
        guard = tools.SafetyGuard()
        with self.assertRaises(PermissionError):
            guard.authorize(definition, {
                "tool_id": definition.tool_id,
                "owner_approved": True,
                "command": "anything",
                "input": {},
            })

    def test_runtime_inventory_is_metadata_only(self):
        result = collect_developer_runtime({}, self.progress, lambda: False)
        self.assertEqual(result["schema_version"], "1.0")
        self.assertIn("git", result["executables"])
        self.assertIn("termux_markers", result)
        self.assertIn("no arbitrary commands", result["notes"].lower())

    def test_project_snapshot_uses_configured_root_and_fixed_git_queries(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            (root / ".git").mkdir()
            (root / "one.txt").write_text("one", encoding="utf-8")
            calls = []

            def fake_run(argv, cwd=None, timeout=8):
                calls.append(tuple(argv))
                if argv[-2:] == ["rev-parse", "HEAD"]:
                    return 0, "abc123"
                if argv[-2:] == ["branch", "--show-current"]:
                    return 0, "work"
                if "status" in argv:
                    return 0, ""
                return 1, "unexpected"

            with mock.patch.dict(os.environ, {"SAGE_FORGE_PROJECT_ROOT": str(root)}, clear=False), \
                 mock.patch("sage_forge.developer_tools.shutil.which", return_value="/usr/bin/git"), \
                 mock.patch("sage_forge.developer_tools._run_fixed", side_effect=fake_run):
                result = collect_project_snapshot({}, self.progress, lambda: False)

            self.assertEqual(result["project_root"], str(root.resolve()))
            self.assertEqual(result["git_head"], "abc123")
            self.assertEqual(result["git_branch"], "work")
            self.assertTrue(result["working_tree_clean"])
            self.assertGreaterEqual(result["file_count"], 1)
            flat = " ".join(" ".join(call) for call in calls)
            for forbidden in (" sh ", "bash", "powershell", "cmd.exe", "-c"):
                self.assertNotIn(forbidden, flat)

    def test_termux_status_uses_only_fixed_adb_package_queries(self):
        calls = []

        def fake_run(adb, args, timeout=8):
            calls.append(tuple(args))
            if args == ["get-state"]:
                return 0, "device"
            if args == ["shell", "pm", "path", "com.termux"]:
                return 0, "package:/data/app/termux/base.apk"
            if args == ["shell", "pm", "path", "com.termux.api"]:
                return 1, ""
            return 1, "unexpected"

        with mock.patch("sage_forge.termux_tools.shutil.which", return_value="/usr/bin/adb"), \
             mock.patch("sage_forge.termux_tools._run", side_effect=fake_run):
            result = collect_termux_status({}, self.progress, lambda: False)

        self.assertTrue(result["device_connected"])
        self.assertTrue(result["termux_installed"])
        self.assertFalse(result["termux_api_installed"])
        self.assertEqual(calls, [
            ("get-state",),
            ("shell", "pm", "path", "com.termux"),
            ("shell", "pm", "path", "com.termux.api"),
        ])

    def test_no_input_can_select_a_path_executable_or_termux_command(self):
        registry = tools.default_registry()
        for tool_id in (
            "developer.runtime_inventory",
            "developer.project_snapshot",
            "android.termux_status",
        ):
            definition = registry.resolve(tool_id)
            with self.assertRaises(ValueError):
                registry.validate_input(definition, {"path": "/tmp"})
            with self.assertRaises(ValueError):
                registry.validate_input(definition, {"executable": "sh"})
            with self.assertRaises(ValueError):
                registry.validate_input(definition, {"command": "echo nope"})


if __name__ == "__main__":
    unittest.main()
