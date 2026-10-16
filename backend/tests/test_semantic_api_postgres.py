"""HTTP contract and revocation tests for the two real vector endpoints.

Every database test uses the shared isolated PostgreSQL+pgvector schema fixture.
The encoder is explicitly a mock restricted to the imported mock recording; no
model network calls or substituted database are used.
"""

import asyncio
import hashlib
import json

from fastapi.testclient import TestClient
import pytest

from backend.app import create_app
from test_api_postgres import check, context, private_json  # noqa: F401


pytestmark = pytest.mark.integration


class StubEncoder:
    model_name = "mock-text-encoder"
    model_version = "mock-v1-preprocess-demo"
    dimensions = 3
    mock = True

    def __init__(self):
        self.calls = []
        self.after_await = None

    async def encode(self, text):
        self.calls.append(text)
        await asyncio.sleep(0)
        if self.after_await is not None:
            self.after_await()
        return [1.0, 0.0, 0.0]


def vector_payload():
    return dict(
        embedding_id="http_embedding", event_id="evt_mock_001", evidence_id="evd_mock_001",
        source_text="mock evidence description", embedding=[1.0, 0.0, 0.0],
        model_name=StubEncoder.model_name, model_version=StubEncoder.model_version,
        dimensions=StubEncoder.dimensions,
    )


def query_payload(context):
    return dict(query_text="寻找货架交互", filter=context["query"], top_k=5)


@pytest.fixture
def http(context):
    # Explicitly prevent unrelated environment model configuration from causing
    # an external call in this deterministic contract test.
    context["settings"].agent_enabled = False
    context["settings"].embedding_model = None
    context["settings"].embedding_api_key = None
    encoder = StubEncoder()
    app = create_app(settings=context["settings"], text_encoder=encoder)
    with TestClient(app, headers={"Authorization": "Bearer admin-test-secret"}) as client:
        check(client.post("/api/v1/recordings", json=context["manifest"]), "ImportResponse", 201)
        yield context, client, encoder


def test_embedding_http_first_replay_changed_and_source_duplicate(http):
    _, client, _ = http
    payload = vector_payload()
    first = check(client.post("/api/v1/event-embeddings", json=payload), "EventEmbeddingResponse", 201)
    replay = check(client.post("/api/v1/event-embeddings", json=payload), "EventEmbeddingResponse", 200)
    assert not first["replayed"] and replay["replayed"]
    assert first["event_embedding"] == replay["event_embedding"]
    changed = dict(payload, embedding=[0.0, 1.0, 0.0])
    assert check(client.post("/api/v1/event-embeddings", json=changed), "ErrorResponse", 409)["error"]["code"] == "idempotency_conflict"
    duplicated = dict(changed, embedding_id="same_source_other_id")
    assert check(client.post("/api/v1/event-embeddings", json=duplicated), "ErrorResponse", 409)["error"]["code"] == "duplicate_embedding_source"


def test_search_http_schema_coverage_and_readonly_can_search(http):
    context, client, encoder = http
    request = query_payload(context)
    unindexed = check(client.post("/api/v1/events/search", json=request), "SemanticSearchResponse")
    assert unindexed["search_status"] == "unindexed"
    assert unindexed["coverage"] == {"eligible_event_count": 1, "indexed_event_count": 0, "missing_event_count": 1}
    assert encoder.calls == []
    check(client.post("/api/v1/event-embeddings", json=vector_payload()), "EventEmbeddingResponse", 201)
    response = check(client.post("/api/v1/events/search", json=request), "SemanticSearchResponse")
    assert response["search_status"] == "matched"
    assert response["coverage"] == {"eligible_event_count": 1, "indexed_event_count": 1, "missing_event_count": 0}
    assert response["candidates"][0]["embedding_id"] == "http_embedding"
    assert response["candidates"][0]["media_status"] == "missing"
    assert response["candidates"][0]["source_mode"] == "mock"
    readonly = {"Authorization": "Bearer readonly-test-secret"}
    check(client.post("/api/v1/events/search", json=request, headers=readonly), "SemanticSearchResponse")
    check(client.post("/api/v1/event-embeddings", json=vector_payload(), headers=readonly), "ErrorResponse", 403)


def test_unconfigured_encoder_is_503_without_mock_fallback(context):
    context["settings"].agent_enabled = False
    context["settings"].embedding_model = None
    context["settings"].embedding_api_key = None
    app = create_app(settings=context["settings"])
    with TestClient(app, headers={"Authorization": "Bearer admin-test-secret"}) as client:
        check(client.post("/api/v1/recordings", json=context["manifest"]), "ImportResponse", 201)
        result = check(client.post("/api/v1/events/search", json=query_payload(context)), "ErrorResponse", 503)
        assert result["error"]["code"] == "model_unavailable"
        assert "candidates" not in result


