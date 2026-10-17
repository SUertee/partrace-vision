"""Scoped optional-tool failures without PostgreSQL or external providers."""

import asyncio
from contextlib import contextmanager
from copy import deepcopy
from types import SimpleNamespace

import pytest

from agent.contracts import ToolUnavailable
from backend.ai.gateway import ScopedToolGateway
from backend.core.errors import ApiError


QUERY = {
    "recording_id": "rec_1", "camera_id": "cam_1", "zone_id": "E3",
    "start_offset_ms": 0, "end_offset_ms": 1000,
}
QUERY_TEXT = "查看货架上的包裹"


class BackendDouble:
    def __init__(self):
        self.principal = SimpleNamespace(user_id="owner")
        self.auth_calls = []
        self.scope_checks = []
        self.error = None

    def authenticate(self, token):
        self.auth_calls.append(token)
        return self.principal

    @contextmanager
    def transaction(self, principal, permission):
        assert principal is self.principal
        assert permission == "data:read"
        yield object(), principal

    def normalize(self, connection, query, principal):
        self.scope_checks.append(query.model_dump(mode="json"))
        assert principal is self.principal
        if self.error is not None:
            raise self.error


class SemanticDouble:
    def __init__(self, backend, *, error=None, after_call=None):
        self.backend = backend
        self.error = error
        self.after_call = after_call
        self.calls = []
        self.result = {"search_status": "matched", "candidates": [{"event_id": "event_1"}]}

    async def search(self, principal, query_text, query, top_k, min_score):
        # Authorization has completed before an optional provider is contacted.
        assert self.backend.scope_checks == [QUERY]
        self.calls.append((principal.user_id, query_text, query.model_dump(mode="json"), top_k, min_score))
        if self.after_call is not None:
            self.after_call()
        if self.error is not None:
            raise self.error
        return self.result


def invoke(backend, semantic, *, query=None, query_text=QUERY_TEXT):
    gateway = ScopedToolGateway(
        backend, semantic, "controlled-test-token", "owner", deepcopy(QUERY), QUERY_TEXT
    )
    return asyncio.run(gateway.semantic_search_events(
        deepcopy(QUERY if query is None else query), query_text, 4, 0.2
    ))


def test_semantic_success_checks_scope_before_and_after_provider_call():
    backend = BackendDouble()
    semantic = SemanticDouble(backend)

    assert invoke(backend, semantic) is semantic.result
    assert semantic.calls == [("owner", QUERY_TEXT, QUERY, 4, 0.2)]
    assert backend.scope_checks == [QUERY, QUERY]
    assert backend.auth_calls == ["controlled-test-token", "controlled-test-token"]


def test_optional_model_error_is_safe_and_reauthorizes_before_fallback():
    backend = BackendDouble()
    provider_error = ApiError(503, "model_unavailable", "private provider URL and model-key")
    semantic = SemanticDouble(backend, error=provider_error)

    with pytest.raises(ToolUnavailable) as failure:
        invoke(backend, semantic)

    assert str(failure.value) == "model_unavailable"
    assert failure.value.__cause__ is None
    assert failure.value.__suppress_context__ is True
    assert backend.scope_checks == [QUERY, QUERY]
    assert len(semantic.calls) == 1


def test_missing_optional_service_requires_scope_authorization():
    backend = BackendDouble()

    with pytest.raises(ToolUnavailable, match="^model_unavailable$"):
        invoke(backend, None)

    assert backend.scope_checks == [QUERY]


@pytest.mark.parametrize("provider_error", [
    ApiError(503, "semantic_storage_unavailable", "向量数据库不可用。"),
    ApiError(503, "database_unavailable", "数据库不可用。"),
    ApiError(403, "model_unavailable", "禁止访问。"),
    ApiError(401, "unauthorized", "身份失效。"),
    ApiError(422, "validation_error", "参数无效。"),
    RuntimeError("private unknown provider failure"),
])
def test_non_optional_errors_are_never_translated(provider_error):
    backend = BackendDouble()
    semantic = SemanticDouble(backend, error=provider_error)

    with pytest.raises(type(provider_error)) as failure:
        invoke(backend, semantic)

    assert failure.value is provider_error
    assert backend.scope_checks == [QUERY]


@pytest.mark.parametrize("provider_error", [None, ApiError(503, "model_unavailable", "provider unavailable")])
@pytest.mark.parametrize("scope_error", [
    ApiError(403, "forbidden", "录像授权已撤销。"),
    ApiError(503, "database_unavailable", "数据库不可用。"),
])
def test_scope_failure_takes_precedence_over_results_or_optional_outage(provider_error, scope_error):
    backend = BackendDouble()

    def revoke():
        backend.error = scope_error

    semantic = SemanticDouble(backend, error=provider_error, after_call=revoke)
    with pytest.raises(ApiError) as failure:
        invoke(backend, semantic)

    assert failure.value is scope_error
    assert backend.scope_checks == [QUERY, QUERY]


def test_changed_identity_takes_precedence_over_optional_outage():
    backend = BackendDouble()

    def change_identity():
        backend.principal = SimpleNamespace(user_id="another-owner")

    semantic = SemanticDouble(
        backend, error=ApiError(503, "model_unavailable", "provider unavailable"),
        after_call=change_identity,
    )
    with pytest.raises(ApiError) as failure:
        invoke(backend, semantic)

    assert failure.value.code == "forbidden"
    assert len(backend.auth_calls) == 2
    assert backend.scope_checks == [QUERY]


@pytest.mark.parametrize("error", [
    ApiError(403, "forbidden", "录像授权已撤销。"),
    ApiError(503, "database_unavailable", "数据库不可用。"),
])
@pytest.mark.parametrize("missing_service", [False, True])
def test_initial_scope_failure_does_not_contact_or_fall_back_from_provider(error, missing_service):
    backend = BackendDouble()
    backend.error = error
    semantic = SemanticDouble(backend)

    with pytest.raises(ApiError) as failure:
        invoke(backend, None if missing_service else semantic)

    assert failure.value is error
    assert semantic.calls == []


@pytest.mark.parametrize("change", [
    {"recording_id": "other"}, {"camera_id": "other"}, {"zone_id": "other"},
    {"start_offset_ms": 1}, {"end_offset_ms": 999},
])
def test_semantic_tool_cannot_change_any_confirmed_scope_field(change):
    backend = BackendDouble()
    semantic = SemanticDouble(backend)

    with pytest.raises(ApiError) as failure:
        invoke(backend, semantic, query={**QUERY, **change})

    assert failure.value.code == "forbidden"
    assert semantic.calls == []
    assert backend.auth_calls == []


def test_semantic_tool_cannot_change_confirmed_query_text():
    backend = BackendDouble()
    semantic = SemanticDouble(backend)

    with pytest.raises(ApiError) as failure:
        invoke(backend, semantic, query_text="读取其他人的录像")

    assert failure.value.code == "forbidden"
    assert semantic.calls == []
    assert backend.auth_calls == []
