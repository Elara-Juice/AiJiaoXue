import hashlib
import json
import time
import uuid

import pytest

from app.database import Database
from app.errors import LeaseLost, ServiceError
from app.main import create_app
from app.schemas import canonical
from app.worker import Worker
from conftest import content_result, request_body


class SemanticFixture:
    def __init__(self): self.calls = 0
    async def analyze(self, request, index, image=None):
        self.calls += 1
        return content_result(), {"provider": "fixture", "model": "fixture", "promptVersion": "v1"}


class AsrFixture:
    def __init__(self): self.submits = 0; self.polls = 0
    async def submit(self, url): self.submits += 1; return "fixture-task", "req-1"
    async def poll(self, task_id):
        self.polls += 1
        return {"status": "PENDING"} if self.polls == 1 else {"status": "SUCCEEDED", "url": "https://fixture.example/result"}
    async def fetch_result(self, url):
        return {"transcripts": [{"channel_id": 0, "sentences": [{"text": "你好", "begin_time": 0, "end_time": 500, "words": []}]}]}


async def test_worker_result_ack_and_raw_recovery(settings):
    semantic = SemanticFixture()
    db = Database(settings)
    job, _ = db.create_job("owner", str(uuid.uuid4()), request_body())
    worker = Worker(db, settings, semantic, AsrFixture())
    assert await worker.run_once()
    response = db.response(db.get_job(job["id"]))
    assert response["state"] == "SUCCEEDED" and response["resultHash"]
    db.ack(job["id"], "owner", response["resultHash"])
    db.ack(job["id"], "owner", response["resultHash"])
    with pytest.raises(ServiceError): db.ack(job["id"], "owner", "f" * 64)
    # Simulate loss of the terminal commit after the cloud response was persisted.
    with db.connect() as connection:
        connection.execute("UPDATE jobs SET state='RUNNING',lease_until=0 WHERE id=?", (job["id"],))
    assert await Worker(db, settings, semantic, AsrFixture()).run_once()
    assert semantic.calls == 1


async def test_asr_restart_resumes_without_resubmit(settings, wav_bytes):
    db = Database(settings)
    path = settings.asset_directory / "fixture.wav"
    path.write_bytes(wav_bytes)
    asset = db.insert_asset("owner", {"caseId": "case-1", "inputId": "audio", "kind": "AUDIO", "sha256": hashlib.sha256(wav_bytes).hexdigest()}, str(path), len(wav_bytes), {"mediaType": "audio/wav", "durationMs": 1000, "width": None, "height": None})
    job, _ = db.create_job("owner", str(uuid.uuid4()), request_body("TRANSCRIBE", [{"inputId": "audio", "kind": "AUDIO", "revision": 1, "assetId": asset["assetId"], "payload": {}}]))
    asr = AsrFixture()
    await Worker(db, settings, SemanticFixture(), asr).run_once()
    assert db.call(job["id"])["task_id"] == "fixture-task"
    await Worker(db, settings, SemanticFixture(), asr).run_once()
    assert db.get_job(job["id"])["state"] == "PARTIAL"
    assert asr.submits == 1 and asr.polls == 2
    assert db.call(job["id"])["raw_result"]


async def test_crash_after_post_is_unknown_not_automatic_resubmission(settings):
    db = Database(settings)
    job, _ = db.create_job("owner", str(uuid.uuid4()), request_body())
    claimed = db.claim("old-worker")
    db.save_call(claimed, "deepseek", "SUBMITTING")
    with db.connect() as connection: connection.execute("UPDATE jobs SET lease_until=0 WHERE id=?", (job["id"],))
    semantic = SemanticFixture()
    await Worker(db, settings, semantic, AsrFixture()).run_once()
    result = db.response(db.get_job(job["id"]))
    assert result["state"] == "FAILED" and result["error"]["code"] == "SUBMISSION_UNKNOWN"
    assert semantic.calls == 0


