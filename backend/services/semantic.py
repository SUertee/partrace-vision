"""Authorized event-description vectors and exact, single-space retrieval.

This module never invents a query vector or treats a model/storage outage as an
empty result. Image identity, actions and ownership are not established by cosine
similarity. The encoder is supplied by application composition, not a request.
"""

from __future__ import annotations

import hashlib
import json
import math
import struct
from typing import Annotated, Protocol

import psycopg
from pydantic import Field, StringConstraints, ValidationError, model_validator
from starlette.concurrency import run_in_threadpool

from backend.core.auth import Principal
from backend.core.errors import ApiError, invalid
from backend.core.models import Id, Query, StrictModel, UtcQuery
from backend.services.facts import BackendService, canonical, envelope, now, one, utc


class TextEncoder(Protocol):
    model_name: str
    model_version: str
    dimensions: int
    mock: bool

    async def encode(self, text: str) -> list[float]: ...


def normalize_vector(values: list, dimensions: int) -> list[float]:
    """Validate both before and after deterministic IEEE float32 conversion."""
    if type(dimensions) is not int or not 1 <= dimensions <= 16000:
        raise invalid("向量维度须为 1–16000 的整数。")
    if not isinstance(values, list) or len(values) != dimensions:
        raise invalid("向量长度必须与 dimensions 一致。")
    result = []
    try:
        for value in values:
            if type(value) not in (int, float) or not math.isfinite(value):
                raise ValueError("nonfinite or nonnumeric vector")
            converted = struct.unpack("!f", struct.pack("!f", value))[0]
            if not math.isfinite(converted):
                raise ValueError("nonfinite float32 vector")
            result.append(converted)
    except (ValueError, TypeError, OverflowError, struct.error):
        raise invalid("向量分量须为可表示的有限 float32 数值。") from None
    if not any(result):
        raise invalid("向量规范化为 float32 后仍须非全零。")
    return result


class _EmbeddingInput(StrictModel):
    embedding_id: Id
    event_id: Id
    evidence_id: Id | None
    source_text: Annotated[str, StringConstraints(min_length=1, max_length=8000)]
    embedding: Annotated[list[float], Field(min_length=1, max_length=16000)]
    model_name: Annotated[str, StringConstraints(min_length=1)]
    model_version: Annotated[str, StringConstraints(min_length=1)]
    dimensions: Annotated[int, Field(ge=1, le=16000)]

    @model_validator(mode="after")
    def normalized(self):
        self.source_text = self.source_text.strip()
        if not self.source_text or not self.model_name.strip() or not self.model_version.strip():
            raise ValueError("text and model space must not be blank")
        try:
            for text in (self.source_text, self.model_name, self.model_version):
                text.encode("utf-8")
        except UnicodeError:
            raise ValueError("text and model space must contain valid Unicode") from None
        self.embedding = normalize_vector(self.embedding, self.dimensions)
        return self


_COLUMNS = (
    "embedding_id,event_id,evidence_id,source_text,embedding::text AS embedding,"
    "model_name,model_version,dimensions,content_sha256,created_at"
)

# Half-open window semantics match BackendService.list_events, including point
# observations. Space isolation is materialized BEFORE the cosine operator so
# vectors from another dimension can never reach that operator.
_SCOPED = """
WITH scoped_events AS MATERIALIZED (
    SELECT * FROM events
    WHERE recording_id=%s AND zone_id=%s AND start_offset_ms < %s
      AND (end_offset_ms > %s OR
           (start_offset_ms=end_offset_ms AND start_offset_ms >= %s))
), space_vectors AS MATERIALIZED (
    SELECT v.* FROM event_embeddings v JOIN scoped_events e USING(event_id)
    WHERE v.model_name=%s AND v.model_version=%s AND v.dimensions=%s
), latest_sources AS MATERIALIZED (
    SELECT DISTINCT ON(event_id,evidence_id) * FROM space_vectors
    ORDER BY event_id,evidence_id,created_at DESC,embedding_id DESC
)
"""

