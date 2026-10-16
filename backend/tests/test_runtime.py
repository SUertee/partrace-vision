import asyncio
from pathlib import Path

import psycopg
import pytest
from fastapi import APIRouter
from fastapi.testclient import TestClient
from pydantic import ValidationError

from backend.app import create_app
from backend.core.settings import Settings
from backend.storage.database import DatabaseReadiness, check_database


@pytest.fixture
def settings():
    return Settings(
        _env_file=None,
        database_url="postgresql://test:private-password@localhost/test",
    )


def test_configuration_requires_database_url(monkeypatch):
    monkeypatch.delenv("PARTRACE_DATABASE_URL", raising=False)
    with pytest.raises(ValidationError):
        Settings(_env_file=None)


def test_default_paths_still_resolve_to_repository_after_package_move(settings):
    root = Path(__file__).resolve().parents[2]
    assert settings.auth_file == root / ".local-state/auth.json"
    assert settings.configs_dir == root / ".local-state/configs"
    assert settings.config_pins_file == root / ".local-state/config-pins.json"
    assert settings.media_root == root / ".local-state/media"
    assert settings.schema_path == root / "docs/database/schema.sql"
    assert settings.agent_cursor_secret_file == root / ".local-state/agent-cursor.key"


def test_environment_is_read_and_secrets_are_redacted(monkeypatch):
    monkeypatch.setenv("PARTRACE_DATABASE_URL", "postgresql://test:never-log-me@db/test")
    monkeypatch.setenv("PARTRACE_ENVIRONMENT", "staging")
    settings = Settings(_env_file=None)
    assert settings.environment == "staging"
    assert "never-log-me" not in repr(settings)
    assert "never-log-me" not in settings.model_dump_json()


@pytest.mark.parametrize(
    "url", ["", "sqlite:///test", "postgresql://localhost", "postgresql:///%oops"]
)
def test_invalid_database_url_fails_without_printing_credentials(url):
    with pytest.raises(ValidationError) as error:
        Settings(_env_file=None, database_url=url)
    assert "input_value" not in str(error.value)


@pytest.mark.parametrize(
    ("result", "expected_status", "vector_state"),
    [
        (DatabaseReadiness(True, True), 200, "ok"),
        (DatabaseReadiness(True, False), 503, "missing"),
        (DatabaseReadiness(False, False), 503, "unavailable"),
    ],
)
def test_readiness_and_liveness_are_independent(settings, result, expected_status, vector_state):
    async def probe():
        return result

    with TestClient(create_app(settings=settings, readiness_probe=probe)) as client:
        assert client.get("/health/live").json() == {"status": "ok"}
        response = client.get("/health/ready")
        assert response.status_code == expected_status
        assert response.json()["checks"]["pgvector"] == vector_state
        assert "private-password" not in response.text
        # Data routes require authentication; AI is still not mounted.
        assert client.get("/api/v1/user").status_code == 401
        assert client.get("/api/v1/agent/runs").status_code == 404
        assert client.get("/docs").status_code == 200


@pytest.mark.parametrize("failure", [psycopg.OperationalError, OSError, TimeoutError])
def test_database_failure_is_reported_without_driver_details(settings, monkeypatch, failure):
    async def fail(*args, **kwargs):
        raise failure("private-password: database connection failed")

    monkeypatch.setattr(psycopg.AsyncConnection, "connect", fail)
    result = asyncio.run(check_database(settings))
    assert result == DatabaseReadiness(False, False)


def test_database_probe_checks_extension_with_read_only_connection(settings, monkeypatch):
    calls = {}

    class Cursor:
        async def __aenter__(self):
            return self

        async def __aexit__(self, *args):
            return False

        async def execute(self, sql):
            calls["sql"] = sql

        async def fetchone(self):
            return (1, False)

    class Connection:
        async def __aenter__(self):
            return self

        async def __aexit__(self, *args):
            return False

        def cursor(self):
            return Cursor()

    async def connect(*args, **kwargs):
        calls["options"] = kwargs["options"]
        return Connection()

    monkeypatch.setattr(psycopg.AsyncConnection, "connect", connect)
    assert asyncio.run(check_database(settings)) == DatabaseReadiness(True, False)
    assert "pg_extension" in calls["sql"]
    assert "default_transaction_read_only=on" in calls["options"]


def test_agent_router_is_only_mounted_when_explicitly_supplied(settings):
    router = APIRouter()

    @router.get("/integration-check")
    async def integration_check():
        return {"integrated": True}

    with TestClient(create_app(settings=settings, agent_router=router)) as client:
        assert client.get("/api/v1/agent/integration-check").json() == {"integrated": True}


def test_development_authentication_is_disabled_in_production(settings):
    settings.environment = 'production'
    with TestClient(create_app(settings=settings)) as client:
        response = client.get('/api/v1/user', headers={'Authorization':'Bearer not-a-production-credential'})
        assert response.status_code == 503
        assert response.json()['error']['code'] == 'auth_unavailable'
        assert client.get('/docs').status_code == 404
        assert client.get('/openapi.json').status_code == 404
        assert client.get('/health/live').status_code == 200


def test_declared_oversized_request_rejected_without_reading_body(settings):
    with TestClient(create_app(settings=settings)) as client:
        response = client.post('/api/v1/recordings', content=b'{}', headers={'Content-Length': str(settings.max_upload_bytes + settings.max_manifest_bytes + 2*1024*1024)})
        assert response.status_code == 413
        assert response.json()['error']['request_id'] == response.headers['x-request-id']
        assert 'private-password' not in response.text
