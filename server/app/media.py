from __future__ import annotations

import hashlib
import hmac
import json
import subprocess
import wave
from pathlib import Path
from urllib.parse import urlencode

from PIL import Image, UnidentifiedImageError

from .config import Settings
from .errors import ServiceError


def inspect_media(path: Path, kind: str, settings: Settings) -> dict:
    if kind == "IMAGE":
        try:
            with Image.open(path) as picture:
                image_format, width, height = picture.format, *picture.size
                if image_format not in {"JPEG", "PNG", "GIF", "WEBP"} or not 1 <= width <= 8192 or not 1 <= height <= 8192:
                    raise ValueError("unsupported image")
                picture.verify()
            return {"mediaType": "image/" + {"JPEG": "jpeg", "PNG": "png", "GIF": "gif", "WEBP": "webp"}[image_format],
                    "durationMs": None, "width": width, "height": height}
        except (OSError, ValueError, UnidentifiedImageError, Image.DecompressionBombError) as exc:
            raise ServiceError("INVALID_MEDIA", "图片无法解码或超过尺寸限制") from exc
    try:
        with wave.open(str(path), "rb") as audio:
            if audio.getcomptype() != "NONE" or audio.getnchannels() < 1 or audio.getframerate() < 1:
                raise ValueError("invalid wav")
            frames = audio.getnframes()
            expected = frames * audio.getnchannels() * audio.getsampwidth()
            actual = 0
            while chunk := audio.readframes(65536):
                actual += len(chunk)
            if actual != expected:
                raise ValueError("truncated wav")
            duration = round(frames * 1000 / audio.getframerate())
            media_type = "audio/wav"
    except (wave.Error, EOFError):
        try:
            info = subprocess.run([settings.ffprobe, "-v", "error", "-show_entries", "format=duration,format_name:stream=codec_type", "-of", "json", str(path)],
                                  capture_output=True, timeout=30, check=True)
            parsed = json.loads(info.stdout)
            if not any(stream.get("codec_type") == "audio" for stream in parsed.get("streams", [])):
                raise ValueError("no audio stream")
            duration = round(float(parsed["format"]["duration"]) * 1000)
            media_type = "audio/mp4" if "mp4" in parsed["format"]["format_name"] else "audio/mpeg"
            subprocess.run([settings.ffmpeg, "-v", "error", "-xerror", "-i", str(path), "-map", "0:a:0", "-f", "null", "-"],
                           capture_output=True, timeout=180, check=True)
        except FileNotFoundError as exc:
            raise ServiceError("MEDIA_DECODER_UNAVAILABLE", "非WAV录音需要服务端配置ffprobe与ffmpeg", status=503) from exc
        except (subprocess.SubprocessError, ValueError, KeyError, OverflowError) as exc:
            raise ServiceError("INVALID_MEDIA", "录音无法完整解码") from exc
    except (ValueError, OSError) as exc:
        raise ServiceError("INVALID_MEDIA", "录音无法完整解码") from exc
    if not 0 < duration <= settings.max_audio_duration_ms:
        raise ServiceError("INVALID_DURATION", "录音时长为空或超过项目上限")
    return {"mediaType": media_type, "durationMs": duration, "width": None, "height": None}


def grant_signature(key: str, grant_id: str, asset_id: str, expiry: int) -> str:
    data = f"{grant_id}\n{asset_id}\n{expiry}".encode()
    return hmac.new(key.encode(), data, hashlib.sha256).hexdigest()


def grant_url(settings: Settings, grant: dict) -> str:
    signature = grant_signature(settings.signing_key, grant["id"], grant["asset_id"], grant["expires_at"])
    return f"{settings.public_base_url.rstrip('/')}/provider-assets/{grant['asset_id']}?" + urlencode({"grantId": grant["id"], "exp": grant["expires_at"], "sig": signature})
