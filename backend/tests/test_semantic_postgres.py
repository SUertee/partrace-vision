"""Exact semantic retrieval against an isolated real PostgreSQL+pgvector schema.

The injected encoder is explicitly mock and can only search mock recordings.
No external embedding provider is called by these tests.
"""

import asyncio
import copy
import hashlib
import math
import struct
from concurrent.futures import ThreadPoolExecutor

from jsonschema import Draft202012Validator, FormatChecker
import psycopg
import pytest

from backend.core.errors import ApiError
from backend.core.models import Query, RecordingManifest
from backend.services.semantic import SemanticService, normalize_vector
from backend.services.facts import BackendService, canonical
from test_api_postgres import SPEC, context  # noqa: F401 — shared isolated schema fixture


pytestmark = pytest.mark.integration


class MockEncoder:
    model_name = "mock-text-encoder"
    model_version = "mock-v1-preprocess-demo"
    dimensions = 3
    mock = True

    def __init__(self, vector=None):
        self.vector = [1.0, 0.0, 0.0] if vector is None else vector
        self.calls = []
        self.callback = None

    async def encode(self, text):
        self.calls.append(text)
        if self.callback:
            self.callback()
        return list(self.vector)


def validate(value, schema):
    Draft202012Validator(
        {"$ref": f"#/components/schemas/{schema}", "components": SPEC["components"]},
        format_checker=FormatChecker(),
    ).validate(value)
    return value


def expect(status, operation):
    with pytest.raises(ApiError) as caught:
        operation()
    assert caught.value.status_code == status
    return caught.value


@pytest.fixture
def setup(context):
    backend = BackendService(context["settings"])
    principal = backend.authenticate("admin-test-secret")
    payload = copy.deepcopy(context["manifest"])
    for suffix, start in (("002", 50000), ("003", 70000)):
        event = copy.deepcopy(payload["events"][0])
        event.update(event_id="evt_mock_" + suffix, start_offset_ms=start, end_offset_ms=start + 10000)
        payload["events"].append(event)
        frame = copy.deepcopy(payload["evidence"][0])
        frame.update(evidence_id="evd_mock_" + suffix, event_id=event["event_id"], offset_ms=start + 1000)
        payload["evidence"].append(frame)
    backend.import_recording(principal, RecordingManifest.model_validate(payload), {})
    encoder = MockEncoder()
    service = SemanticService(backend, encoder)
    query = Query.model_validate(context["query"])
    yield context, backend, principal, encoder, service, query
    backend.close()


def embedding(identifier="emb_001", *, event="evt_mock_001", frame=None, text="蓝色衣服靠近货架", vector=None, model="mock-text-encoder", version="mock-v1-preprocess-demo"):
    vector = [1.0, 0.0, 0.0] if vector is None else vector
    return dict(embedding_id=identifier, event_id=event, evidence_id=frame, source_text=text,
                embedding=vector, model_name=model, model_version=version, dimensions=len(vector))


def search(service, principal, query, *, top_k=10, min_score=None):
    return validate(asyncio.run(service.search(principal, "  蓝衣服  ", query, top_k, min_score)), "SemanticSearchResponse")


def test_insert_normalizes_float32_and_replays_full_request(setup):
    _, backend, principal, _, service, _ = setup
    payload = embedding(text="  蓝色衣服靠近货架  ", vector=[0.1, 0.2, 0.3], frame="evd_mock_001")
    result, status = service.insert_embedding(principal, payload)
    validate(result, "EventEmbeddingResponse")
    assert status == 201 and not result["replayed"]
    row = result["event_embedding"]
    assert row["source_text"] == "蓝色衣服靠近货架"
    assert row["embedding"] == [struct.unpack("!f", struct.pack("!f", v))[0] for v in payload["embedding"]]
    retry = dict(payload, source_text=row["source_text"], embedding=row["embedding"])
    replayed, status = service.insert_embedding(principal, retry)
    assert status == 200 and replayed["replayed"]
    assert replayed["event_embedding"] == row
    changed = dict(payload, embedding=[0.9, 0.2, 0.3])
    assert expect(409, lambda: service.insert_embedding(principal, changed)).code == "idempotency_conflict"
    duplicate = dict(changed, embedding_id="another_id")
    assert expect(409, lambda: service.insert_embedding(principal, duplicate)).code == "duplicate_embedding_source"
    with backend.transaction(principal, "data:read") as (connection, _):
        event = connection.execute("SELECT * FROM events WHERE event_id='evt_mock_001'").fetchone()
        frame = connection.execute("SELECT * FROM evidence WHERE evidence_id='evd_mock_001'").fetchone()
    expected_content = {
        "source_text": row["source_text"],
        "event": {k: event[k] for k in ("event_id", "recording_id", "zone_id", "track_key", "start_offset_ms", "end_offset_ms")},
        "recording": {"camera_id": "cam_demo_01", "config_version": "demo_v1"},
        "evidence": {k: frame[k] for k in ("evidence_id", "event_id", "offset_ms", "bbox_json")},
    }
    assert row["content_sha256"] == hashlib.sha256(canonical(expected_content)).hexdigest()


