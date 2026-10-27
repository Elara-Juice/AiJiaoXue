import asyncio
import hashlib
import io
import json
import time
import uuid
from urllib.parse import urlsplit

from fastapi.testclient import TestClient
from PIL import Image

from app.main import create_app
from app.media import grant_url
from conftest import request_body


def post_job(client, headers, body=None, key=None):
    return client.post("/v1/analysis-jobs", headers={**headers, "Idempotency-Key": key or str(uuid.uuid4())}, json=body or request_body())


def upload_audio(client, headers, audio):
    metadata = {"caseId": "case-1", "inputId": "audio-1", "kind": "AUDIO", "sha256": hashlib.sha256(audio).hexdigest()}
    return client.post("/v1/assets", headers=headers, data={"metadata": json.dumps(metadata)}, files={"file": ("misleading.txt", audio, "text/plain")})


def test_auth_and_capabilities(settings, headers):
    with TestClient(create_app(settings)) as client:
        assert client.get("/healthz").json() == {"status": "ok"}
        assert client.get("/v1/capabilities").status_code == 401
        assert client.get("/v1/capabilities", headers={"Authorization": "Bearer wrong"}).status_code == 401
        result = client.get("/v1/capabilities", headers=headers).json()
        assert result["asr"] and result["vision"] and result["semantic"]
        assert "fixture-deepseek-key" not in str(result)
        assert client.get("/v1/schemas/CONTENT", headers=headers).json()["additionalProperties"] is False


def test_upload_decodes_actual_bytes_and_rejects_hash(settings, headers, wav_bytes):
    with TestClient(create_app(settings)) as client:
        response = upload_audio(client, headers, wav_bytes)
        assert response.status_code == 201, response.text
        assert response.json()["durationMs"] == 1000
        assert response.json()["mediaType"] == "audio/wav"
        bad = client.post("/v1/assets", headers=headers, data={"metadata": json.dumps({"caseId": "case-1", "inputId": "x", "kind": "IMAGE", "sha256": "0" * 64})}, files={"file": ("x.png", b"not image")})
        assert bad.json()["error"]["code"] == "HASH_MISMATCH"


def test_image_and_upload_size(settings, headers):
    target = io.BytesIO()
    Image.new("RGB", (30, 20), "white").save(target, "PNG")
    data = target.getvalue()
    meta = {"caseId": "case-1", "inputId": "image-1", "kind": "IMAGE", "sha256": hashlib.sha256(data).hexdigest()}
    with TestClient(create_app(settings)) as client:
        response = client.post("/v1/assets", headers=headers, data={"metadata": json.dumps(meta)}, files={"file": ("x", data)})
        assert response.status_code == 201
        assert response.json()["width"] == 30
        settings.max_image_bytes = 10
        assert client.post("/v1/assets", headers=headers, data={"metadata": json.dumps(meta)}, files={"file": ("x", data)}).status_code == 413


def test_idempotency_and_validation(settings, headers):
    with TestClient(create_app(settings)) as client:
        key = str(uuid.uuid4())
        first = post_job(client, headers, key=key)
        assert first.status_code == 202, first.text
        assert post_job(client, headers, key=key).json()["jobId"] == first.json()["jobId"]
        changed = request_body()
        changed["methodVersion"] = "another"
        assert post_job(client, headers, changed, key).status_code == 409
        changed["unknown"] = "rejected"
        assert post_job(client, headers, changed).status_code == 422
        assert client.get("/v1/analysis-jobs/unknown", headers=headers).status_code == 404


def test_wrong_case_asset_and_signed_download_range(settings, headers, wav_bytes):
    app = create_app(settings)
    with TestClient(app) as client:
        asset = upload_audio(client, headers, wav_bytes).json()
        inputs = [{"inputId": "audio-1", "kind": "AUDIO", "revision": 1, "assetId": asset["assetId"], "payload": {}}]
        body = request_body("TRANSCRIBE", inputs)
        body["caseId"] = "other-case"
        assert post_job(client, headers, body).status_code == 422
        body["caseId"] = "case-1"
        job_id = post_job(client, headers, body).json()["jobId"]
        job = app.state.database.claim("fixture-worker")
        assert job["id"] == job_id
        grant = app.state.database.create_grant(job, asset["assetId"])
        url = urlsplit(grant_url(settings, grant))
        relative = url.path + "?" + url.query
        response = client.get(relative, headers={"Range": "bytes=0-9"})
        assert response.status_code == 206 and response.content == wav_bytes[:10]
        assert client.get(relative + "bad").status_code == 403
        client.post(f"/v1/analysis-jobs/{job_id}/cancel", headers=headers)
        assert client.get(relative).status_code == 403


def test_empty_audio_and_cross_owner(settings, headers, wav_bytes):
    app = create_app(settings)
    with TestClient(app) as client:
        assert upload_audio(client, headers, b"").json()["error"]["code"] == "EMPTY_INPUT"
        job = post_job(client, headers).json()
        with app.state.database.connect() as db:
            db.execute("UPDATE jobs SET owner='someone-else' WHERE id=?", (job["jobId"],))
        assert client.get(f"/v1/analysis-jobs/{job['jobId']}", headers=headers).status_code == 404


def test_queued_cancellation(settings, headers):
    app = create_app(settings)
    with TestClient(app) as client:
        job_id = post_job(client, headers).json()["jobId"]
        assert client.post(f"/v1/analysis-jobs/{job_id}/cancel", headers=headers).json()["state"] == "CANCEL_REQUESTED"
        asyncio.run(app.state.worker.run_once())
        assert client.get(f"/v1/analysis-jobs/{job_id}", headers=headers).json()["state"] == "CANCELLED"


def test_result_expiration(settings, headers):
    app = create_app(settings)
    with TestClient(app) as client:
        job_id = post_job(client, headers).json()["jobId"]
        with app.state.database.connect() as db:
            db.execute("UPDATE jobs SET expires_at=? WHERE id=?", (time.time() - 1, job_id))
        value = client.get(f"/v1/analysis-jobs/{job_id}", headers=headers).json()
        assert value["state"] == "EXPIRED" and value["result"] is None


def test_guard_authenticates_before_multipart_and_limits_body(settings, headers):
    with TestClient(create_app(settings)) as client:
        assert client.post("/v1/assets", content=b"bad multipart", headers={"Content-Type": "multipart/form-data"}).status_code == 401
        response = client.post("/v1/analysis-jobs", content=b"x" * (2 * 1024 * 1024 + 1), headers={**headers, "Idempotency-Key": str(uuid.uuid4())})
        assert response.status_code == 413 and response.json()["error"]["code"] == "INPUT_TOO_LARGE"