def test_fenced_lease_rejects_late_worker(settings):
    db = Database(settings)
    db.create_job("owner", str(uuid.uuid4()), request_body())
    old = db.claim("old")
    with db.connect() as connection: connection.execute("UPDATE jobs SET lease_until=0")
    new = db.claim("new")
    assert new["lease_version"] > old["lease_version"]
    with pytest.raises(LeaseLost): db.update(old, state="SUCCEEDED")


async def test_cancel_during_cloud_call_discards_late_result(settings):
    db = Database(settings)
    job, _ = db.create_job("owner", str(uuid.uuid4()), request_body())
    class CancellingSemantic(SemanticFixture):
        async def analyze(self, request, index, image=None):
            db.cancel(job["id"], "owner")
            return await super().analyze(request, index, image)
    await Worker(db, settings, CancellingSemantic(), AsrFixture()).run_once()
    result = db.response(db.get_job(job["id"]))
    assert result["state"] == "CANCELLED" and result["result"] is None


async def test_retry_limit_and_insufficient_input(settings):
    db = Database(settings)
    job, _ = db.create_job("owner", str(uuid.uuid4()), request_body())
    class RateLimited(SemanticFixture):
        async def analyze(self, *args):
            self.calls += 1
            raise ServiceError("PROVIDER_RATE_LIMIT", "fixture", retryable=True)
    provider = RateLimited()
    worker = Worker(db, settings, provider, AsrFixture())
    for _ in range(4): await worker.run_once()
    assert provider.calls == 4 and db.get_job(job["id"])["state"] == "FAILED"
    body = request_body(inputs=[{"inputId": "course", "kind": "COURSE_REFERENCE", "revision": 1, "payload": {"knowledgePoints": [{"id": "KP1", "text": "并未实际讲授"}]}}])
    second, _ = db.create_job("owner", str(uuid.uuid4()), body)
    await worker.run_once()
    result = db.response(db.get_job(second["id"]))
    assert result["result"]["resultState"] == "INSUFFICIENT_INPUT"
    assert provider.calls == 4


async def test_cancel_between_claim_and_stage_does_not_call_provider(settings):
    db = Database(settings)
    job, _ = db.create_job("owner", str(uuid.uuid4()), request_body())
    original_claim = db.claim
    def claim_then_cancel(worker_id):
        claimed = original_claim(worker_id)
        if claimed: db.cancel(claimed["id"], "owner")
        return claimed
    db.claim = claim_then_cancel
    semantic = SemanticFixture()
    await Worker(db, settings, semantic, AsrFixture()).run_once()
    assert semantic.calls == 0 and db.get_job(job["id"])["state"] == "CANCELLED"


def test_cleanup_preserves_active_assets_and_external_paths(settings, tmp_path):
    db = Database(settings)
    path = settings.asset_directory / "expired.bin"
    path.write_bytes(b"fixture")
    asset = db.insert_asset("owner", {"caseId": "case-1", "inputId": "audio", "kind": "AUDIO", "sha256": "a" * 64}, str(path), 7, {"mediaType": "audio/wav", "durationMs": 1000, "width": None, "height": None})
    job, _ = db.create_job("owner", str(uuid.uuid4()), request_body("TRANSCRIBE", [{"inputId": "audio", "kind": "AUDIO", "revision": 1, "assetId": asset["assetId"], "payload": {}}]))
    with db.connect() as connection: connection.execute("UPDATE assets SET expires_at=0")
    db.cleanup()
    assert path.exists()
    with db.connect() as connection: connection.execute("UPDATE jobs SET state='CANCELLED'")
    db.cleanup()
    assert not path.exists()
    external = tmp_path / "outside-assets.txt"
    external.write_text("preserve", encoding="utf-8")
    db.insert_asset("owner", {"caseId": "case-1", "inputId": "outside", "kind": "IMAGE", "sha256": "b" * 64}, str(external), 8, {"mediaType": "image/png", "durationMs": None, "width": 1, "height": 1})
    with db.connect() as connection: connection.execute("UPDATE assets SET expires_at=0")
    db.cleanup()
    assert external.read_text(encoding="utf-8") == "preserve"
