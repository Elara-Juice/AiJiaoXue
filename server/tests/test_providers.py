import json

import httpx
import pytest

from app.errors import ServiceError
from app.evidence import EvidenceIndex
from app.providers import BailianAsrProvider, DeepSeekProvider, normalize_transcript
from app.schemas import AnalysisRequest
from conftest import content_result, request_body


@pytest.mark.asyncio
async def test_deepseek_request_and_real_quote_validation(settings):
    calls = []
    def handler(request):
        calls.append(json.loads(request.content))
        assert request.headers["Authorization"] == "Bearer fixture-deepseek-key"
        return httpx.Response(200, json={"id": "completion-1", "model": "deepseek-flash", "choices": [{"finish_reason": "stop", "message": {"content": json.dumps(content_result(), ensure_ascii=False)}}]})
    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        request = AnalysisRequest.model_validate(request_body())
        result, metadata = await DeepSeekProvider(settings, client).analyze(request, EvidenceIndex(request))
    assert result["payload"]["topics"][0]["name"] == "前序遍历"
    assert calls[0]["response_format"] == {"type": "json_object"}
    assert calls[0]["thinking"] == {"type": "disabled"}
    assert metadata["requestId"] == "completion-1"


@pytest.mark.asyncio
async def test_empty_model_response_repaired_once_then_failed(settings):
    calls = []
    def handler(request):
        calls.append(request)
        return httpx.Response(200, json={"choices": [{"finish_reason": "stop", "message": {"content": ""}}]})
    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        request = AnalysisRequest.model_validate(request_body())
        with pytest.raises(ServiceError, match="模型结果") as exc:
            await DeepSeekProvider(settings, client).analyze(request, EvidenceIndex(request))
    assert exc.value.code == "INVALID_OUTPUT_SCHEMA" and len(calls) == 2


@pytest.mark.asyncio
async def test_fabricated_quote_not_repaired(settings):
    calls = []
    def handler(request):
        calls.append(request)
        return httpx.Response(200, json={"choices": [{"finish_reason": "stop", "message": {"content": json.dumps(content_result("数据库事务"))}}]})
    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        request = AnalysisRequest.model_validate(request_body())
        with pytest.raises(ServiceError) as exc:
            await DeepSeekProvider(settings, client).analyze(request, EvidenceIndex(request))
    assert exc.value.code == "INVALID_EVIDENCE" and len(calls) == 1


@pytest.mark.asyncio
async def test_asr_singular_result_ms_and_no_key_on_object_store(settings):
    calls = []
    raw = {"transcripts": [{"channel_id": 0, "sentences": [{"begin_time": 100, "end_time": 900, "text": "你好。", "words": [{"begin_time": 100, "end_time": 400, "text": "你"}, {"begin_time": 400, "end_time": 900, "text": "好"}]}]}]}
    def handler(request):
        calls.append(request)
        if request.method == "POST":
            body = json.loads(request.content)
            assert body["input"] == {"file_url": "https://fixture.example/audio"}
            assert body["parameters"]["enable_words"] is True
            assert request.headers["X-DashScope-Async"] == "enable"
            return httpx.Response(200, json={"request_id": "r1", "output": {"task_id": "task-1", "task_status": "PENDING"}})
        if request.url.path.startswith("/api/v1/tasks/"):
            assert request.headers["Authorization"] == "Bearer fixture-dashscope-key"
            return httpx.Response(200, json={"output": {"task_status": "SUCCEEDED", "result": {"transcription_url": "http://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/a.json?Signature=fixture"}}})
        assert request.url.scheme == "https" and "authorization" not in request.headers
        assert request.url.query == b"Signature=fixture"
        return httpx.Response(200, json=raw)
    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        provider = BailianAsrProvider(settings, client)
        task, _ = await provider.submit("https://fixture.example/audio")
        state = await provider.poll(task)
        response = await provider.fetch_result(state["url"])
    result = normalize_transcript(response, 1000)
    assert result["payload"]["segments"][0]["mediaStartMs"] == 100
    assert result["payload"]["segments"][0]["words"][1]["endMs"] == 900
    assert len(calls) == 3