@pytest.mark.parametrize("endpoint", ["/api/v1/event-embeddings", "/api/v1/events/search"])
def test_semantic_routes_require_bearer_and_json(http, endpoint):
    context, client, _ = http
    body = vector_payload() if endpoint.endswith("event-embeddings") else query_payload(context)
    check(client.post(endpoint, json=body, headers={"Authorization": ""}), "ErrorResponse", 401)
    check(client.post(endpoint, content=json.dumps(body), headers={"Content-Type": "text/plain"}), "ErrorResponse", 415)
    check(client.post(endpoint + "?unexpected=true", json=body), "ErrorResponse", 422)


@pytest.mark.parametrize("endpoint", ["/api/v1/event-embeddings", "/api/v1/events/search"])
@pytest.mark.parametrize("kind", ["empty", "array", "unknown", "nested_unknown", "wrong_type"])
def test_invalid_semantic_http_fields_are_422(http, endpoint, kind):
    context, client, _ = http
    body = vector_payload() if endpoint.endswith("event-embeddings") else query_payload(context)
    if kind == "empty":
        body = {}
    elif kind == "array":
        body = []
    elif kind == "unknown":
        body["client_selected_model"] = "untrusted"
    elif kind == "nested_unknown":
        if endpoint.endswith("event-embeddings"):
            body["embedding"] = {"vector": [1, 0, 0]}
        else:
            body["filter"] = dict(body["filter"], camera_override="untrusted")
    elif endpoint.endswith("event-embeddings"):
        body["dimensions"] = True
    else:
        body["top_k"] = True
    check(client.post(endpoint, json=body), "ErrorResponse", 422)


@pytest.mark.parametrize("value", [None, True, "0.5", 1.1, -1.1])
def test_search_min_score_must_be_an_optional_finite_number_not_null(http, value):
    context, client, _ = http
    body = query_payload(context)
    body["min_score"] = value
    check(client.post("/api/v1/events/search", json=body), "ErrorResponse", 422)


@pytest.mark.parametrize("endpoint", ["/api/v1/event-embeddings", "/api/v1/events/search"])
@pytest.mark.parametrize("kind", ["duplicate", "nested_duplicate", "nan"])
def test_duplicate_json_members_and_nonfinite_literals_are_400(http, endpoint, kind):
    context, client, _ = http
    body = vector_payload() if endpoint.endswith("event-embeddings") else query_payload(context)
    encoded = json.dumps(body)
    if kind == "duplicate":
        encoded = encoded[:-1] + ', "duplicate":1, "duplicate":2}'
    elif kind == "nested_duplicate":
        if endpoint.endswith("event-embeddings"):
            encoded = encoded[:-1] + ', "unknown":{"key":1,"key":2}}'
        else:
            encoded = encoded.replace('"zone_id": "E3"', '"zone_id":"E3","zone_id":"E3"')
    elif endpoint.endswith("event-embeddings"):
        encoded = encoded.replace("[1.0, 0.0, 0.0]", "[NaN, 0.0, 0.0]")
    else:
        encoded = encoded[:-1] + ', "min_score":NaN}'
    check(client.post(endpoint, content=encoded, headers={"Content-Type": "application/json"}), "ErrorResponse", 400)


@pytest.mark.parametrize("revocation,status", [("token", 401), ("scope", 403), ("camera", 403), ("recording", 403), ("identity", 403)])
def test_token_or_grant_revoked_during_await_discards_the_entire_result(http, revocation, status):
    context, client, encoder = http
    check(client.post("/api/v1/event-embeddings", json=vector_payload()), "EventEmbeddingResponse", 201)
    def revoke():
        registry = json.loads(context["settings"].auth_file.read_text())
        digest = hashlib.sha256(b"admin-test-secret").hexdigest()
        entry = next(token for token in registry["tokens"] if token["token_sha256"] == digest)
        if revocation == "token":
            registry["tokens"].remove(entry)
        elif revocation == "scope":
            entry["scopes"] = []
        elif revocation == "camera":
            entry["camera_ids"] = []
        elif revocation == "recording":
            entry["recording_ids"] = []
        else:
            entry["user_id"] = "other"
        private_json(context["settings"].auth_file, registry)
    encoder.after_await = revoke
    result = check(client.post("/api/v1/events/search", json=query_payload(context)), "ErrorResponse", status)
    assert len(encoder.calls) == 1
    assert "candidates" not in result
    assert "mock evidence description" not in json.dumps(result)
