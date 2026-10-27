import hashlib
import io
import wave

import pytest

from app.config import Settings


@pytest.fixture
def settings(tmp_path):
    return Settings(database_path=tmp_path / "jobs.db", asset_directory=tmp_path / "assets",
                    token_hash=hashlib.sha256(b"fixture-device-token").hexdigest(), signing_key="fixture-signing-key",
                    public_base_url="https://fixture.example", deepseek_key="fixture-deepseek-key",
                    dashscope_key="fixture-dashscope-key", worker_enabled=False, poll_seconds=0,
                    retry_base_seconds=0)


@pytest.fixture
def headers():
    return {"Authorization": "Bearer fixture-device-token"}


@pytest.fixture
def wav_bytes():
    target = io.BytesIO()
    with wave.open(target, "wb") as audio:
        audio.setnchannels(1)
        audio.setsampwidth(2)
        audio.setframerate(8000)
        audio.writeframes(b"\0\0" * 8000)
    return target.getvalue()


def request_body(operation="CONTENT", inputs=None):
    return {"schemaVersion": 1, "caseId": "case-1", "operation": operation,
            "inputFingerprint": "a" * 64, "methodVersion": "fixture_v1",
            "inputs": inputs or [{"inputId": "tr-1", "kind": "TRANSCRIPT", "revision": 1, "assetId": None,
                "payload": {"segments": [{"segmentId": "seg-1", "text": "二叉树的前序遍历。", "mediaStartMs": 0, "mediaEndMs": 1000}]}}]}


def content_result(quote="二叉树"):
    return {"resultState": "SUPPORTED", "limitations": [], "payload": {"topics": [
        {"id": "topic-1", "name": "前序遍历", "keywords": ["二叉树"],
         "evidenceRefs": [{"inputId": "tr-1", "itemKey": "seg-1", "quote": quote}]}]}}