@pytest.mark.asyncio
async def test_result_download_rejects_untrusted_hosts_and_redirect(settings):
    async with httpx.AsyncClient(transport=httpx.MockTransport(lambda r: httpx.Response(302, headers={"location": "http://127.0.0.1/"}))) as client:
        provider = BailianAsrProvider(settings, client)
        for url in ("http://127.0.0.1/file", "https://evil.example/a", "https://user:pass@dashscope-result-bj.oss-cn-beijing.aliyuncs.com/a"):
            with pytest.raises(ServiceError) as exc: await provider.fetch_result(url)
            assert exc.value.code == "INVALID_RESULT_URL"
        with pytest.raises(ServiceError) as exc:
            await provider.fetch_result("https://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/a")
        assert exc.value.code == "PROVIDER_REDIRECT"


def test_asr_silence_and_invalid_intervals():
    assert normalize_transcript({"transcripts": [{"channel_id": 0, "sentences": []}]}, 1000)["resultState"] == "INSUFFICIENT_INPUT"
    with pytest.raises(ServiceError):
        normalize_transcript({"transcripts": [{"sentences": [{"text": "x", "begin_time": 20, "end_time": 1200}]}]}, 1000)


@pytest.mark.parametrize("status,body,code,retry", [(401, {}, "PROVIDER_AUTH", False), (429, {"error": {"code": "insufficient_quota"}}, "PROVIDER_QUOTA", False), (429, {}, "PROVIDER_RATE_LIMIT", True), (500, {}, "SUBMISSION_UNKNOWN", False)])
@pytest.mark.asyncio
async def test_provider_errors(settings, status, body, code, retry):
    async with httpx.AsyncClient(transport=httpx.MockTransport(lambda r: httpx.Response(status, json=body))) as client:
        with pytest.raises(ServiceError) as exc:
            await BailianAsrProvider(settings, client).submit("https://fixture.example/audio")
    assert exc.value.code == code and exc.value.retryable is retry


async def test_vision_sends_actual_bytes_and_json_mode(settings, tmp_path):
    import base64
    image_path = tmp_path / "fixture-image.bin"
    image_path.write_bytes(b"fixture-real-image-bytes")
    result = {"resultState": "SUPPORTED", "payload": {"readability": "READABLE", "blocks": [{"id": "b1", "text": "遍历", "region": None}], "limitations": []}, "limitations": []}
    def handler(request):
        body = json.loads(request.content)
        block = body["messages"][1]["content"][1]
        assert block["image_url"]["url"] == "data:image/png;base64," + base64.b64encode(image_path.read_bytes()).decode()
        assert body["response_format"] == {"type": "json_object"}
        return httpx.Response(200, json={"choices": [{"finish_reason": "stop", "message": {"content": json.dumps(result)}}]})
    request = AnalysisRequest.model_validate(request_body("IMAGE_TEXT", [{"inputId": "img", "kind": "IMAGE", "revision": 1, "assetId": "asset-fixture", "payload": {}}]))
    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        response, _ = await DeepSeekProvider(settings, client).analyze(request, EvidenceIndex(request), {"path": str(image_path), "media_type": "image/png"})
    assert response == result


async def test_object_store_drops_client_default_authorization(settings):
    def handler(request):
        assert "authorization" not in request.headers
        return httpx.Response(200, json={"transcripts": []})
    async with httpx.AsyncClient(headers={"Authorization": "Bearer must-not-leak"}, transport=httpx.MockTransport(handler)) as client:
        assert await BailianAsrProvider(settings, client).fetch_result("https://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/r") == {"transcripts": []}


def test_asr_missing_word_timings_partial_and_malformed_words_rejected():
    raw = {"transcripts": [{"sentences": [{"text": "你好", "begin_time": 0, "end_time": 500}]}]}
    assert normalize_transcript(raw, 1000)["resultState"] == "PARTIAL"
    raw["transcripts"][0]["sentences"][0]["words"] = [None]
    with pytest.raises(ServiceError): normalize_transcript(raw, 1000)
