from __future__ import annotations

import asyncio
import contextlib
import hashlib
import hmac
import json
import time
import uuid
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Annotated

import httpx
from fastapi import Depends, FastAPI, File, Form, Header, Request, UploadFile
from fastapi.exceptions import RequestValidationError
from fastapi.responses import FileResponse, JSONResponse
from pydantic import ValidationError
from starlette.exceptions import HTTPException

from .config import Settings
from .database import Database, TERMINAL
from .errors import ServiceError
from .evidence import EvidenceIndex
from .media import grant_signature, inspect_media
from .providers import BailianAsrProvider, DeepSeekProvider
from .schemas import AckRequest, AnalysisRequest, AssetMetadata, PAYLOAD_MODELS
from .security import RequestGuard, authenticate
from .worker import Worker


def create_app(settings: Settings | None = None, *, semantic=None, asr=None, transport=None) -> FastAPI:
    settings = settings or Settings()
    database = Database(settings)
    client = httpx.AsyncClient(timeout=httpx.Timeout(180, connect=15), follow_redirects=False, transport=transport)
    worker = Worker(database, settings, semantic or DeepSeekProvider(settings, client), asr or BailianAsrProvider(settings, client))

    @asynccontextmanager
    async def lifespan(app):
        task = asyncio.create_task(worker.run()) if settings.worker_enabled else None
        try: yield
        finally:
            if task:
                task.cancel()
                with contextlib.suppress(asyncio.CancelledError): await task
            await client.aclose()

    app = FastAPI(title="爱教学分析代理", version="1.0.0", lifespan=lifespan)
    app.add_middleware(RequestGuard, settings=settings)
    app.state.database, app.state.worker, app.state.settings = database, worker, settings

    @app.exception_handler(ServiceError)
    async def service_error(request, exc):
        return JSONResponse({"error": exc.as_dict()}, status_code=exc.status)

    @app.exception_handler(RequestValidationError)
    async def validation_error(request, exc):
        return JSONResponse({"error": ServiceError("INVALID_REQUEST", "请求字段格式不合法").as_dict()}, status_code=422)

    @app.exception_handler(HTTPException)
    async def http_error(request, exc):
        code = "INPUT_TOO_LARGE" if exc.status_code == 413 else "INVALID_REQUEST"
        return JSONResponse({"error": ServiceError(code, "请求无法处理").as_dict()}, status_code=exc.status_code)

    async def owner(authorization: Annotated[str | None, Header()] = None) -> str:
        return authenticate(settings, authorization)

    @app.get("/healthz")
    async def health(): return {"status": "ok"}

    @app.get("/v1/capabilities")
    async def capabilities(device: str = Depends(owner)):
        return {"schemaVersion": 1, "asr": settings.asr_configured, "vision": bool(settings.deepseek_key),
                "semantic": bool(settings.deepseek_key), "maxAudioBytes": settings.max_audio_bytes,
                "maxAudioDurationMs": settings.max_audio_duration_ms, "maxImageBytes": settings.max_image_bytes,
                "maxImagesPerJob": 1, "operations": list(PAYLOAD_MODELS)}

    @app.get("/v1/schemas/{operation}")
    async def schema(operation: str, device: str = Depends(owner)):
        if operation not in PAYLOAD_MODELS: raise ServiceError("NOT_FOUND", "分析类型不存在", status=404)
        return PAYLOAD_MODELS[operation].model_json_schema()

    @app.post("/v1/assets", status_code=201)
    async def upload(file: Annotated[UploadFile, File()], metadata: Annotated[str, Form()], device: str = Depends(owner)):
        try: meta = AssetMetadata.model_validate_json(metadata)
        except (ValueError, ValidationError) as exc:
            raise ServiceError("INVALID_METADATA", "材料元信息格式不正确") from exc
        temporary = settings.asset_directory / (str(uuid.uuid4()) + ".upload")
        maximum = settings.max_audio_bytes if meta.kind == "AUDIO" else settings.max_image_bytes
        sha, size = hashlib.sha256(), 0
        try:
            with temporary.open("xb") as target:
                while data := await file.read(1024 * 1024):
                    size += len(data)
                    if size > maximum: raise ServiceError("INPUT_TOO_LARGE", "材料超过项目大小限制", status=413)
                    sha.update(data)
                    target.write(data)
            if size == 0: raise ServiceError("EMPTY_INPUT", "材料文件为空")
            if sha.hexdigest() != meta.sha256: raise ServiceError("HASH_MISMATCH", "材料哈希与实际文件不一致")
            info = await asyncio.to_thread(inspect_media, temporary, meta.kind, settings)
            path = settings.asset_directory / (str(uuid.uuid4()) + ".bin")
            temporary.replace(path)
            try: return database.insert_asset(device, meta.model_dump(), str(path.resolve()), size, info)
            except BaseException:
                path.unlink(missing_ok=True)
                raise
        finally:
            temporary.unlink(missing_ok=True)
            await file.close()

    @app.post("/v1/analysis-jobs", status_code=202)
    async def create_job(request: Request, idempotency_key: Annotated[str, Header()], device: str = Depends(owner)):
        try: uuid.UUID(idempotency_key)
        except ValueError as exc: raise ServiceError("INVALID_REQUEST", "Idempotency-Key必须为UUID") from exc
        chunks, size = [], 0
        async for chunk in request.stream():
            size += len(chunk)
            if size > 2 * 1024 * 1024: raise ServiceError("INPUT_TOO_LARGE", "分析清单超过2MiB", status=413)
            chunks.append(chunk)
        try: body = AnalysisRequest.model_validate_json(b"".join(chunks))
        except (ValueError, ValidationError) as exc: raise ServiceError("INVALID_REQUEST", "分析清单字段不合法") from exc
        EvidenceIndex(body)
        for item in body.inputs:
            if item.assetId:
                asset = database.asset(item.assetId, device)
                if asset["case_id"] != body.caseId or asset["input_id"] != item.inputId or asset["kind"] != item.kind:
                    raise ServiceError("INVALID_EVIDENCE", "材料与任务、来源或类型不匹配")
                if asset["expires_at"] <= time.time() or not Path(asset["path"]).is_file():
                    raise ServiceError("EXPIRED", "材料已过期，请重新上传", status=410)
        job, _ = database.create_job(device, idempotency_key, body.model_dump())
        return database.response(job)

    @app.get("/v1/analysis-jobs/{job_id}")
    async def get_job(job_id: str, device: str = Depends(owner)):
        return database.response(database.get_job(job_id, device))

    @app.post("/v1/analysis-jobs/{job_id}/cancel")
    async def cancel(job_id: str, device: str = Depends(owner)):
        return database.response(database.cancel(job_id, device))

    @app.post("/v1/analysis-jobs/{job_id}/ack")
    async def ack(job_id: str, body: AckRequest, device: str = Depends(owner)):
        database.ack(job_id, device, body.resultHash)
        return {"acknowledged": True}

    @app.get("/provider-assets/{asset_id}")
    async def provider_asset(asset_id: str, grantId: str, exp: int, sig: str):
        if not settings.signing_key: raise ServiceError("FORBIDDEN", "签名下载未配置", status=403)
        grant = database.grant(grantId)
        expected = grant_signature(settings.signing_key, grantId, asset_id, exp)
        if not grant or grant["asset_id"] != asset_id or grant["expires_at"] != exp or exp <= time.time() or not hmac.compare_digest(sig, expected):
            raise ServiceError("FORBIDDEN", "下载授权无效或已过期", status=403)
        job = database.get_job(grant["job_id"], grant["owner"])
        if job["state"] not in {"QUEUED", "RUNNING"}:
            raise ServiceError("FORBIDDEN", "任务已终止", status=403)
        asset = database.asset(asset_id, grant["owner"])
        if not Path(asset["path"]).is_file(): raise ServiceError("NOT_FOUND", "材料文件已过期", status=404)
        return FileResponse(asset["path"], media_type=asset["media_type"])

    return app


app = create_app()
