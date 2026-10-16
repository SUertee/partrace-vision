"""Validated environment configuration; database credentials stay private."""

from pathlib import Path
from typing import Literal

from pydantic import Field, SecretStr, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict
from psycopg import ProgrammingError
from psycopg.conninfo import conninfo_to_dict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_prefix="PARTRACE_",
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
        hide_input_in_errors=True,
    )

    environment: Literal["development", "staging", "production"] = "development"
    database_url: SecretStr = Field(repr=False)
    database_timeout_seconds: int = Field(default=5, ge=1, le=30)
    auth_file: Path = Path(__file__).resolve().parents[2] / ".local-state/auth.json"
    configs_dir: Path = Path(__file__).resolve().parents[2] / ".local-state/configs"
    config_pins_file: Path = Path(__file__).resolve().parents[2] / ".local-state/config-pins.json"
    media_root: Path = Path(__file__).resolve().parents[2] / ".local-state/media"
    schema_path: Path = Path(__file__).resolve().parents[2] / "docs/database/schema.sql"
    clock_file: Path | None = None
    ffprobe_path: str = "ffprobe"
    ffmpeg_path: str = "ffmpeg"
    max_upload_bytes: int = Field(default=64 * 1024 * 1024, ge=1024, le=1024 * 1024 * 1024)
    max_manifest_bytes: int = Field(default=2 * 1024 * 1024, ge=1024, le=8 * 1024 * 1024)
    max_result_items: int = Field(default=5000, ge=1, le=50000)
    database_schema: str = Field(default="public", pattern=r"^[a-z_][a-z0-9_]{0,62}$")
    database_pool_size: int = Field(default=4, ge=1, le=16)
    agent_enabled: bool = False
    agent_cursor_secret_file: Path = Path(__file__).resolve().parents[2] / ".local-state/agent-cursor.key"
    agent_model: str | None = Field(default=None, min_length=1, max_length=100)
    agent_model_version: str = Field(default="v1", min_length=1, max_length=80)
    agent_api_key: SecretStr | None = Field(default=None, repr=False)
    agent_base_url: str | None = None
    agent_timeout_seconds: int = Field(default=60, ge=1, le=300)
    agent_max_concurrent_runs: int = Field(default=2, ge=1, le=8)
    agent_model_calls: int = Field(default=8, ge=1, le=20)
    agent_tool_calls: int = Field(default=12, ge=1, le=30)
    embedding_model: str | None = Field(default=None, min_length=1, max_length=100)
    embedding_model_version: str = Field(default="v1", min_length=1, max_length=80)
    embedding_dimensions: int | None = Field(default=None, ge=1, le=16000)
    embedding_api_key: SecretStr | None = Field(default=None, repr=False)
    embedding_base_url: str | None = None
    embedding_timeout_seconds: int = Field(default=20, ge=1, le=120)

    @field_validator("agent_model", "agent_base_url", "embedding_model", "embedding_base_url", "embedding_dimensions", mode="before")
    @classmethod
    def empty_optional_model_setting(cls, value):
        return None if value == "" else value

    @field_validator("agent_base_url", "embedding_base_url")
    @classmethod
    def validate_model_url(cls, value: str | None) -> str | None:
        if value is None:
            return value
        from urllib.parse import urlsplit
        parsed = urlsplit(value)
        if (parsed.scheme not in {"https", "http"} or not parsed.hostname
                or parsed.username or parsed.password or parsed.query or parsed.fragment):
            raise ValueError("model base URL must be an HTTP(S) origin/path without credentials, query or fragment")
        if parsed.scheme == "http" and parsed.hostname not in {"127.0.0.1", "localhost", "::1"}:
            raise ValueError("unencrypted model transport is allowed only on loopback")
        return value.rstrip("/")

    @field_validator("database_url")
    @classmethod
    def validate_database_url(cls, value: SecretStr) -> SecretStr:
        raw = value.get_secret_value()
        if not raw.startswith(("postgresql://", "postgres://")):
            raise ValueError("database URL must be a PostgreSQL connection URL")
        try:
            connection = conninfo_to_dict(raw)
        except (ProgrammingError, ValueError):
            raise ValueError("database URL is invalid") from None
        if not connection.get("host") or not connection.get("dbname"):
            raise ValueError("database URL must specify a host and database")
        return value