@pytest.mark.parametrize("vector", [[0, 0, 0], [1e-100, 0, 0], [math.inf, 1, 0], [math.nan, 1, 0], [1e40, 1, 0], [True, 1, 0]])
def test_nonfinite_zero_underflow_overflow_and_boolean_vectors_rejected(setup, vector):
    _, _, principal, _, service, _ = setup
    expect(422, lambda: service.insert_embedding(principal, embedding(vector=vector)))


def test_embedding_dimensions_unknown_fields_and_frame_membership(setup):
    _, _, principal, _, service, _ = setup
    service.insert_embedding(principal, embedding())
    expect(422, lambda: service.insert_embedding(principal, embedding("dim4", text="different", vector=[1, 0, 0, 0])))
    expect(422, lambda: service.insert_embedding(principal, dict(embedding("extra"), unexpected=True)))
    expect(422, lambda: service.insert_embedding(principal, embedding("wrong_frame", frame="evd_mock_002")))
    expect(422, lambda: service.insert_embedding(principal, dict(embedding("wrong_length"), dimensions=4)))
    different_space, status = service.insert_embedding(principal, embedding("other_model", model="another-model", vector=[1, 0, 0, 0]))
    assert status == 201 and different_space["event_embedding"]["dimensions"] == 4


def test_authorization_before_encoding_and_writes(setup):
    _, backend, principal, encoder, service, query = setup
    for credential, status in (("viewer", 403), ("readonly", 403), ("disabled", 403), ("restricted", 403), ("empty", 403), ("nomap", 401)):
        denied = backend.authenticate(credential + "-test-secret")
        expect(status, lambda: service.insert_embedding(denied, embedding()))
    for credential, status in (("disabled", 403), ("restricted", 403), ("empty", 403), ("nomap", 401)):
        denied = backend.authenticate(credential + "-test-secret")
        expect(status, lambda: asyncio.run(service.search(denied, "question", query, 5)))
    invalid_query = query.model_copy(update={"zone_id": "UNKNOWN"})
    expect(422, lambda: asyncio.run(service.search(principal, "question", invalid_query, 5)))
    assert encoder.calls == []


def test_all_unindexed_partial_coverage_threshold_and_other_model_dimensions(setup):
    _, _, principal, encoder, service, query = setup
    initial = search(service, principal, query)
    assert initial["search_status"] == "unindexed"
    assert initial["coverage"] == {"eligible_event_count": 3, "indexed_event_count": 0, "missing_event_count": 3}
    assert encoder.calls == []  # No paid encoding when no candidate has an index.
    service.insert_embedding(principal, embedding(vector=[0, 1, 0]))
    # Different dimensions cannot enter the cosine operator or indexed coverage.
    service.insert_embedding(principal, embedding("different_dim", event="evt_mock_002", model="other", vector=[1, 0, 0, 0]))
    matched = search(service, principal, query, top_k=1)
    assert matched["search_status"] == "matched"
    assert matched["coverage"] == {"eligible_event_count": 3, "indexed_event_count": 1, "missing_event_count": 2}
    assert matched["query_text"] == "蓝衣服"
    assert matched["candidates"][0]["score"] == 0
    assert matched["candidates"][0]["media_status"] is None
    assert matched["candidates"][0]["source_mode"] == "mock"
    excluded = search(service, principal, query, min_score=0.9)
    assert excluded["search_status"] == "no_matches" and excluded["candidates"] == []
    assert excluded["coverage"] == matched["coverage"]


