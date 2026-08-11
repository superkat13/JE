from __future__ import annotations

import unittest
from unittest import mock

import sage_forge  # noqa: F401 - package import installs additive registry extension
from sage_forge import adb_tools
from sage_forge.tools import default_registry


class AdbAuthorityProbeTests(unittest.TestCase):
    def test_registered_as_bounded_owner_approved_tool(self):
        registry = default_registry()
        tool = registry.resolve("android.adb_authority_probe")
        self.assertEqual(tool.risk_level, "low")
        self.assertEqual(tool.confirmation_policy, "always")
        self.assertEqual(tool.input_schema["properties"], {})
        self.assertIn("fixed_command_set", tool.audit_requirements)

    @mock.patch("sage_forge.adb_tools.shutil.which", return_value=None)
    def test_missing_adb_is_truthful(self, _which):
        events = []
        result = adb_tools.collect_adb_authority({}, lambda *args: events.append(args), lambda: False)
        self.assertFalse(result["adb_available"])
        self.assertEqual(result["device_state"], "adb_not_found")
        self.assertIn("adb", result["next_ceiling"].lower())

    @mock.patch("sage_forge.adb_tools.shutil.which", return_value="/usr/bin/adb")
    @mock.patch("sage_forge.adb_tools._run")
    def test_connected_probe_uses_only_fixed_read_only_queries(self, run, _which):
        seen = []

        def fake_run(_adb, args, timeout=8):
            seen.append(tuple(args))
            if args == ["get-state"]:
                return 0, "device"
            if args == ["get-serialno"]:
                return 0, "SERIAL"
            if args[:3] == ["shell", "pm", "path"]:
                return 0, "package:/data/app/sage/base.apk"
            if args[:3] == ["shell", "dumpsys", "package"]:
                return 0, "android.permission.WRITE_SECURE_SETTINGS: granted=true"
            if args[:3] == ["shell", "dpm", "list-owners"]:
                return 0, ""
            if args[:4] == ["shell", "dpm", "list", "owners"]:
                return 0, ""
            if args[:3] == ["shell", "getprop", "ro.product.model"]:
                return 0, "L10_T05"
            if args[:3] == ["shell", "getprop", "ro.product.device"]:
                return 0, "L10_T05"
            if args[:3] == ["shell", "getprop", "ro.build.version.release"]:
                return 0, "13"
            if args[:3] == ["shell", "getprop", "ro.build.version.sdk"]:
                return 0, "33"
            return 0, "locked"

        run.side_effect = fake_run
        result = adb_tools.collect_adb_authority({}, lambda *_: None, lambda: False)
        self.assertTrue(result["adb_available"])
        self.assertEqual(result["device_serial"], "SERIAL")
        self.assertEqual(result["authority"]["write_secure_settings"], "granted")
        self.assertIn("secure-settings", result["next_ceiling"].lower())

        forbidden_exact = {
            ("root",), ("reboot",), ("reboot", "bootloader"),
        }
        forbidden_prefixes = (
            ("install",), ("uninstall",), ("push",), ("pull",),
            ("shell", "pm", "grant"), ("shell", "pm", "revoke"),
            ("shell", "dpm", "set-device-owner"), ("shell", "dpm", "set-profile-owner"),
            ("shell", "su"), ("shell", "reboot"), ("flash",),
        )
        for command in seen:
            self.assertNotIn(command, forbidden_exact)
            for prefix in forbidden_prefixes:
                self.assertNotEqual(command[:len(prefix)], prefix)

        # Reading a property whose *name* contains "flash" is evidence collection,
        # not an ADB flash operation.
        self.assertIn(("shell", "getprop", "ro.boot.flash.locked"), seen)

    def test_no_arbitrary_command_input_surface(self):
        registry = default_registry()
        tool = registry.resolve("android.adb_authority_probe")
        self.assertEqual(tool.input_schema.get("additionalProperties"), False)
        self.assertNotIn("command", tool.input_schema["properties"])
        self.assertNotIn("shell", tool.input_schema["properties"])
        self.assertNotIn("script", tool.input_schema["properties"])


if __name__ == "__main__":
    unittest.main()
