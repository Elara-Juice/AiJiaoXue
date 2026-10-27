"""Authenticate before multipart parsing and bound streamed request bodies."""
import hashlib
import hmac

from starlette.exceptions import HTTPException
from starlette.responses import JSONResponse

from .config import Settings
from .errors import ServiceError


def authenticate(settings: Settings, authorization: str | None) -> str:
    if not settings.token_hash:
        raise ServiceError("PROXY_NOT_CONFIGURED", "代理访问令牌尚未配置", status=503)
    if not authorization or not authorization.startswith("Bearer "):
        raise ServiceError("UNAUTHORIZED", "缺少有效设备访问令牌", status=401)
    token_hash = hashlib.sha256(authorization[7:].encode()).hexdigest()
    if not hmac.compare_digest(token_hash, settings.token_hash):
        raise ServiceError("UNAUTHORIZED", "设备访问令牌无效", status=401)
    return "device-" + token_hash[:24]


class RequestGuard:
    def __init__(self, app, settings: Settings):
        self.app, self.settings = app, settings

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http" or not scope["path"].startswith("/v1/"):
            return await self.app(scope, receive, send)
        headers = {k.lower(): v for k, v in scope.get("headers", [])}
        try:
            authenticate(self.settings, headers.get(b"authorization", b"").decode("latin-1"))
            limit = max(self.settings.max_audio_bytes, self.settings.max_image_bytes) + 1024 * 1024 if scope["path"] == "/v1/assets" else 2 * 1024 * 1024
            if b"content-length" in headers and int(headers[b"content-length"]) > limit:
                raise ServiceError("INPUT_TOO_LARGE", "请求超过项目大小限制", status=413)
        except (ServiceError, ValueError) as exc:
            error = exc if isinstance(exc, ServiceError) else ServiceError("INVALID_REQUEST", "请求长度字段无效")
            return await JSONResponse({"error": error.as_dict()}, status_code=error.status)(scope, receive, send)
        size = 0

        async def bounded_receive():
            nonlocal size
            message = await receive()
            size += len(message.get("body", b""))
            if size > limit:
                raise HTTPException(413, "请求超过项目大小限制")
            return message

        return await self.app(scope, bounded_receive, send)
