"""Explicit OpenAI-compatible providers; no model, key or fake fallback by default."""

import asyncio

import httpx
from backend.core.errors import ApiError


def make_engine(settings):
    if not settings.agent_model or not settings.agent_api_key or not settings.agent_api_key.get_secret_value():
        return None
    from langchain_openai import ChatOpenAI
    from agent.engine import AgentEngine, AgentLimits
    model = ChatOpenAI(
        model=settings.agent_model,
        api_key=settings.agent_api_key.get_secret_value(),
        base_url=settings.agent_base_url,
        temperature=0,
        timeout=settings.agent_timeout_seconds,
        max_retries=0,
    )
    return AgentEngine(model, AgentLimits(
        timeout_seconds=settings.agent_timeout_seconds,
        model_calls=settings.agent_model_calls,
        tool_calls=settings.agent_tool_calls,
    ))


class CompatibleTextEncoder:
    mock = False

    def __init__(self, settings):
        self.model_name = settings.embedding_model
        self.model_version = settings.embedding_model_version
        self.dimensions = settings.embedding_dimensions
        self._key = settings.embedding_api_key.get_secret_value()
        self._url = (settings.embedding_base_url or "https://api.openai.com/v1") + "/embeddings"
        self._timeout = settings.embedding_timeout_seconds

    async def encode(self, text):
        try:
            async with asyncio.timeout(self._timeout):
                async with httpx.AsyncClient(timeout=self._timeout, follow_redirects=False, trust_env=False) as client:
                    response = await client.post(self._url, headers={"Authorization": "Bearer " + self._key}, json={
                        "model": self.model_name, "input": text, "dimensions": self.dimensions,
                    })
                    response.raise_for_status()
                    if len(response.content) > 2 * 1024 * 1024:
                        raise ValueError("embedding response exceeds limit")
                    data = response.json()
                    if data.get("model") != self.model_name or len(data["data"]) != 1 or data["data"][0]["index"] != 0:
                        raise ValueError("embedding response belongs to another model or input")
                    return data["data"][0]["embedding"]
        except asyncio.CancelledError:
            raise
        except Exception:
            raise ApiError(503, "model_unavailable", "语义编码服务不可用，未返回检索结果。") from None


def make_encoder(settings):
    if (not settings.embedding_model or not settings.embedding_dimensions
            or not settings.embedding_api_key or not settings.embedding_api_key.get_secret_value()):
        return None
    return CompatibleTextEncoder(settings)
