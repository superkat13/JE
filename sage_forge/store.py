"""Durable Forge trust, job, log, and replay state."""

from __future__ import annotations

import json
import sqlite3
import threading
import time
from pathlib import Path
from typing import Any

from .security import sha256_hex


class ForgeStore:
    def __init__(self, path: Path):
        self.path = path
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.RLock()
        self._db = sqlite3.connect(str(path), check_same_thread=False)
        self._db.row_factory = sqlite3.Row
        self._db.executescript(
            """
            PRAGMA journal_mode=WAL;
            PRAGMA foreign_keys=ON;
            CREATE TABLE IF NOT EXISTS devices (
              device_id TEXT PRIMARY KEY,
              display_name TEXT NOT NULL,
              token_hash TEXT NOT NULL UNIQUE,
              paired_at INTEGER NOT NULL,
              revoked_at INTEGER
            );
            CREATE TABLE IF NOT EXISTS nonces (
              device_id TEXT NOT NULL,
              nonce TEXT NOT NULL,
              seen_at INTEGER NOT NULL,
              PRIMARY KEY(device_id, nonce)
            );
            CREATE TABLE IF NOT EXISTS jobs (
              job_id TEXT PRIMARY KEY,
              device_id TEXT NOT NULL,
              tool_id TEXT NOT NULL,
              input_json TEXT NOT NULL,
              status TEXT NOT NULL,
              stage TEXT NOT NULL,
              progress INTEGER NOT NULL,
              created_at INTEGER NOT NULL,
              updated_at INTEGER NOT NULL,
              cancel_requested INTEGER NOT NULL DEFAULT 0,
              result_json TEXT,
              error TEXT,
              FOREIGN KEY(device_id) REFERENCES devices(device_id)
            );
            CREATE TABLE IF NOT EXISTS job_logs (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              job_id TEXT NOT NULL,
              timestamp INTEGER NOT NULL,
              level TEXT NOT NULL,
              message TEXT NOT NULL,
              FOREIGN KEY(job_id) REFERENCES jobs(job_id)
            );
            """
        )
        now = int(time.time())
        self._db.execute(
            "UPDATE jobs SET status='interrupted', stage='Forge restarted', updated_at=?, "
            "error='Forge stopped while this job was running' WHERE status='running'", (now,)
        )
        self._db.commit()

    def close(self) -> None:
        with self._lock:
            self._db.close()

    def add_device(self, device_id: str, name: str, token: str) -> None:
        now = int(time.time())
        with self._lock:
            self._db.execute(
                "INSERT INTO devices(device_id,display_name,token_hash,paired_at) VALUES(?,?,?,?)",
                (device_id, name, sha256_hex(token.encode("utf-8")), now),
            )
            self._db.commit()

    def authenticate(self, token: str) -> dict[str, Any] | None:
        token_hash = sha256_hex(token.encode("utf-8"))
        with self._lock:
            row = self._db.execute(
                "SELECT device_id,display_name,paired_at FROM devices "
                "WHERE token_hash=? AND revoked_at IS NULL", (token_hash,)
            ).fetchone()
        return dict(row) if row else None

    def accept_nonce(self, device_id: str, nonce: str, seen_at: int) -> bool:
        if not (16 <= len(nonce) <= 128) or not nonce.replace("-", "").replace("_", "").isalnum():
            return False
        cutoff = seen_at - 300
        with self._lock:
            self._db.execute("DELETE FROM nonces WHERE seen_at < ?", (cutoff,))
            try:
                self._db.execute(
                    "INSERT INTO nonces(device_id,nonce,seen_at) VALUES(?,?,?)",
                    (device_id, nonce, seen_at),
                )
                self._db.commit()
                return True
            except sqlite3.IntegrityError:
                self._db.rollback()
                return False

    def revoke(self, device_id: str) -> bool:
        with self._lock:
            cursor = self._db.execute(
                "UPDATE devices SET revoked_at=? WHERE device_id=? AND revoked_at IS NULL",
                (int(time.time()), device_id),
            )
            self._db.execute("DELETE FROM nonces WHERE device_id=?", (device_id,))
            self._db.commit()
            return cursor.rowcount == 1

    def create_job(self, job_id: str, device_id: str, tool_id: str, tool_input: dict[str, Any]) -> None:
        now = int(time.time())
        with self._lock:
            self._db.execute(
                "INSERT INTO jobs(job_id,device_id,tool_id,input_json,status,stage,progress,created_at,updated_at) "
                "VALUES(?,?,?,?,?,?,?,?,?)",
                (job_id, device_id, tool_id, json.dumps(tool_input, sort_keys=True),
                 "queued", "Accepted", 0, now, now),
            )
            self._db.commit()

    def update_job(self, job_id: str, *, status: str | None = None, stage: str | None = None,
                   progress: int | None = None, result: dict[str, Any] | None = None,
                   error: str | None = None) -> None:
        changes: list[str] = ["updated_at=?"]
        values: list[Any] = [int(time.time())]
        for column, value in (("status", status), ("stage", stage), ("progress", progress),
                              ("error", error)):
            if value is not None:
                changes.append(f"{column}=?")
                values.append(value)
        if result is not None:
            changes.append("result_json=?")
            values.append(json.dumps(result, sort_keys=True))
        values.append(job_id)
        with self._lock:
            self._db.execute(f"UPDATE jobs SET {','.join(changes)} WHERE job_id=?", values)
            self._db.commit()

    def add_log(self, job_id: str, level: str, message: str) -> None:
        safe = message.replace("\r", " ").replace("\n", " ")[:2000]
        with self._lock:
            self._db.execute(
                "INSERT INTO job_logs(job_id,timestamp,level,message) VALUES(?,?,?,?)",
                (job_id, int(time.time()), level, safe),
            )
            self._db.commit()

    def request_cancel(self, job_id: str, device_id: str) -> bool:
        with self._lock:
            cursor = self._db.execute(
                "UPDATE jobs SET cancel_requested=1,updated_at=? WHERE job_id=? AND device_id=? "
                "AND status IN ('queued','running')", (int(time.time()), job_id, device_id)
            )
            self._db.commit()
            return cursor.rowcount == 1

    def cancellation_requested(self, job_id: str) -> bool:
        with self._lock:
            row = self._db.execute(
                "SELECT cancel_requested FROM jobs WHERE job_id=?", (job_id,)
            ).fetchone()
        return bool(row and row[0])

    def get_job(self, job_id: str, device_id: str) -> dict[str, Any] | None:
        with self._lock:
            row = self._db.execute(
                "SELECT * FROM jobs WHERE job_id=? AND device_id=?", (job_id, device_id)
            ).fetchone()
            if not row:
                return None
            logs = self._db.execute(
                "SELECT timestamp,level,message FROM job_logs WHERE job_id=? ORDER BY id", (job_id,)
            ).fetchall()
        value = dict(row)
        value["input"] = json.loads(value.pop("input_json"))
        value["result"] = json.loads(value.pop("result_json")) if value.get("result_json") else None
        value.pop("result_json", None)
        value["cancel_requested"] = bool(value["cancel_requested"])
        value["logs"] = [dict(entry) for entry in logs]
        return value
