"""Bounded Sage Autonomy transport for the paired Forge project.

Sage Commander may place one structured engineering order into a fixed outbox under the
configured Forge Git project and later read one structured result from a fixed results
folder. No command text, executable path, shell, or arbitrary filesystem path crosses the
Commander to Forge trust boundary.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import time
from pathlib import Path
from typing import Any, Callable

Progress = Callable[[str, int, str], None]

_JOB = re.compile(r"sage_[0-9a-f]{16}")
_FINGERPRINT = re.compile(r"[0-9a-f]{64}")
_MAX_ORDER = 32_000
_MAX_RESULT = 64_000
_RESULT_FIELDS = {"job_id", "status", "summary", "commit", "tests", "apk", "blocker", "notes"}


def _project_root() -> Path:
    configured = os.environ.get("SAGE_FORGE_PROJECT_ROOT", "").strip()
    root = Path(configured) if configured else Path.cwd()
    root = root.expanduser().resolve()
    if not root.is_dir() or not (root / ".git").exists():
        raise ValueError("configured Forge project root must be a Git working tree")
    return root


def _safe_job(value: Any) -> str:
    job_id = str(value or "").strip().lower()
    if not _JOB.fullmatch(job_id):
        raise ValueError("Sage autonomy job ID is invalid")
    return job_id


def _safe_fingerprint(value: Any) -> str:
    fingerprint = str(value or "").strip().lower()
    if not _FINGERPRINT.fullmatch(fingerprint):
        raise ValueError("Sage autonomy fingerprint must be 64 lowercase hex characters")
    return fingerprint


def _safe_order(value: Any) -> str:
    if not isinstance(value, str):
        raise ValueError("Sage autonomy order must be text")
    order = value.replace("\x00", " ").strip()
    if not order or len(order) > _MAX_ORDER:
        raise ValueError("Sage autonomy order size is invalid")
    return order


def _git_identity(root: Path) -> tuple[str, str]:
    git = root / ".git"
    if git.is_file():
        return "worktree", "indirect_git_dir"
    head_file = git / "HEAD"
    if not head_file.is_file():
        return "", ""
    head = head_file.read_text(encoding="utf-8", errors="replace").strip()[:512]
    if head.startswith("ref: "):
        ref = head[5:].strip()
        branch = ref.rsplit("/", 1)[-1]
        ref_file = git / ref
        commit = ref_file.read_text(encoding="utf-8", errors="replace").strip()[:64] if ref_file.is_file() else ""
        return commit, branch
    return head[:64], "detached"


def _relative(path: Path, root: Path) -> str:
    return path.resolve().relative_to(root.resolve()).as_posix()


def _atomic_write(path: Path, value: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(value, encoding="utf-8")
    os.replace(temporary, path)


def collect_autonomy_dispatch(value: dict[str, Any], progress: Progress,
                              cancelled: Callable[[], bool]) -> dict[str, Any]:
    job_id = _safe_job(value.get("job_id"))
    fingerprint = _safe_fingerprint(value.get("fingerprint"))
    order = _safe_order(value.get("order"))
    root = _project_root()

    progress("Validating Sage engineering order", 10, "Validated fixed job identity and bounded order text")
    if cancelled():
        raise InterruptedError("cancelled by owner")

    head, branch = _git_identity(root)
    order_sha = hashlib.sha256(order.encode("utf-8")).hexdigest()
    created = int(time.time())
    packet = {
        "schema_version": "1.0",
        "created_at": created,
        "job_id": job_id,
        "fingerprint": fingerprint,
        "order_sha256": order_sha,
        "project_head_at_dispatch": head,
        "project_branch_at_dispatch": branch,
        "order": order,
    }

    progress("Writing Forge autonomy outbox", 55, "Writing only under .sage/autonomy/outbox in the configured project")
    if cancelled():
        raise InterruptedError("cancelled by owner")
    outbox = root / ".sage" / "autonomy" / "outbox"
    json_path = outbox / f"{job_id}.json"
    md_path = outbox / f"{job_id}.md"
    _atomic_write(json_path, json.dumps(packet, sort_keys=True, indent=2) + "\n")
    _atomic_write(md_path, order + "\n")

    progress("Complete", 100, "Sage engineering order is queued in the fixed Forge outbox")
    return {
        "schema_version": "1.0",
        "observed_at": created,
        "job_id": job_id,
        "status": "queued_for_developer",
        "outbox_json": _relative(json_path, root),
        "outbox_markdown": _relative(md_path, root),
        "order_sha256": order_sha,
        "project_head": head,
        "project_branch": branch,
        "notes": "Local file handoff only. Nothing was executed and no arbitrary path was accepted.",
    }


def collect_autonomy_result(value: dict[str, Any], progress: Progress,
                            cancelled: Callable[[], bool]) -> dict[str, Any]:
    job_id = _safe_job(value.get("job_id"))
    root = _project_root()
    result_path = root / ".sage" / "autonomy" / "results" / f"{job_id}.json"

    progress("Checking Forge autonomy result inbox", 25, "Checking one fixed result path for this Sage job")
    if cancelled():
        raise InterruptedError("cancelled by owner")
    relative = _relative(result_path, root)
    if not result_path.is_file():
        progress("Complete", 100, "No developer result is ready yet")
        return {
            "schema_version": "1.0",
            "observed_at": int(time.time()),
            "job_id": job_id,
            "status": "waiting",
            "result": {},
            "result_sha256": "",
            "result_file": relative,
            "notes": "Waiting for a structured developer result file; no other path was inspected.",
        }

    raw = result_path.read_bytes()
    if len(raw) > _MAX_RESULT:
        raise ValueError("Forge autonomy result exceeds the bounded size limit")
    digest = hashlib.sha256(raw).hexdigest()
    try:
        parsed = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError("Forge autonomy result is not valid UTF-8 JSON") from error
    if not isinstance(parsed, dict) or set(parsed) - _RESULT_FIELDS:
        raise ValueError("Forge autonomy result fields are invalid")
    if _safe_job(parsed.get("job_id")) != job_id:
        raise ValueError("Forge autonomy result belongs to a different Sage job")
    status = str(parsed.get("status") or "").strip().lower()
    if status not in {"ready", "blocked"}:
        raise ValueError("Forge autonomy result status must be ready or blocked")

    clean: dict[str, str] = {"job_id": job_id, "status": status}
    for field in ("summary", "commit", "tests", "apk", "blocker", "notes"):
        item = parsed.get(field, "")
        if item is None:
            item = ""
        if not isinstance(item, str):
            raise ValueError(f"Forge autonomy result field {field} must be text")
        clean[field] = item.replace("\x00", " ").strip()[:12_000]

    progress("Complete", 100, "Structured Forge autonomy result is ready for Sage")
    return {
        "schema_version": "1.0",
        "observed_at": int(time.time()),
        "job_id": job_id,
        "status": status,
        "result": clean,
        "result_sha256": digest,
        "result_file": relative,
        "notes": "Read one bounded structured result from the fixed Forge result inbox.",
    }
