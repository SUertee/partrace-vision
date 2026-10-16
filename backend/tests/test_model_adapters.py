"""Provider transport/configuration boundaries without external AI requests."""

import asyncio

import httpx
import pytest
from pydantic import ValidationError

from backend.core.settings import Settings
from backend.core.errors import ApiError
from backend.ai.providers import CompatibleTextEncoder, make_encoder, make_engine


def settings(**values):
    return Settings(_env_file=None, database_url="postgresql://test:private@localhost/test", **values)


def test_missing_configuration_never_constructs_a_fake_provider():
    configuration = settings()
    assert make_engine(configuration) is make_encoder(configuration) is None
    configuration = settings(agent_model="", agent_base_url="", embedding_model="", embedding_base_url="", embedding_dimensions="")
    assert configuration.embedding_dimensions is None
    assert make_engine(configuration) is make_encoder(configuration) is None


def test_chat_adapter_constructs_real_bounded_engine_without_network():
    from agent import AgentEngine
    configuration = settings(agent_model="provider-model", agent_api_key="private-test-key",
                             agent_base_url="https://provider.example.invalid/v1")
    engine = make_engine(configuration)
    assert isinstance(engine, AgentEngine)
    assert engine.model.model_name == "provider-model"
    assert engine.limits.model_calls == 8 and engine.limits.tool_calls == 12
    assert "private-test-key" not in repr(configuration)
    assert "private-test-key" not in configuration.model_dump_json()
    assert "private-test-key" not in repr(engine.model)


@pytest.mark.parametrize("url", ["https://user:password@example.org/v1", "https://example.org?api_key=secret",
                                      "https://example.org/#key", "http://example.org/v1", "file:///tmp/model"])
def test_model_urls_disallow_credentials_and_insecure_remote_transport(url):
    with pytest.raises(ValidationError):
        settings(agent_base_url=url)


@pytest.mark.parametrize("mode", ["ok", "wrong_model", "wrong_index", "multiple", "provider_error", "invalid_json", "timeout"])
def test_embedding_transport_has_fixed_space_and_sanitized_errors(monkeypatch, mode):
    configuration = settings(embedding_model="fixed-model", embedding_dimensions=3,
                             embedding_api_key="private-embedding-key", embedding_base_url="https://provider.example.invalid/v1")
    captured = []

    async def handle(request):
        captured.append(request)
        if mode == "timeout":
            raise httpx.ReadTimeout("private-embedding-key in provider detail")
        if mode == "invalid_json":
            return httpx.Response(200, text="not JSON private-embedding-key")
        data = {"model": "other" if mode == "wrong_model" else "fixed-model", "data": [
            {"index": 2 if mode == "wrong_index" else 0, "embedding": [1, 2, 3]},
        ]}
        if mode == "multiple":
            data["data"].append(data["data"][0])
        return httpx.Response(401 if mode == "provider_error" else 200, json=data)

    real_client = httpx.AsyncClient
    monkeypatch.setattr(httpx, "AsyncClient", lambda **kwargs: real_client(transport=httpx.MockTransport(handle), **kwargs))
    encoder = make_encoder(configuration)
    assert isinstance(encoder, CompatibleTextEncoder)
    assert encoder.mock is False
    if mode == "ok":
        assert asyncio.run(encoder.encode("a description")) == [1, 2, 3]
    else:
        with pytest.raises(ApiError) as error:
            asyncio.run(encoder.encode("a description"))
        assert error.value.code == "model_unavailable" and error.value.status_code == 503
        assert "private-embedding-key" not in str(error.value)
    assert str(captured[0].url) == "https://provider.example.invalid/v1/embeddings"
    assert captured[0].headers["authorization"] == "Bearer private-embedding-key"


def test_embedding_cancellation_is_not_reported_as_provider_failure(monkeypatch):
    configuration = settings(embedding_model="fixed", embedding_dimensions=3, embedding_api_key="private")
    async def cancel(*args, **kwargs):
        raise asyncio.CancelledError
    monkeypatch.setattr(httpx.AsyncClient, "post", cancel)
    with pytest.raises(asyncio.CancelledError):
        asyncio.run(CompatibleTextEncoder(configuration).encode("query"))
