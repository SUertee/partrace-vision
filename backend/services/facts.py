"""PostgreSQL facts and human reviews. No model, in-memory DB or AI fallback."""

import hashlib
import json
import threading
from contextlib import contextmanager
from datetime import datetime, timezone, timedelta
from pathlib import Path

import psycopg
from psycopg.rows import dict_row
from psycopg.types.json import Jsonb
from psycopg_pool import ConnectionPool, PoolTimeout

from backend.core.auth import AuthRegistry, Principal
from backend.core.settings import Settings
from backend.core.configuration import ConfigRegistry
from backend.core.errors import API_VERSION, ApiError, invalid
from backend.storage.media import MediaStore
from backend.core.models import Query, UtcQuery


def utc(value: datetime) -> str:
    return value.astimezone(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def now() -> datetime:
    value = datetime.now(timezone.utc)
    return value.replace(microsecond=(value.microsecond // 1000) * 1000)


def envelope(**values):
    return {"api_version": API_VERSION, **values}


def canonical(value) -> bytes:
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode()


def one(connection, sql, args, code="not_found"):
    row = connection.execute(sql, args).fetchone()
    if row is None:
        raise ApiError(404, code, "资源不存在。")
    return row


class BackendService:
    def __init__(self, settings: Settings):
        self.settings = settings
        self._pool = None
        self._configs = None
        self._lock = threading.Lock()
        self.media = MediaStore(
            settings.media_root, ffprobe_path=settings.ffprobe_path,
            ffmpeg_path=settings.ffmpeg_path, max_bytes=settings.max_upload_bytes,
        )

    def close(self):
        if self._pool is not None:
            self._pool.close()

    def authenticate(self, token: str) -> Principal:
        if self.settings.environment != "development":
            raise ApiError(503, "auth_unavailable", "正式认证尚未配置；本地凭据只用于 development。")
        return AuthRegistry(self.settings.auth_file).authenticate(token)

    @property
    def configs(self):
        with self._lock:
            if self._configs is None:
                try:
                    path = self.settings.config_pins_file
                    if path.is_symlink() or path.stat().st_size > 2 * 1024 * 1024:
                        raise ValueError("invalid pins")
                    def unique_pairs(items):
                        value = {}
                        for key, entry in items:
                            if key in value:
                                raise ValueError("duplicate config pin")
                            value[key] = entry
                        return value
                    pins = json.loads(path.read_text(), object_pairs_hook=unique_pairs)
                    if not isinstance(pins, dict) or not pins:
                        raise ValueError("config pins must be a nonempty object")
                except (OSError, ValueError):
                    raise ApiError(503, "config_unavailable", "配置版本签名不可用。") from None
                self._configs = ConfigRegistry(self.settings.configs_dir, pins=pins)
            return self._configs

    @property
    def pool(self):
        with self._lock:
            if self._pool is None:
                self._pool = ConnectionPool(
                    self.settings.database_url.get_secret_value(), min_size=0,
                    max_size=self.settings.database_pool_size, open=False,
                    timeout=self.settings.database_timeout_seconds,
                    kwargs={"row_factory": dict_row, "connect_timeout": self.settings.database_timeout_seconds,
                            "options": f"-c timezone=UTC -c statement_timeout=15000 -c lock_timeout=5000 -c search_path={self.settings.database_schema},public"},
                )
                self._pool.open()
            return self._pool

    @contextmanager
    def transaction(self, principal: Principal, scope: str | None = None, admin=False):
        if scope is not None and scope not in principal.scopes:
            raise ApiError(403, "forbidden", "当前凭据没有此操作权限。")
        try:
            with self.pool.connection() as connection:
                with connection.transaction():
                    connection.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED")
                    user = connection.execute("SELECT * FROM users WHERE user_id=%s FOR SHARE", (principal.user_id,)).fetchone()
                    if user is None:
                        raise ApiError(401, "unauthorized", "身份尚未映射到应用用户。")
                    if user["status"] != "active" or (admin and user["role"] != "admin"):
                        raise ApiError(403, "forbidden", "用户已禁用或没有此操作权限。")
                    yield connection, user
        except (psycopg.OperationalError, psycopg.InterfaceError, PoolTimeout):
            raise ApiError(503, "database_unavailable", "数据库暂时不可用，请稍后重试。") from None
        except (psycopg.errors.UniqueViolation, psycopg.errors.SerializationFailure):
            raise ApiError(409, "idempotency_conflict", "标识或并发请求发生冲突，请使用原请求重试。") from None
        except psycopg.IntegrityError:
            raise invalid("数据约束或关联校验失败。") from None

    @staticmethod
    def allowed(principal, recording):
        return recording["camera_id"] in principal.camera_ids and (
            principal.recording_ids is None or recording["recording_id"] in principal.recording_ids
        )

    def recording(self, connection, recording_id, principal):
        row = one(connection, "SELECT * FROM recordings WHERE recording_id=%s", (recording_id,), "recording_not_found")
        if not self.allowed(principal, row):
            raise ApiError(403, "forbidden", "没有访问此录像的权限。")
        return row

    def camera(self, recording):
        config = self.configs.load(recording["config_version"])
        camera = next((c for c in config["cameras"] if c["camera_id"] == recording["camera_id"]), None)
        if camera is None:
            raise invalid("录像的镜头与配置不一致。")
        return camera

    def public_recording(self, row):
        result = {k: v for k, v in row.items() if k != "storage_key"}
        result["started_at"] = utc(row["started_at"]) if row["started_at"] is not None else None
        result["created_at"] = utc(row["created_at"])
        if result["media_status"] == "available" and not self.media_exists(row["storage_key"]):
            result["media_status"] = "missing"
        return result

    def media_exists(self, key):
        if key is None:
            return False
        try:
            return self.media.resolve(key).is_file()
        except (ApiError, OSError):
            return False

    def evidence(self, row):
        available = row["media_status"] == "available" and self.media_exists(row["storage_key"])
        return {k: row[k] for k in ("evidence_id", "event_id", "offset_ms", "bbox_json")} | {
            "media_status": "available" if available else "missing",
            "media_url": f'/api/v1/evidence/{row["evidence_id"]}/media' if available else None,
        }

    @staticmethod
    def event(connection, row):
        ids = connection.execute("SELECT evidence_id FROM evidence WHERE event_id=%s ORDER BY offset_ms,evidence_id", (row["event_id"],)).fetchall()
        return dict(row) | {"evidence_ids": [item["evidence_id"] for item in ids]}

    def event_bundle(self, connection, event_id, principal):
        row = one(connection, "SELECT * FROM events WHERE event_id=%s", (event_id,), "event_not_found")
        self.recording(connection, row["recording_id"], principal)
        frames = connection.execute("SELECT * FROM evidence WHERE event_id=%s ORDER BY offset_ms,evidence_id", (event_id,)).fetchall()
        points = [dict(evidence_id=f["evidence_id"], offset_ms=f["offset_ms"], bbox=f["bbox_json"]) for f in frames
                  if row["detection_source"] != "mock" and f["bbox_json"] is not None]
        return envelope(event=self.event(connection, row), evidence=[self.evidence(f) for f in frames], observed_points=points)

    def normalize(self, connection, value: Query | UtcQuery, principal):
        recording = self.recording(connection, value.recording_id, principal)
        if value.camera_id != recording["camera_id"] or value.zone_id not in {z["zone_id"] for z in self.camera(recording)["zones"]}:
            raise invalid("镜头、区域与录像配置不一致。")
        if isinstance(value, UtcQuery):
            if recording["started_at"] is None:
                raise ApiError(422, "clock_unverified", "录像尚无已验证的 UTC 起点，请使用视频偏移。")
            offsets = [(datetime.fromisoformat(t.replace("Z", "+00:00")) - recording["started_at"]) // timedelta(milliseconds=1) for t in (value.start, value.end)]
            if offsets[0] < 0 or offsets[1] <= offsets[0]:
                raise invalid("UTC 查询无法映射到有效视频偏移。")
            value = Query(recording_id=value.recording_id, camera_id=value.camera_id, zone_id=value.zone_id,
                          start_offset_ms=offsets[0], end_offset_ms=offsets[1])
        return value, recording

    @staticmethod
    def hits(event, query):
        start, end = event["start_offset_ms"], event["end_offset_ms"]
        return (query.start_offset_ms <= start < query.end_offset_ms) if start == end else (start < query.end_offset_ms and end > query.start_offset_ms)

    def list_events(self, principal, query):
        with self.transaction(principal, "data:read") as (connection, _):
            query, recording = self.normalize(connection, query, principal)
            start, end = max(0, query.start_offset_ms), min(recording["duration_ms"], query.end_offset_ms)
            relation = "outside" if end <= start else "full" if query.end_offset_ms <= recording["duration_ms"] else "partial"
            rows = []
            if relation != "outside":
                rows = connection.execute(
                    "SELECT * FROM events WHERE recording_id=%s AND zone_id=%s AND start_offset_ms < %s "
                    "AND (end_offset_ms > %s OR (start_offset_ms=end_offset_ms AND start_offset_ms >= %s)) "
                    "ORDER BY start_offset_ms,event_id LIMIT %s",
                    (query.recording_id, query.zone_id, query.end_offset_ms, query.start_offset_ms, query.start_offset_ms, self.settings.max_result_items + 1),
                ).fetchall()
                self.limit(rows)
            return envelope(query=query.model_dump(mode="json"), query_status="outside_recording" if relation == "outside" else "matched" if rows else "no_detections",
                            recording_range={"relation": relation, "covered_start_offset_ms": None if relation == "outside" else start,
                                             "covered_end_offset_ms": None if relation == "outside" else end},
                            events=[self.event(connection, row) for row in rows])

    def limit(self, rows):
        if len(rows) > self.settings.max_result_items:
            raise invalid("结果超过部署限额，请缩小查询范围。")

    def verify_clock(self, recording, digests):
        if recording["started_at"] is None:
            return
        try:
            if self.settings.clock_file is None:
                raise ValueError("missing calibration")
            path = self.settings.clock_file
            if path.is_symlink() or path.stat().st_size > 2 * 1024 * 1024:
                raise ValueError("invalid calibration")
            entry = json.loads(path.read_text())["recordings"][recording["recording_id"]]
            expected = {key: recording[key] for key in ("camera_id", "config_version", "duration_ms", "started_at")}
            expected["video_sha256"] = digests[recording["file_name"]]
            if entry != expected:
                raise ValueError("calibration mismatch")
        except (OSError, ValueError, KeyError, TypeError):
            raise ApiError(422, "clock_unverified", "此视频内容没有匹配的服务端时间校准记录。") from None

    def import_recording(self, principal, manifest, uploads):
        payload = manifest.model_dump(mode="json")
        recording = payload["recording"]
        with self.transaction(principal, "data:ingest", admin=True):
            if not self.allowed(principal, recording):
                raise ApiError(403, "forbidden", "没有导入此镜头／录像的权限。")
            camera = self.camera(recording)
            if any(e["zone_id"] not in {z["zone_id"] for z in camera["zones"]} for e in payload["events"]):
                raise invalid("事件引用了此配置不存在的区域。")
        self.limit(payload["events"])
        self.limit(payload["evidence"])
        prepared = self.media.prepare(payload, uploads)
        try:
            self.verify_clock(recording, prepared.digests)
            digest = hashlib.sha256(canonical({"manifest": payload, "files": prepared.digests})).hexdigest()
            replayed = False
            with self.transaction(principal, "data:ingest", admin=True) as (connection, _):
                connection.execute("SELECT pg_advisory_xact_lock(hashtextextended(%s,0))", ("recording:" + recording["recording_id"],))
                old = connection.execute("SELECT * FROM recordings WHERE recording_id=%s", (recording["recording_id"],)).fetchone()
                if old:
                    if old["manifest_sha256"] != digest:
                        raise ApiError(409, "idempotency_conflict", "此录像 ID 已发布不同内容，请使用新 ID。")
                    result = envelope(recording=self.public_recording(old),
                                      event_count=connection.execute("SELECT count(*) n FROM events WHERE recording_id=%s", (recording["recording_id"],)).fetchone()["n"],
                                      evidence_count=connection.execute("SELECT count(*) n FROM evidence f JOIN events e USING(event_id) WHERE e.recording_id=%s", (recording["recording_id"],)).fetchone()["n"], replayed=True)
                    replayed = True
                else:
                    row = connection.execute(
                        "INSERT INTO recordings(recording_id,camera_id,config_version,source_mode,started_at,duration_ms,manifest_sha256,storage_key,media_status,created_at) "
                        "VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s) RETURNING *",
                        tuple(recording[k] for k in ("recording_id", "camera_id", "config_version", "source_mode", "started_at", "duration_ms")) +
                        (digest, prepared.recording_storage_key, "available" if prepared.recording_storage_key else "missing", now()),
                    ).fetchone()
                    for event in payload["events"]:
                        connection.execute(
                            "INSERT INTO events(event_id,recording_id,zone_id,track_key,event_type,observation_status,start_offset_ms,end_offset_ms,detection_source,summary) VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)",
                            (event["event_id"], recording["recording_id"]) + tuple(event[k] for k in ("zone_id", "track_key", "event_type", "observation_status", "start_offset_ms", "end_offset_ms", "detection_source", "summary")),
                        )
                    for frame in payload["evidence"]:
                        key = prepared.evidence_keys[frame["evidence_id"]]
                        connection.execute("INSERT INTO evidence(evidence_id,event_id,offset_ms,bbox_json,storage_key,media_status) VALUES(%s,%s,%s,%s,%s,%s)",
                                           (frame["evidence_id"], frame["event_id"], frame["offset_ms"], Jsonb(frame["bbox_json"]) if frame["bbox_json"] is not None else None, key, "available" if key else "missing"))
                    prepared.commit()
                    result = envelope(recording=self.public_recording(row), event_count=len(payload["events"]), evidence_count=len(payload["evidence"]), replayed=False)
            if replayed:
                prepared.rollback()
            return result, 200 if replayed else 201
        except ApiError as error:
            # A disconnected COMMIT has an unknown outcome: preserve published bytes,
            # so a possibly committed fact never points at files we just removed.
            if error.code == "database_unavailable":
                if getattr(prepared, "published", False):
                    raise
            prepared.rollback()
            raise
        except Exception:
            prepared.rollback()
            raise

    def review_bundle(self, connection, row, principal):
        event = self.event_bundle(connection, row["event_id"], principal)
        return envelope(review=self.public_review(row), event=event["event"], evidence=event["evidence"])

    @staticmethod
    def public_review(row):
        return {"review_id": row["review_id"], "event_id": row["event_id"], "query": row["query_json"],
                "decision": row["decision"], "note": row["note"], "reviewer_id": row["reviewer_id"], "created_at": utc(row["created_at"])}

    def save_review(self, principal, value):
        with self.transaction(principal, "reviews:write", admin=True) as (connection, _):
            query, recording = self.normalize(connection, value.query, principal)
            if query.end_offset_ms > recording["duration_ms"]:
                raise invalid("保存窗口必须完全落在视频范围内。")
            event = one(connection, "SELECT * FROM events WHERE event_id=%s", (value.event_id,), "event_not_found")
            if event["recording_id"] != query.recording_id or event["zone_id"] != query.zone_id or not self.hits(event, query):
                raise invalid("事件不属于本次核查范围。")
            connection.execute("SELECT pg_advisory_xact_lock(hashtextextended(%s,0))", ("review:" + value.review_id,))
            old = connection.execute("SELECT * FROM review_records WHERE review_id=%s", (value.review_id,)).fetchone()
            expected = dict(review_id=value.review_id, event_id=value.event_id, query_json=query.model_dump(mode="json"), decision=value.decision, note=value.note, reviewer_id=principal.user_id)
            if old:
                if old["reviewer_id"] != principal.user_id:
                    raise ApiError(403, "forbidden", "不能重放其他用户的核查请求。")
                if any(old[k] != v for k, v in expected.items()):
                    raise ApiError(409, "idempotency_conflict", "此核查 ID 已保存不同内容。")
                return self.review_bundle(connection, old, principal), 200
            row = connection.execute("INSERT INTO review_records(review_id,event_id,query_json,decision,note,reviewer_id,created_at) VALUES(%s,%s,%s,%s,%s,%s,%s) RETURNING *",
                                     (value.review_id, value.event_id, Jsonb(expected["query_json"]), value.decision, value.note, principal.user_id, now())).fetchone()
            return self.review_bundle(connection, row, principal), 201