_COVERAGE = _SCOPED + """
SELECT (SELECT count(*) FROM scoped_events) AS eligible,
       (SELECT count(DISTINCT event_id) FROM latest_sources) AS indexed
"""

_SEARCH = _SCOPED + """
, scored AS MATERIALIZED (
    SELECT *, 1 - (embedding <=> %s::vector) AS score FROM latest_sources
), ranked AS MATERIALIZED (
    SELECT *, row_number() OVER(
        PARTITION BY event_id ORDER BY score DESC,embedding_id DESC
    ) AS event_rank FROM scored
), selected AS MATERIALIZED (
    SELECT * FROM ranked WHERE event_rank=1
      AND (%s::double precision IS NULL OR score >= %s)
    ORDER BY score DESC,event_id,embedding_id DESC LIMIT %s
), counts AS (
    SELECT (SELECT count(*) FROM scoped_events) AS eligible,
           (SELECT count(DISTINCT event_id) FROM latest_sources) AS indexed,
           (SELECT count(*) FROM scored WHERE score IS NULL OR score='NaN'::double precision
              OR score IN ('Infinity'::double precision,'-Infinity'::double precision)) AS invalid_scores
)
SELECT counts.*, hit.event_id,hit.evidence_id,hit.embedding_id,hit.source_text,hit.score,
       e.detection_source,e.observation_status,frame.media_status,frame.storage_key
FROM counts LEFT JOIN selected hit ON TRUE
LEFT JOIN scoped_events e ON e.event_id=hit.event_id
LEFT JOIN evidence frame ON frame.evidence_id=hit.evidence_id AND frame.event_id=hit.event_id
ORDER BY hit.score DESC,hit.event_id,hit.embedding_id DESC
"""


