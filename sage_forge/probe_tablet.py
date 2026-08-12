"""Run Sage Forge's fixed read-only Android authority probe directly on the Dell.

This is a convenience entry point for the owner when the tablet is attached through
ADB. It accepts no command text and exposes no arbitrary shell surface. The probe
implementation remains the same allowlisted evidence collector used by Forge jobs.
"""

from __future__ import annotations

import json

from .adb_tools import collect_adb_authority


def main() -> int:
    stages: list[dict[str, object]] = []

    def progress(stage: str, percent: int, detail: str) -> None:
        stages.append({"stage": stage, "percent": percent, "detail": detail})

    result = collect_adb_authority({}, progress, lambda: False)
    print(json.dumps({"progress": stages, "result": result}, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
