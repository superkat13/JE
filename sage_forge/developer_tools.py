"""Typed, owner-approved Sage Forge developer jobs.

These jobs deliberately expose no arbitrary command/script/path surface. They inspect the
local Forge runtime and one configured project root using fixed operations only.
"""

from __future__ import annotations

import os
import shutil
import subprocess
import time
from pathlib import Path
from typing import Any, Callable

Progress = Callable[[str, int, str], None]


def _run_fixed(argv: list[str], cwd: Path | None = None, timeout: int = 8) -> tuple[int, str]:
    completed = subprocess.run(
        argv,
        cwd=str(cwd) if cwd else None,
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        timeout=timeout,
        check=False,
    )
    return completed.returncode, completed.stdout.strip()[:12000]


def collect_developer_runtime(_: dict[str, Any], progress: Progress,
                              cancelled: Callable[[], bool]) -> dict[str, Any]:
    progress("Inspecting Forge developer runtime", 10, "Checking fixed executable names only")
    if cancelled():
        raise InterruptedError("cancelled by owner")

    names = ("git", "python3", "python", "java", "javac", "gradle", "adb", "node", "npm")
    executables: dict[str, dict[str, Any]] = {}
    for index, name in enumerate(names):
        path = shutil.which(name)
        executables[name] = {"available": bool(path), "path": path or ""}
        progress("Inspecting Forge developer runtime", 15 + index * 5,
                 f"Checked {name} availability")
        if cancelled():
            raise InterruptedError("cancelled by owner")

    termux_markers = {
        "termux_command_on_forge": bool(shutil.which("termux-info")),
        "termux_prefix_env_present": bool(os.environ.get("PREFIX", "").endswith("com.termux/files/usr")),
    }

    progress("Complete", 100, "Typed developer runtime inventory ready")
    return {
        "schema_version": "1.0",
        "observed_at": int(time.time()),
        "executables": executables,
        "termux_markers": termux_markers,
        "notes": "Inventory only; no arbitrary commands were accepted or executed.",
    }


def _configured_project_root() -> Path:
    configured = os.environ.get("SAGE_FORGE_PROJECT_ROOT", "").strip()
    root = Path(configured) if configured else Path.cwd()
    return root.expanduser().resolve()


def collect_project_snapshot(_: dict[str, Any], progress: Progress,
                             cancelled: Callable[[], bool]) -> dict[str, Any]:
    root = _configured_project_root()
    progress("Validating configured Forge project", 10, "Using SAGE_FORGE_PROJECT_ROOT or Forge working directory")
    if cancelled():
        raise InterruptedError("cancelled by owner")
    if not root.is_dir():
        raise FileNotFoundError("configured Forge project root is not a directory")
    if not (root / ".git").exists():
        raise ValueError("configured Forge project root is not a Git working tree")

    git = shutil.which("git")
    if not git:
        raise FileNotFoundError("git is not available on Forge")

    progress("Reading repository identity", 30, "Running fixed read-only git identity queries")
    code, head = _run_fixed([git, "rev-parse", "HEAD"], cwd=root)
    if code != 0:
        raise RuntimeError("could not resolve repository HEAD")
    _, branch = _run_fixed([git, "branch", "--show-current"], cwd=root)
    _, status = _run_fixed([git, "status", "--porcelain=v1", "--untracked-files=normal"], cwd=root)
    if cancelled():
        raise InterruptedError("cancelled by owner")

    progress("Counting project files", 65, "Walking project metadata without opening file contents")
    files = 0
    bytes_total = 0
    skipped_dirs = {".git", ".gradle", "build", "node_modules", "__pycache__"}
    for base, dirs, filenames in os.walk(root):
        dirs[:] = [d for d in dirs if d not in skipped_dirs]
        for filename in filenames:
            path = Path(base) / filename
            try:
                stat = path.stat()
            except OSError:
                continue
            files += 1
            bytes_total += stat.st_size
            if files % 500 == 0 and cancelled():
                raise InterruptedError("cancelled by owner")

    changed_lines = [line for line in status.splitlines() if line.strip()]
    progress("Complete", 100, "Structured project snapshot ready")
    return {
        "schema_version": "1.0",
        "observed_at": int(time.time()),
        "project_root": str(root),
        "git_head": head.strip(),
        "git_branch": branch.strip(),
        "working_tree_clean": len(changed_lines) == 0,
        "changed_entry_count": len(changed_lines),
        "file_count": files,
        "file_bytes": bytes_total,
        "notes": "Read-only fixed Git queries and metadata walk; no arbitrary command or file-content execution surface.",
    }
