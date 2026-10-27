from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path


@dataclass
class Settings:
    database_path: Path = field(default_factory=lambda: Path(os.getenv("JOB_DATABASE_PATH", "./var/jobs.db")))
    asset_directory: Path = field(default_factory=lambda: Path(os.getenv("ASSET_DIRECTORY", "./var/assets")))
    token_hash: str = field(default_factory=lambda: os.getenv("PROXY_CLIENT_TOKEN_HASH", ""))
    signing_key: str = field(default_factory=lambda: os.getenv("ASSET_URL_SIGNING_KEY", ""))
    public_base_url: str = field(default_factory=lambda: os.getenv("PUBLIC_BASE_URL", ""))
    deepseek_key: str = field(default_factory=lambda: os.getenv("DEEPSEEK_API_KEY", ""))
    deepseek_base_url: str = field(default_factory=lambda: os.getenv("DEEPSEEK_BASE_URL", "https://api.deepseek.com"))
    deepseek_model: str = field(default_factory=lambda: os.getenv("DEEPSEEK_MODEL", "deepseek-flash"))
    dashscope_key: str = field(default_factory=lambda: os.getenv("DASHSCOPE_API_KEY", ""))
    dashscope_base_url: str = field(default_factory=lambda: os.getenv("DASHSCOPE_BASE_URL", "https://dashscope.aliyuncs.com/api/v1"))
    asr_model: str = field(default_factory=lambda: os.getenv("ASR_MODEL", "qwen3-asr-flash-filetrans"))
    asr_result_hosts: tuple[str, ...] = field(default_factory=lambda: tuple(os.getenv("ASR_RESULT_HOSTS", "dashscope-result-bj.oss-cn-beijing.aliyuncs.com,dashscope-result-sg.oss-ap-southeast-1.aliyuncs.com").split(",")))
    max_audio_bytes: int = field(default_factory=lambda: int(os.getenv("MAX_AUDIO_BYTES", "262144000")))
    max_audio_duration_ms: int = field(default_factory=lambda: int(os.getenv("MAX_AUDIO_DURATION_MS", "7200000")))
    max_image_bytes: int = field(default_factory=lambda: int(os.getenv("MAX_IMAGE_BYTES", "10485760")))
    asset_ttl_hours: int = field(default_factory=lambda: int(os.getenv("ASSET_TTL_HOURS", "24")))
    result_ttl_hours: int = field(default_factory=lambda: int(os.getenv("RESULT_TTL_HOURS", "168")))
    ffprobe: str = field(default_factory=lambda: os.getenv("FFPROBE_PATH", "ffprobe"))
    ffmpeg: str = field(default_factory=lambda: os.getenv("FFMPEG_PATH", "ffmpeg"))
    worker_enabled: bool = True
    lease_seconds: int = 90
    job_deadline_seconds: int = 21600
    poll_seconds: float = 5.0
    retry_base_seconds: float = 2.0

    @property
    def asr_configured(self) -> bool:
        return bool(self.dashscope_key and self.signing_key and self.public_base_url.startswith("https://"))

    def prepare(self) -> None:
        self.database_path.parent.mkdir(parents=True, exist_ok=True)
        self.asset_directory.mkdir(parents=True, exist_ok=True)
