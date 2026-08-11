"""Sage Forge: owner-controlled local engineering companion service."""

__version__ = "0.1.0"

# Register the bounded Android authority probe without replacing the existing Forge
# registry or system.info tool.  ToolRunner resolves default_registry at runtime,
# so this package-level extension remains additive and easy to remove independently.
from . import tools as _tools
from .adb_tools import collect_adb_authority

_original_default_registry = _tools.default_registry


def _default_registry_with_android_authority():
    registry = _original_default_registry()
    registry.register(_tools.ToolDefinition(
        tool_id="android.adb_authority_probe",
        display_name="Sage tablet ADB authority probe",
        purpose="Inspect the connected Sage tablet's real non-root Android/ADB authority ceiling using fixed read-only commands",
        supported_platforms=("windows", "linux", "darwin"),
        implementation=collect_adb_authority,
        input_schema={"type": "object", "additionalProperties": False,
                      "properties": {}, "required": []},
        output_schema={
            "type": "object",
            "additionalProperties": False,
            "properties": {
                "schema_version": {}, "observed_at": {}, "adb_available": {},
                "device_state": {}, "device_serial": {}, "device": {},
                "sage_package": {}, "authority": {}, "boot": {}, "next_ceiling": {},
            },
            "required": [
                "schema_version", "observed_at", "adb_available", "device_state",
                "device_serial", "device", "sage_package", "authority", "boot",
                "next_ceiling",
            ],
        },
        required_permissions=("owner_approved_adb_readonly",),
        risk_level="low",
        confirmation_policy="always",
        timeout_seconds=60,
        concurrency_limit=1,
        network_scope="local_usb_or_adb_device",
        data_leaves_device=True,
        audit_requirements=("owner_approval", "fixed_command_set", "job_lifecycle", "result_recipient"),
    ))
    return registry


_tools.default_registry = _default_registry_with_android_authority
