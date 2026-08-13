"""Sage Forge: owner-controlled local engineering companion service."""

__version__ = "0.2.0"

# Register bounded Android authority and developer-inspection tools without replacing the
# existing Forge registry or system.info tool. ToolRunner resolves default_registry at
# runtime, so these extensions remain additive and independently testable.
from . import tools as _tools
from .adb_tools import collect_adb_authority
from .developer_tools import collect_developer_runtime, collect_project_snapshot

_original_default_registry = _tools.default_registry


def _object_schema(properties: tuple[str, ...]):
    return {
        "type": "object",
        "additionalProperties": False,
        "properties": {name: {} for name in properties},
        "required": list(properties),
    }


def _register_readonly(registry, *, tool_id, display_name, purpose, implementation,
                       output_properties, timeout_seconds=60, network_scope="local_device"):
    registry.register(_tools.ToolDefinition(
        tool_id=tool_id,
        display_name=display_name,
        purpose=purpose,
        supported_platforms=("windows", "linux", "darwin"),
        implementation=implementation,
        input_schema={"type": "object", "additionalProperties": False,
                      "properties": {}, "required": []},
        output_schema=_object_schema(output_properties),
        required_permissions=("owner_approved_readonly_engineering",),
        risk_level="low",
        confirmation_policy="always",
        timeout_seconds=timeout_seconds,
        concurrency_limit=1,
        network_scope=network_scope,
        data_leaves_device=True,
        audit_requirements=("owner_approval", "fixed_operation_set", "job_lifecycle", "result_recipient"),
    ))


def _default_registry_with_sage_extensions():
    registry = _original_default_registry()
    registry.register(_tools.ToolDefinition(
        tool_id="android.adb_authority_probe",
        display_name="Sage tablet ADB authority probe",
        purpose="Inspect the connected Sage tablet's real non-root Android/ADB authority ceiling using fixed read-only commands",
        supported_platforms=("windows", "linux", "darwin"),
        implementation=collect_adb_authority,
        input_schema={"type": "object", "additionalProperties": False,
                      "properties": {}, "required": []},
        output_schema=_object_schema((
            "schema_version", "observed_at", "adb_available", "device_state",
            "device_serial", "device", "sage_package", "authority", "boot", "next_ceiling",
        )),
        required_permissions=("owner_approved_adb_readonly",),
        risk_level="low",
        confirmation_policy="always",
        timeout_seconds=60,
        concurrency_limit=1,
        network_scope="local_usb_or_adb_device",
        data_leaves_device=True,
        audit_requirements=("owner_approval", "fixed_command_set", "job_lifecycle", "result_recipient"),
    ))
    _register_readonly(
        registry,
        tool_id="developer.runtime_inventory",
        display_name="Forge developer runtime inventory",
        purpose="Report which fixed developer runtimes are available on Forge without accepting command text",
        implementation=collect_developer_runtime,
        output_properties=("schema_version", "observed_at", "executables", "termux_markers", "notes"),
        timeout_seconds=30,
    )
    _register_readonly(
        registry,
        tool_id="developer.project_snapshot",
        display_name="Forge project snapshot",
        purpose="Read one configured Git working tree's identity, cleanliness, and file metadata using fixed read-only operations",
        implementation=collect_project_snapshot,
        output_properties=(
            "schema_version", "observed_at", "project_root", "git_head", "git_branch",
            "working_tree_clean", "changed_entry_count", "file_count", "file_bytes", "notes",
        ),
        timeout_seconds=60,
    )
    return registry


_tools.default_registry = _default_registry_with_sage_extensions
