from __future__ import annotations

import asyncio
import contextlib
import json
import random
import time
import uuid

from .config import Settings
from .database import Database
from .errors import LeaseLost, ServiceError
from .evidence import EvidenceIndex, insufficient_payload
from .media import grant_url
from .providers import AsrProvider, SemanticProvider, normalize_transcript
from .schemas import AnalysisRequest, canonical, digest, validate_candidate


class Worker:
    def __init__(self, database: Database, settings: Settings, semantic: SemanticProvider, asr: AsrProvider):
        self.db, self.settings, self.semantic, self.asr = database, settings, semantic, asr
        self.id = str(uuid.uuid4())

    async def run(self):
        last_cleanup = 0
        while True:
            if time.monotonic() - last_cleanup > 60:
                self.db.cleanup()
                last_cleanup = time.monotonic()
            if not await self.run_once():
                await asyncio.sleep(0.5)

    async def renew(self, job):
        while True:
            await asyncio.sleep(max(0.1, self.settings.lease_seconds / 3))
            self.db.heartbeat(job)

    async def run_once(self) -> bool:
        job = self.db.claim(self.id)
        if job is None: return False
        heartbeat = asyncio.create_task(self.renew(job))
        try:
            if job["state"] == "CANCEL_REQUESTED":
                self.db.update(job, state="CANCELLED", stage="CANCELLED", lease_until=0)
                return True
            if time.time() - job["created_at"] > self.settings.job_deadline_seconds:
                raise ServiceError("JOB_DEADLINE_EXCEEDED", "任务超过六小时处理期限")
            request = AnalysisRequest.model_validate_json(job["input_json"])
            index = EvidenceIndex(request)
            reason = index.missing_requirements()
            if reason:
                result = insufficient_payload(request.operation, reason)
                producer = {"provider": "input-validation", "model": "none", "promptVersion": "none"}
            elif request.operation == "TRANSCRIBE":
                outcome = await self.transcribe(job, request)
                if outcome is None: return True
                result, producer = outcome
            else:
                self.db.update(job, stage="READING_IMAGES" if request.operation == "IMAGE_TEXT" else "ANALYZING")
                call = self.db.call(job["id"])
                if call and call["raw_result"]:
                    saved = json.loads(call["raw_result"])
                    result, producer = saved["result"], saved["producer"]
                    result = validate_candidate(request.operation, result)
                    index.validate_result(result["payload"])
                else:
                    if call and call["status"] == "SUBMITTING":
                        raise ServiceError("SUBMISSION_UNKNOWN", "上次云端提交结果不明，需要手动重试")
                    image = self.db.asset(request.inputs[0].assetId, job["owner"]) if request.operation == "IMAGE_TEXT" else None
                    self.db.save_call(job, "deepseek", "SUBMITTING")
                    try:
                        result, producer = await self.semantic.analyze(request, index, image)
                    except ServiceError as exc:
                        if exc.retryable: self.db.save_call(job, "deepseek", "REJECTED_RETRYABLE")
                        raise
                    self.db.save_call(job, "deepseek", "RESPONSE_SAVED", request_id=producer.get("requestId"), raw_result={"result": result, "producer": producer})
            result = validate_candidate(request.operation, result)
            result = {"schemaVersion": 1, **result, "producer": {**producer,
                      "schemaVersion": 1, "methodVersion": request.methodVersion}}
            self.db.update(job, state="PARTIAL" if result["resultState"] == "PARTIAL" else "SUCCEEDED", stage="VALIDATED",
                           result_json=canonical(result), result_hash=digest(result), error_json=None, lease_until=0)
        except LeaseLost:
            # Finalize a cancellation with our lease, but never touch a newer lease.
            try:
                if self.db.get_job(job["id"])["state"] == "CANCEL_REQUESTED":
                    self.db.update(job, state="CANCELLED", stage="CANCELLED", lease_until=0)
            except LeaseLost:
                pass
        except ServiceError as exc:
            try:
                current = self.db.get_job(job["id"])
                attempts = current["attempts"] + 1
                if exc.retryable and attempts <= 3:
                    delay = exc.retry_after if exc.retry_after is not None else self.settings.retry_base_seconds * 2 ** (attempts - 1) + random.random() * self.settings.retry_base_seconds
                    self.db.update(job, state="RUNNING", stage="RETRY_WAIT", attempts=attempts,
                                   error_json=canonical(exc.as_dict(current["stage"])), next_run_at=time.time() + delay, lease_until=0)
                else:
                    self.db.update(job, state="FAILED", stage="FAILED", error_json=canonical(exc.as_dict(current["stage"])), lease_until=0)
            except LeaseLost: pass
        except asyncio.CancelledError:
            # Leave the lease and durable provider status for recovery, never reset a POST.
            raise
        except Exception:
            try:
                self.db.update(job, state="FAILED", stage="FAILED", error_json=canonical(ServiceError("INTERNAL_ERROR", "处理失败，请查看服务端配置和输入格式").as_dict()), lease_until=0)
            except LeaseLost: pass
        finally:
            heartbeat.cancel()
            with contextlib.suppress(asyncio.CancelledError, LeaseLost): await heartbeat
        return True

    async def transcribe(self, job: dict, request: AnalysisRequest):
        asset = self.db.asset(request.inputs[0].assetId, job["owner"])
        call = self.db.call(job["id"])
        self.db.update(job, stage="TRANSCRIBING")
        if call and call["raw_result"]:
            raw = json.loads(call["raw_result"])
        else:
            task_id = call["task_id"] if call else None
            if not task_id:
                if call and call["status"] == "SUBMITTING":
                    raise ServiceError("SUBMISSION_UNKNOWN", "转写提交结果不明，需要手动重试")
                grant = self.db.create_grant(job, asset["id"])
                self.db.save_call(job, "bailian", "SUBMITTING")
                try:
                    task_id, request_id = await self.asr.submit(grant_url(self.settings, grant))
                except ServiceError as exc:
                    if exc.retryable: self.db.save_call(job, "bailian", "REJECTED_RETRYABLE")
                    raise
                self.db.save_call(job, "bailian", "SUBMITTED", task_id=task_id, request_id=request_id)
            result = await self.asr.poll(task_id)
            if result["status"] != "SUCCEEDED":
                self.db.update(job, stage="TRANSCRIBING", next_run_at=time.time() + self.settings.poll_seconds, lease_until=0)
                return None
            self.db.update(job, stage="FETCHING_RESULT")
            raw = await self.asr.fetch_result(result["url"])
            # Persist the downloaded JSON before normalization, so recovery needs no expiring URL.
            self.db.save_call(job, "bailian", "RESPONSE_SAVED", task_id=task_id, request_id=result.get("requestId"), raw_result=raw)
        return normalize_transcript(raw, asset["durationMs"]), {"provider": "bailian", "model": self.settings.asr_model,
                "requestId": (self.db.call(job["id"]) or {}).get("request_id"), "promptVersion": "asr_v1"}
