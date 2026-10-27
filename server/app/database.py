from __future__ import annotations

import json
import sqlite3
import time
import uuid
from pathlib import Path
from contextlib import contextmanager

from .config import Settings
from .errors import LeaseLost, ServiceError
from .schemas import canonical, digest

TERMINAL = {"SUCCEEDED", "PARTIAL", "FAILED", "CANCELLED", "EXPIRED", "STALE_INPUT"}


class Database:
    def __init__(self, settings: Settings):
        self.settings = settings
        settings.prepare()
        with self.connect() as db:
            db.executescript("""
            PRAGMA journal_mode=WAL;
            CREATE TABLE IF NOT EXISTS assets(
              id TEXT PRIMARY KEY, owner TEXT NOT NULL, case_id TEXT NOT NULL,
              input_id TEXT NOT NULL, hash TEXT NOT NULL, path TEXT NOT NULL,
              kind TEXT NOT NULL, media_type TEXT NOT NULL, size INTEGER NOT NULL,
              metadata TEXT NOT NULL, expires_at REAL NOT NULL);
            CREATE INDEX IF NOT EXISTS assets_owner ON assets(owner,case_id);
            CREATE TABLE IF NOT EXISTS jobs(
              id TEXT PRIMARY KEY, owner TEXT NOT NULL, idempotency_key TEXT NOT NULL,
              body_hash TEXT NOT NULL, input_json TEXT NOT NULL, state TEXT NOT NULL,
              stage TEXT NOT NULL, result_json TEXT, result_hash TEXT, error_json TEXT,
              lease_owner TEXT, lease_version INTEGER NOT NULL DEFAULT 0,
              lease_until REAL NOT NULL DEFAULT 0, next_run_at REAL NOT NULL DEFAULT 0,
              attempts INTEGER NOT NULL DEFAULT 0, created_at REAL NOT NULL,
              updated_at REAL NOT NULL, expires_at REAL NOT NULL, ack_at REAL,
              UNIQUE(owner,idempotency_key));
            CREATE INDEX IF NOT EXISTS jobs_ready ON jobs(state,next_run_at,lease_until);
            CREATE TABLE IF NOT EXISTS provider_calls(
              job_id TEXT PRIMARY KEY REFERENCES jobs(id), provider TEXT NOT NULL,
              status TEXT NOT NULL, task_id TEXT, request_id TEXT, raw_result TEXT,
              attempt INTEGER NOT NULL DEFAULT 0);
            CREATE TABLE IF NOT EXISTS asset_grants(
              id TEXT PRIMARY KEY, asset_id TEXT NOT NULL REFERENCES assets(id),
              job_id TEXT NOT NULL REFERENCES jobs(id), owner TEXT NOT NULL,
              expires_at INTEGER NOT NULL);
            """)

    @contextmanager
    def connect(self):
        connection = sqlite3.connect(self.settings.database_path, timeout=15)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys=ON")
        try:
            yield connection
            connection.commit()
        except BaseException:
            connection.rollback()
            raise
        finally:
            connection.close()

    def insert_asset(self, owner: str, metadata: dict, path: str, size: int, info: dict) -> dict:
        asset_id = str(uuid.uuid4())
        with self.connect() as db:
            db.execute("INSERT INTO assets VALUES(?,?,?,?,?,?,?,?,?,?,?)", (
                asset_id, owner, metadata["caseId"], metadata["inputId"], metadata["sha256"], path,
                metadata["kind"], info["mediaType"], size, canonical(info),
                time.time() + self.settings.asset_ttl_hours * 3600))
        return {"assetId": asset_id, "sha256": metadata["sha256"], **info}

    def asset(self, asset_id: str, owner: str | None = None) -> dict:
        with self.connect() as db:
            row = db.execute("SELECT * FROM assets WHERE id=?", (asset_id,)).fetchone()
        if row is None or (owner is not None and row["owner"] != owner):
            raise ServiceError("NOT_FOUND", "材料不存在或无权访问", status=404)
        return {**dict(row), **json.loads(row["metadata"])}

    def create_job(self, owner: str, key: str, request: dict) -> tuple[dict, bool]:
        body_hash = digest(request)
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            existing = db.execute("SELECT * FROM jobs WHERE owner=? AND idempotency_key=?", (owner, key)).fetchone()
            if existing:
                if existing["body_hash"] != body_hash:
                    raise ServiceError("IDEMPOTENCY_CONFLICT", "同一请求标识不能用于不同输入", status=409)
                return dict(existing), False
            now, job_id = time.time(), str(uuid.uuid4())
            db.execute("""INSERT INTO jobs(id,owner,idempotency_key,body_hash,input_json,state,stage,
                created_at,updated_at,expires_at) VALUES(?,?,?,?,?,'QUEUED','QUEUED',?,?,?)""",
                       (job_id, owner, key, body_hash, canonical(request), now, now,
                        now + self.settings.result_ttl_hours * 3600))
            return dict(db.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone()), True

    def get_job(self, job_id: str, owner: str | None = None) -> dict:
        with self.connect() as db:
            row = db.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone()
        if row is None or (owner is not None and row["owner"] != owner):
            raise ServiceError("NOT_FOUND", "任务不存在或无权访问", status=404)
        result = dict(row)
        if result["expires_at"] < time.time():
            result.update(state="EXPIRED", result_json=None, result_hash=None,
                          error_json=canonical(ServiceError("EXPIRED", "任务结果已过保留期限").as_dict()))
        return result

    def response(self, job: dict) -> dict:
        request = json.loads(job["input_json"])
        return {"jobId": job["id"], "state": job["state"], "stage": job["stage"],
                "inputFingerprint": request["inputFingerprint"], "operation": request["operation"],
                "result": json.loads(job["result_json"]) if job["result_json"] else None,
                "error": json.loads(job["error_json"]) if job["error_json"] else None,
                "resultHash": job["result_hash"]}

    def cancel(self, job_id: str, owner: str) -> dict:
        self.get_job(job_id, owner)
        with self.connect() as db:
            db.execute("""UPDATE jobs SET state='CANCEL_REQUESTED',updated_at=?
                       WHERE id=? AND owner=? AND state IN ('QUEUED','RUNNING')""", (time.time(), job_id, owner))
        return self.get_job(job_id, owner)

    def ack(self, job_id: str, owner: str, result_hash: str) -> None:
        row = self.get_job(job_id, owner)
        if row["state"] not in {"SUCCEEDED", "PARTIAL"} or row["result_hash"] != result_hash:
            raise ServiceError("RESULT_HASH_MISMATCH", "结果摘要不匹配或结果尚未成功", status=409)
        with self.connect() as db:
            db.execute("UPDATE jobs SET ack_at=COALESCE(ack_at,?) WHERE id=?", (time.time(), job_id))

    def claim(self, worker_id: str) -> dict | None:
        now = time.time()
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("""SELECT * FROM jobs WHERE state IN ('QUEUED','RUNNING','CANCEL_REQUESTED')
                AND next_run_at<=? AND lease_until<=? ORDER BY created_at LIMIT 1""", (now, now)).fetchone()
            if not row:
                return None
            db.execute("""UPDATE jobs SET state=CASE WHEN state='CANCEL_REQUESTED' THEN state ELSE 'RUNNING' END,
                lease_owner=?,lease_version=lease_version+1,lease_until=?,updated_at=? WHERE id=?""",
                       (worker_id, now + self.settings.lease_seconds, now, row["id"]))
            return dict(db.execute("SELECT * FROM jobs WHERE id=?", (row["id"],)).fetchone())

    @staticmethod
    def _fence(db, job: dict):
        row = db.execute("SELECT * FROM jobs WHERE id=?", (job["id"],)).fetchone()
        if not row or row["state"] in TERMINAL or row["lease_version"] != job["lease_version"] or row["lease_owner"] != job["lease_owner"] or row["lease_until"] < time.time():
            raise LeaseLost()
        return row

    def update(self, job: dict, **values) -> None:
        allowed = {"state", "stage", "result_json", "result_hash", "error_json", "lease_until", "next_run_at", "attempts"}
        if set(values) - allowed:
            raise ValueError("invalid job update")
        cancelled = False
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            row = self._fence(db, job)
            if row["state"] == "CANCEL_REQUESTED":
                cancelled = True
                values = {"state": "CANCELLED", "stage": "CANCELLED", "lease_until": 0,
                          "result_json": None, "result_hash": None}
            assignments = ",".join(f"{key}=?" for key in values)
            db.execute(f"UPDATE jobs SET {assignments},updated_at=? WHERE id=?", (*values.values(), time.time(), job["id"]))
        if cancelled:
            raise LeaseLost()  # Commit cancellation first, then stop this worker's next stage.

    def heartbeat(self, job: dict) -> None:
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            self._fence(db, job)
            db.execute("UPDATE jobs SET lease_until=? WHERE id=?", (time.time() + self.settings.lease_seconds, job["id"]))

    def call(self, job_id: str) -> dict | None:
        with self.connect() as db:
            row = db.execute("SELECT * FROM provider_calls WHERE job_id=?", (job_id,)).fetchone()
        return dict(row) if row else None

    def save_call(self, job: dict, provider: str, status: str, task_id=None, request_id=None, raw_result=None):
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            row = self._fence(db, job)
            if row["state"] == "CANCEL_REQUESTED":
                raise LeaseLost()
            db.execute("""INSERT INTO provider_calls(job_id,provider,status,task_id,request_id,raw_result,attempt)
                VALUES(?,?,?,?,?,?,1) ON CONFLICT(job_id) DO UPDATE SET status=excluded.status,
                task_id=COALESCE(excluded.task_id,provider_calls.task_id),
                request_id=COALESCE(excluded.request_id,provider_calls.request_id),
                raw_result=COALESCE(excluded.raw_result,provider_calls.raw_result),attempt=provider_calls.attempt+1""",
                       (job["id"], provider, status, task_id, request_id,
                        canonical(raw_result) if raw_result is not None else None))

    def create_grant(self, job: dict, asset_id: str) -> dict:
        grant = {"id": str(uuid.uuid4()), "asset_id": asset_id, "job_id": job["id"],
                 "owner": job["owner"], "expires_at": int(time.time() + 86400)}
        with self.connect() as db:
            self._fence(db, job)
            db.execute("INSERT INTO asset_grants VALUES(?,?,?,?,?)", tuple(grant.values()))
        return grant

    def grant(self, grant_id: str) -> dict | None:
        with self.connect() as db:
            row = db.execute("SELECT * FROM asset_grants WHERE id=?", (grant_id,)).fetchone()
        return dict(row) if row else None

    def cleanup(self, now: float | None = None) -> None:
        """Only delete this proxy's expired assets; running references always win."""
        now = time.time() if now is None else now
        root = self.settings.asset_directory.resolve()
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            active_assets = set()
            for row in db.execute("SELECT input_json FROM jobs WHERE state IN ('QUEUED','RUNNING','CANCEL_REQUESTED')"):
                active_assets.update(i.get("assetId") for i in json.loads(row["input_json"])["inputs"] if i.get("assetId"))
            expired = db.execute("SELECT id,path FROM assets WHERE expires_at<=?", (now,)).fetchall()
            for row in expired:
                if row["id"] in active_assets: continue
                path = Path(row["path"]).resolve()
                # Do not follow a modified DB path outside the configured directory.
                if not path.is_relative_to(root): continue
                try: path.unlink(missing_ok=True)
                except OSError: continue
                db.execute("DELETE FROM asset_grants WHERE asset_id=?", (row["id"],))
                db.execute("DELETE FROM assets WHERE id=?", (row["id"],))
            db.execute("DELETE FROM asset_grants WHERE expires_at<=?", (now,))
            db.execute("DELETE FROM provider_calls WHERE job_id IN (SELECT id FROM jobs WHERE expires_at<=? AND state NOT IN ('QUEUED','RUNNING','CANCEL_REQUESTED'))", (now,))
            db.execute("""UPDATE jobs SET state='EXPIRED',result_json=NULL,result_hash=NULL,input_json=json_set(input_json,'$.inputs',json('[]'))
                WHERE expires_at<=? AND state NOT IN ('QUEUED','RUNNING','CANCEL_REQUESTED')""", (now,))