def test_latest_description_per_source_then_best_per_event_and_stable_order(setup):
    _, _, principal, _, service, query = setup
    service.insert_embedding(principal, embedding("old_text", text="older source text", vector=[1, 0, 0]))
    # Time and ID order are deterministic, even if both append within one ms.
    service.insert_embedding(principal, embedding("zz_new_text", text="latest source text", vector=[0, 1, 0]))
    service.insert_embedding(principal, embedding("frame_best", frame="evd_mock_001", text="frame source", vector=[1, 1, 0]))
    service.insert_embedding(principal, embedding("event_2", event="evt_mock_002", vector=[1, 1, 0]))
    response = search(service, principal, query)
    assert [c["event_id"] for c in response["candidates"]] == ["evt_mock_001", "evt_mock_002"]
    assert response["candidates"][0]["embedding_id"] == "frame_best"
    assert response["candidates"][0]["media_status"] == "missing"
    assert abs(response["candidates"][0]["score"] - 2 ** -0.5) < 1e-6
    assert response["coverage"]["indexed_event_count"] == 2
    assert search(service, principal, query, top_k=1)["coverage"]["indexed_event_count"] == 2
    assert search(service, principal, query, min_score=0.9)["candidates"] == []


def test_empty_half_open_window_and_outside_recording(setup):
    _, _, principal, encoder, service, query = setup
    empty = query.model_copy(update={"start_offset_ms": 48000, "end_offset_ms": 50000})
    response = search(service, principal, empty)
    assert response["search_status"] == "no_matches"
    assert response["coverage"]["eligible_event_count"] == 0
    outside = query.model_copy(update={"start_offset_ms": 130000, "end_offset_ms": 140000})
    response = search(service, principal, outside)
    assert response["recording_range"]["relation"] == "outside"
    assert response["coverage"]["eligible_event_count"] == 0
    assert encoder.calls == []


def test_no_encoder_and_mock_real_source_mismatch_fail_closed(setup):
    _, backend, principal, encoder, service, query = setup
    absent = SemanticService(backend)
    assert expect(503, lambda: search(absent, principal, query)).code == "model_unavailable"
    encoder.mock = False
    assert expect(503, lambda: search(service, principal, query)).code == "model_unavailable"
    encoder.mock = True
    expect(503, lambda: service._model({"source_mode": "prerecorded"}))
    assert encoder.calls == []


@pytest.mark.parametrize("vector", [[0, 0, 0], [1, 2], [math.nan, 1, 0]])
def test_bad_provider_vectors_are_503_not_success_or_client_error(setup, vector):
    _, _, principal, encoder, service, query = setup
    service.insert_embedding(principal, embedding())
    encoder.vector = vector
    assert expect(503, lambda: search(service, principal, query)).code == "model_unavailable"


def test_encoder_fault_is_503_and_does_not_leak_details(setup):
    _, _, principal, encoder, service, query = setup
    service.insert_embedding(principal, embedding())
    def fail():
        raise RuntimeError("private-provider-key")
    encoder.callback = fail
    error = expect(503, lambda: search(service, principal, query))
    assert error.code == "model_unavailable" and "private-provider-key" not in str(error)


def test_database_and_model_are_rechecked_after_encoding_without_holding_user_lock(setup):
    _, backend, principal, encoder, service, query = setup
    service.insert_embedding(principal, embedding())
    def disable():
        with backend.pool.connection() as connection:
            connection.execute("UPDATE users SET status='disabled' WHERE user_id='admin'")
    encoder.callback = disable
    expect(403, lambda: search(service, principal, query))
    assert len(encoder.calls) == 1


def test_changed_encoding_space_is_not_used_for_old_query_vector(setup):
    _, _, principal, encoder, service, query = setup
    service.insert_embedding(principal, embedding())
    encoder.callback = lambda: setattr(encoder, "model_version", "changed-version")
    assert expect(503, lambda: search(service, principal, query)).code == "model_unavailable"


def test_postgres_storage_fault_does_not_become_unindexed(setup, monkeypatch):
    import backend.services.semantic as module
    _, _, principal, _, service, query = setup
    monkeypatch.setattr(module, "_COVERAGE", "SELECT column_that_does_not_exist")
    assert expect(503, lambda: search(service, principal, query)).code == "semantic_storage_unavailable"


def test_concurrent_identical_vector_writes_publish_once(setup):
    _, _, principal, _, service, _ = setup
    with ThreadPoolExecutor(max_workers=3) as executor:
        results = list(executor.map(lambda _: service.insert_embedding(principal, embedding()), range(3)))
    assert sorted(status for _, status in results) == [200, 200, 201]
    rows = [result["event_embedding"] for result, _ in results]
    assert rows[0] == rows[1] == rows[2]


@pytest.mark.parametrize("values,dimensions", [([1e-100], 1), ([1e40], 1), ([True], 1), ([1], True), ([1], 0), ([1], 2)])
def test_vector_conversion_boundary_without_database(values, dimensions):
    expect(422, lambda: normalize_vector(values, dimensions))
