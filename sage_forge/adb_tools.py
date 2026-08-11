"""Bounded Android/ADB authority inspection for Sage Forge.

The probe intentionally exposes no arbitrary shell input. Every ADB invocation is a
fixed, read-only command selected in this module so Commander can learn what the
connected Sage tablet actually allows before any root/bootloader decision is made.
"""

from __future__ import annotations

import shutil
import subprocess
import time
from typing import Any, Callable


Progress = Callable[[str, int, str], None]

PACKAGE = "com.pineapple.sagecommander.stable"


def _run(adb: str, args: list[str], timeout: int = 8) -> tuple[int, str]:
    completed = subprocess.run(
        [adb, *args],
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        timeout=timeout,
        check=False,
    )
    return completed.returncode, completed.stdout.strip()[:20000]


def _shell(adb: str, *args: str) -> str:
    code, output = _run(adb, ["shell", *args])
    return output if code == 0 else "unavailable"


def _grant_state(dumpsys: str, permission: str) -> str:
    needle = permission + ": granted=true"
    if needle in dumpsys:
        return "granted"
    if permission in dumpsys:
        return "requested_not_granted_or_unresolved"
    return "not_requested_or_not_visible"


def collect_adb_authority(_: dict[str, Any], progress: Progress,
                          cancelled: Callable[[], bool]) -> dict[str, Any]:
    progress("Locating Android Debug Bridge", 5, "Looking for adb on the Dell")
    adb = shutil.which("adb")
    observed = int(time.time())
    if not adb:
        return {
            "schema_version": "1.0",
            "observed_at": observed,
            "adb_available": False,
            "device_state": "adb_not_found",
            "device_serial": "",
            "device": {},
            "sage_package": {},
            "authority": {},
            "boot": {},
            "next_ceiling": "Install/configure adb on Forge before testing elevated Android authority.",
        }
    if cancelled():
        raise InterruptedError("cancelled by owner")

    progress("Checking attached Android device", 15, "Running fixed adb device-state query")
    state_code, state = _run(adb, ["get-state"])
    serial_code, serial = _run(adb, ["get-serialno"])
    if state_code != 0 or state.strip() != "device":
        return {
            "schema_version": "1.0",
            "observed_at": observed,
            "adb_available": True,
            "device_state": state or "not_connected",
            "device_serial": serial if serial_code == 0 else "",
            "device": {},
            "sage_package": {},
            "authority": {},
            "boot": {},
            "next_ceiling": "Authorize the tablet's USB debugging connection to Forge.",
        }
    if cancelled():
        raise InterruptedError("cancelled by owner")

    progress("Reading tablet identity and verified-boot state", 30,
             "Only fixed getprop queries are allowed")
    device = {
        "model": _shell(adb, "getprop", "ro.product.model"),
        "device": _shell(adb, "getprop", "ro.product.device"),
        "android": _shell(adb, "getprop", "ro.build.version.release"),
        "sdk": _shell(adb, "getprop", "ro.build.version.sdk"),
        "fingerprint": _shell(adb, "getprop", "ro.build.fingerprint"),
    }
    boot = {
        "flash_locked": _shell(adb, "getprop", "ro.boot.flash.locked"),
        "vbmeta_device_state": _shell(adb, "getprop", "ro.boot.vbmeta.device_state"),
        "verified_boot_state": _shell(adb, "getprop", "ro.boot.verifiedbootstate"),
    }
    if cancelled():
        raise InterruptedError("cancelled by owner")

    progress("Inspecting Sage package authority", 50,
             "Reading package and device-policy state without changing it")
    package_path = _shell(adb, "pm", "path", PACKAGE)
    dumpsys = _shell(adb, "dumpsys", "package", PACKAGE)
    dpm = _shell(adb, "dpm", "list-owners")
    if dpm == "unavailable":
        dpm = _shell(adb, "dpm", "list", "owners")
    package_present = package_path.startswith("package:")
    owner_marker = PACKAGE in dpm
    authority = {
        "device_owner_report_mentions_sage": owner_marker,
        "device_policy_report": dpm[:4000],
        "write_secure_settings": _grant_state(dumpsys, "android.permission.WRITE_SECURE_SETTINGS"),
        "read_logs": _grant_state(dumpsys, "android.permission.READ_LOGS"),
        "dump": _grant_state(dumpsys, "android.permission.DUMP"),
        "package_usage_stats": _grant_state(dumpsys, "android.permission.PACKAGE_USAGE_STATS"),
    }
    sage_package = {
        "package": PACKAGE,
        "installed": package_present,
        "apk_path": package_path if package_present else "unavailable",
    }

    progress("Classifying non-root authority ceiling", 75,
             "No grants, owner changes, shell scripts, unlocks, flashes, or root commands run")
    if owner_marker:
        next_ceiling = "Sage already appears in Android's device-owner report; wire device-owner APIs before considering root."
    elif authority["write_secure_settings"] == "granted":
        next_ceiling = "ADB-granted secure-settings authority is active; expose useful bounded system controls next."
    elif package_present:
        next_ceiling = "Sage is installed and ADB-connected; test specific grantable development permissions and device-owner eligibility next."
    else:
        next_ceiling = "Sage package was not visible through ADB; verify the connected tablet/user before authority work."

    progress("Complete", 100, "ADB authority evidence ready")
    return {
        "schema_version": "1.0",
        "observed_at": observed,
        "adb_available": True,
        "device_state": "device",
        "device_serial": serial if serial_code == 0 else "",
        "device": device,
        "sage_package": sage_package,
        "authority": authority,
        "boot": boot,
        "next_ceiling": next_ceiling,
    }
