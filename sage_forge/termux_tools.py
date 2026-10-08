"""Read-only Termux readiness inspection for Sage Forge.

This does not invoke Termux commands. It only uses fixed ADB package queries so Sage can
truthfully report whether the optional tablet developer environment is present and ready for
future owner-approved typed integrations.
"""

from __future__ import annotations

import shutil
import subprocess
import time
from typing import Any, Callable

Progress = Callable[[str, int, str], None]
TERMUX_PACKAGE = "com.termux"
TERMUX_API_PACKAGE = "com.termux.api"


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
    return completed.returncode, completed.stdout.strip()[:12000]


def _package_path(adb: str, package: str) -> str:
    code, output = _run(adb, ["shell", "pm", "path", package])
    return output if code == 0 and output.startswith("package:") else ""


def collect_termux_status(_: dict[str, Any], progress: Progress,
                          cancelled: Callable[[], bool]) -> dict[str, Any]:
    progress("Locating ADB", 10, "Checking Forge for adb")
    adb = shutil.which("adb")
    observed = int(time.time())
    if not adb:
        return {
            "schema_version": "1.0", "observed_at": observed, "adb_available": False,
            "device_connected": False, "termux_installed": False,
            "termux_api_installed": False, "termux_apk_path": "", "termux_api_apk_path": "",
            "readiness": "Install/configure adb on Forge before inspecting Termux readiness.",
        }
    if cancelled():
        raise InterruptedError("cancelled by owner")

    progress("Checking tablet connection", 25, "Using fixed adb get-state query")
    code, state = _run(adb, ["get-state"])
    connected = code == 0 and state.strip() == "device"
    if not connected:
        return {
            "schema_version": "1.0", "observed_at": observed, "adb_available": True,
            "device_connected": False, "termux_installed": False,
            "termux_api_installed": False, "termux_apk_path": "", "termux_api_apk_path": "",
            "readiness": "Authorize/connect the tablet to Forge before inspecting Termux readiness.",
        }
    if cancelled():
        raise InterruptedError("cancelled by owner")

    progress("Inspecting Termux packages", 55, "Using fixed package-manager path queries only")
    termux_path = _package_path(adb, TERMUX_PACKAGE)
    api_path = _package_path(adb, TERMUX_API_PACKAGE)
    installed = bool(termux_path)
    api_installed = bool(api_path)

    if installed and api_installed:
        readiness = "Termux and Termux:API packages are present. Future integration can use separately allowlisted typed actions."
    elif installed:
        readiness = "Termux is present. Keep it isolated until a specific typed Sage action needs an audited API bridge."
    else:
        readiness = "Termux is not installed on the connected tablet; Sage does not require it for the current update."

    progress("Complete", 100, "Termux readiness evidence ready; no Termux command was executed")
    return {
        "schema_version": "1.0",
        "observed_at": observed,
        "adb_available": True,
        "device_connected": True,
        "termux_installed": installed,
        "termux_api_installed": api_installed,
        "termux_apk_path": termux_path,
        "termux_api_apk_path": api_path,
        "readiness": readiness,
    }