class SemanticService:
    def __init__(self, backend: BackendService, encoder: TextEncoder | None = None):
        self.backend = backend
        self.encoder = encoder

    @staticmethod
    def _stored(row):
        vector = json.loads(row["embedding"]) if isinstance(row["embedding"], str) else list(row["embedding"])
        return dict(row) | {
            "embedding": normalize_vector(vector, row["dimensions"]),
            "created_at": utc(row["created_at"]),
        }

    def insert_embedding(self, principal: Principal, payload: dict) -> tuple[dict, int]:
        try:
            value = _EmbeddingInput.model_validate(payload)
        except ValidationError:
            raise invalid("向量请求字段、描述或数值无效。") from None
        try:
            with self.backend.transaction(principal, "data:ingest", admin=True) as (connection, _):
                event = one(connection, "SELECT * FROM events WHERE event_id=%s", (value.event_id,), "event_not_found")
                recording = self.backend.recording(connection, event["recording_id"], principal)
                frame = None
                if value.evidence_id is not None:
                    frame = one(connection, "SELECT * FROM evidence WHERE evidence_id=%s", (value.evidence_id,), "evidence_not_found")
                    if frame["event_id"] != value.event_id:
                        raise invalid("关键帧必须属于所写入的事件。")
                content = {
                    "source_text": value.source_text,
                    "event": {key: event[key] for key in (
                        "event_id", "recording_id", "zone_id", "track_key", "start_offset_ms", "end_offset_ms",
                    )},
                    "recording": {key: recording[key] for key in ("camera_id", "config_version")},
                    "evidence": {key: frame[key] for key in ("evidence_id", "event_id", "offset_ms", "bbox_json")} if frame else None,
                }
                digest = hashlib.sha256(canonical(content)).hexdigest()
                # Lock ID first, then the one model space. This serializes both
                # cross-space same-ID requests and concurrent dimension choices.
                for key in (
                    ["embedding-id", value.embedding_id],
                    ["embedding-space", value.model_name, value.model_version],
                ):
                    connection.execute("SELECT pg_advisory_xact_lock(hashtextextended(%s,0))", (canonical(key).decode(),))
                old = connection.execute(f"SELECT {_COLUMNS} FROM event_embeddings WHERE embedding_id=%s", (value.embedding_id,)).fetchone()
                if old:
                    old_event = one(connection, "SELECT recording_id FROM events WHERE event_id=%s", (old["event_id"],), "event_not_found")
                    self.backend.recording(connection, old_event["recording_id"], principal)
                    public = self._stored(old)
                    if {key: public[key] for key in type(value).model_fields} != value.model_dump():
                        raise ApiError(409, "idempotency_conflict", "此向量 ID 已用于不同的规范请求。")
                    return envelope(event_embedding=public, replayed=True), 200
                dimensions = connection.execute(
                    "SELECT dimensions FROM event_embeddings WHERE model_name=%s AND model_version=%s AND dimensions<>%s LIMIT 1",
                    (value.model_name, value.model_version, value.dimensions),
                ).fetchone()
                if dimensions is not None:
                    raise invalid("同一模型与编码版本必须保持固定维度。")
                duplicate = connection.execute(
                    "SELECT embedding_id FROM event_embeddings WHERE event_id=%s AND evidence_id IS NOT DISTINCT FROM %s "
                    "AND model_name=%s AND model_version=%s AND content_sha256=%s",
                    (value.event_id, value.evidence_id, value.model_name, value.model_version, digest),
                ).fetchone()
                if duplicate:
                    raise ApiError(409, "duplicate_embedding_source", "同一来源与模型版本的描述已写入，请复用原 ID。")
                row = connection.execute(
                    "INSERT INTO event_embeddings(embedding_id,event_id,evidence_id,source_text,embedding,model_name,model_version,dimensions,content_sha256,created_at) "
                    f"VALUES(%s,%s,%s,%s,%s::vector,%s,%s,%s,%s,%s) RETURNING {_COLUMNS}",
                    (value.embedding_id, value.event_id, value.evidence_id, value.source_text,
                     json.dumps(value.embedding, allow_nan=False), value.model_name, value.model_version,
                     value.dimensions, digest, now()),
                ).fetchone()
                return envelope(event_embedding=self._stored(row), replayed=False), 201
        except psycopg.Error:
            raise ApiError(503, "semantic_storage_unavailable", "向量存储暂时不可用。") from None

    def _model(self, recording) -> tuple[str, str, int, bool]:
        try:
            encoder = self.encoder
            if encoder is None:
                raise ValueError("missing encoder")
            model = (encoder.model_name, encoder.model_version, encoder.dimensions, encoder.mock)
            if any(not isinstance(value, str) or not value.strip() for value in model[:2]):
                raise ValueError("invalid model identity")
            for identity in model[:2]:
                identity.encode("utf-8")
            if type(model[2]) is not int or not 1 <= model[2] <= 16000 or type(model[3]) is not bool:
                raise ValueError("invalid model configuration")
            if model[3] != (recording["source_mode"] == "mock"):
                raise ValueError("mock and real encoding sources cannot mix")
            return model
        except (AttributeError, TypeError, ValueError):
            raise ApiError(503, "model_unavailable", "此来源没有可用的文本编码模型配置。") from None

    @staticmethod
    def _range(query, recording):
        start, end = max(0, query.start_offset_ms), min(recording["duration_ms"], query.end_offset_ms)
        relation = "outside" if end <= start else "full" if query.end_offset_ms <= recording["duration_ms"] else "partial"
        return {"relation": relation, "covered_start_offset_ms": None if relation == "outside" else start,
                "covered_end_offset_ms": None if relation == "outside" else end}

    @staticmethod
    def _args(query, model):
        return (query.recording_id, query.zone_id, query.end_offset_ms,
                query.start_offset_ms, query.start_offset_ms, *model[:3])

    @staticmethod
    def _response(query_text, query, recording_range, model, counts, candidates):
        eligible, indexed = int(counts["eligible"]), int(counts["indexed"])
        return envelope(
            query_text=query_text, filter=query.model_dump(mode="json"),
            model=dict(zip(("model_name", "model_version", "dimensions"), model[:3])),
            search_status="matched" if candidates else "unindexed" if eligible and not indexed else "no_matches",
            recording_range=recording_range,
            coverage={"eligible_event_count": eligible, "indexed_event_count": indexed, "missing_event_count": eligible - indexed},
            candidates=candidates,
        )

    def _before_encode(self, principal, query):
        try:
            with self.backend.transaction(principal, "data:read") as (connection, _):
                query, recording = self.backend.normalize(connection, query, principal)
                model = self._model(recording)
                recording_range = self._range(query, recording)
                counts = {"eligible": 0, "indexed": 0} if recording_range["relation"] == "outside" else connection.execute(_COVERAGE, self._args(query, model)).fetchone()
                return query, model, recording_range, counts
        except psycopg.Error:
            raise ApiError(503, "semantic_storage_unavailable", "向量检索暂时不可用。") from None

    def _after_encode(self, principal, query_text, query, model, vector, top_k, min_score):
        try:
            with self.backend.transaction(principal, "data:read") as (connection, _):
                query, recording = self.backend.normalize(connection, query, principal)
                if self._model(recording) != model:
                    raise ApiError(503, "model_unavailable", "编码期间模型配置发生改变，请重试。")
                recording_range = self._range(query, recording)
                if recording_range["relation"] == "outside":
                    return self._response(query_text, query, recording_range, model, {"eligible": 0, "indexed": 0}, [])
                rows = connection.execute(
                    _SEARCH, self._args(query, model) + (json.dumps(vector, allow_nan=False), min_score, min_score, top_k),
                ).fetchall()
                if rows[0]["invalid_scores"]:
                    raise ApiError(503, "semantic_storage_unavailable", "向量分数无效，检索未完成。")
                candidates = []
                for row in rows:
                    if row["event_id"] is None:
                        continue
                    score = float(row["score"])
                    if not math.isfinite(score):
                        raise ApiError(503, "semantic_storage_unavailable", "向量分数无效，检索未完成。")
                    status = None
                    if row["evidence_id"] is not None:
                        status = "available" if row["media_status"] == "available" and self.backend.media_exists(row["storage_key"]) else "missing"
                    candidates.append({
                        key: row[key] for key in ("event_id", "evidence_id", "embedding_id", "source_text", "detection_source", "observation_status")
                    } | {"score": max(-1.0, min(1.0, score)), "media_status": status, "source_mode": recording["source_mode"]})
                return self._response(query_text, query, self._range(query, recording), model, rows[0], candidates)
        except psycopg.Error:
            raise ApiError(503, "semantic_storage_unavailable", "向量检索暂时不可用。") from None

    async def search(self, principal: Principal, query_text: str, filter: Query | UtcQuery,
                     top_k: int, min_score: float | None = None) -> dict:
        if not isinstance(query_text, str) or not query_text.strip() or len(query_text) > 2000:
            raise invalid("检索文本须为 1–2000 字符的非空描述。")
        try:
            query_text.encode("utf-8")
        except UnicodeError:
            raise invalid("检索文本须为有效 Unicode。") from None
        if type(top_k) is not int or not 1 <= top_k <= 50:
            raise invalid("top_k 须为 1–50 的整数。")
        if min_score is not None and (type(min_score) not in (int, float) or not math.isfinite(min_score) or not -1 <= min_score <= 1):
            raise invalid("min_score 须为 -1 到 1 的有限数值。")
        if not isinstance(filter, (Query, UtcQuery)):
            raise invalid("检索 filter 必须是有效的单录像范围。")
        query_text = query_text.strip()
        query, model, recording_range, counts = await run_in_threadpool(self._before_encode, principal, filter)
        if counts["indexed"] == 0:
            return self._response(query_text, query, recording_range, model, counts, [])
        try:
            # No database connection/transaction remains open during model I/O.
            vector = normalize_vector(await self.encoder.encode(query_text), model[2])
        except Exception:
            raise ApiError(503, "model_unavailable", "文本编码失败，未返回模拟或空结果。") from None
        return await run_in_threadpool(self._after_encode, principal, query_text, filter, model, vector, top_k, min_score)
