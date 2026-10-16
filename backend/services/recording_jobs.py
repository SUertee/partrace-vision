"""Durable SDK raw-video intake and fenced Vision work; PostgreSQL is authoritative."""

import hashlib
import hmac
import secrets

from backend.core.errors import ApiError, invalid
from backend.services.facts import canonical, envelope, now, one, utc


class RecordingJobs:
    def __init__(self, backend):
        self.backend = backend

    @staticmethod
    def public(row):
        fields = ("job_id", "camera_id", "config_version", "sdk_event_id", "event_type", "duration_ms",
                  "sdk_timestamp_ms", "status", "attempts", "error_code", "event_count", "evidence_count")
        value = {key: row[key] for key in fields}
        value["recording_id"] = row["job_id"]
        for key in ("created_at", "updated_at", "started_at", "finished_at", "lease_expires_at"):
            value[key] = utc(row[key]) if row[key] is not None else None
        return value

    def _principal(self, token):
        return self.backend.authenticate(token)

    def _allowed(self, principal, row):
        if not self.backend.allowed(principal, dict(camera_id=row["camera_id"], recording_id=row["job_id"])):
            raise ApiError(403, "forbidden", "没有访问此镜头／录像任务的权限。")

    def _job(self, connection, principal, job_id, *, lock=False):
        row = one(connection, "SELECT * FROM recording_jobs WHERE job_id=%s" + (" FOR UPDATE" if lock else ""),
                  (job_id,), "recording_job_not_found")
        self._allowed(principal, row)
        return row

    def _lease(self, connection, principal, row, lease_token, *, terminal=None):
        digest = hashlib.sha256(lease_token.encode()).hexdigest()
        if (row["worker_user_id"] != principal.user_id or row["lease_token_sha256"] is None
                or not hmac.compare_digest(row["lease_token_sha256"], digest)):
            raise ApiError(409, "lease_conflict", "任务租约已变化，请重新领取。")
        if terminal is not None and row["status"] == terminal:
            return
        valid = connection.execute("SELECT %s::timestamptz > clock_timestamp() AS valid", (row["lease_expires_at"],)).fetchone()["valid"]
        if row["status"] != "processing" or not valid:
            raise ApiError(409, "lease_conflict", "任务租约已过期或任务已终结。")

    def create(self, token, value, video, *, include_recording=False):
        s = self.backend
        payload = value.model_dump(mode="json")
        p = self._principal(token)
        with s.transaction(p, "data:ingest", admin=True):
            self._allowed(p, payload)
            s.camera(payload)
        # Raw SDK timestamp is intentionally not a recording UTC start.
        raw_manifest = dict(recording=dict(recording_id=value.job_id, camera_id=value.camera_id,
                                           config_version=value.config_version, source_mode="sdk", started_at=None,
                                           duration_ms=value.duration_ms, file_name="source.mp4"), events=[], evidence=[])
        prepared = s.media.prepare(raw_manifest, {"source.mp4": video})
        try:
            digest = hashlib.sha256(canonical(dict(manifest=payload, video_sha256=prepared.digests["source.mp4"]))).hexdigest()
            p = self._principal(token)  # Recheck revocation after potentially expensive media decoding.
            with s.transaction(p, "data:ingest", admin=True) as (connection, _):
                self._allowed(p, payload)
                s.camera(payload)
                connection.execute("SELECT pg_advisory_xact_lock(hashtextextended(%s,0))", ("recording:" + value.job_id,))
                old = connection.execute("SELECT * FROM recording_jobs WHERE job_id=%s FOR UPDATE", (value.job_id,)).fetchone()
                if old:
                    self._allowed(p, old)
                    if old["request_sha256"] != digest:
                        raise ApiError(409, "idempotency_conflict", "此任务 ID 已接收不同内容。")
                    result = envelope(job=self.public(old), replayed=True)
                    replayed = True
                else:
                    if connection.execute("SELECT 1 FROM recordings WHERE recording_id=%s", (value.job_id,)).fetchone():
                        raise ApiError(409, "idempotency_conflict", "此录像 ID 已完整发布，不能重新接收原片。")
                    row = connection.execute(
                        "INSERT INTO recording_jobs(job_id,camera_id,config_version,sdk_event_id,event_type,duration_ms,sdk_timestamp_ms,created_by,video_sha256,request_sha256,storage_key,created_at,updated_at) "
                        "VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s) RETURNING *",
                        tuple(payload[key] for key in ("job_id", "camera_id", "config_version", "sdk_event_id", "event_type", "duration_ms", "sdk_timestamp_ms")) +
                        (p.user_id, prepared.digests["source.mp4"], digest, prepared.recording_storage_key, now(), now()),
                    ).fetchone()
                    connection.execute(
                        "INSERT INTO recordings(recording_id,camera_id,config_version,source_mode,started_at,duration_ms,manifest_sha256,storage_key,media_status,created_at,publication_status) "
                        "VALUES(%s,%s,%s,'sdk',NULL,%s,NULL,%s,'available',%s,'pending')",
                        (value.job_id, value.camera_id, value.config_version, value.duration_ms, row["storage_key"], row["created_at"]),
                    )
                    prepared.commit()
                    result = envelope(job=self.public(row), replayed=False)
                    replayed = False
                if include_recording:
                    result["recording"] = s.public_recording(s.recording(connection, value.job_id, p, require_published=False))
            if replayed:
                prepared.rollback()
            return result, 200 if replayed else 202
        except ApiError as error:
            if error.code == "database_unavailable" and prepared.published:
                raise
            prepared.rollback()
            raise
        except BaseException:
            prepared.rollback()
            raise

    def get(self, token, job_id):
        p = self._principal(token)
        with self.backend.transaction(p, "data:read") as (connection, _):
            return envelope(job=self.public(self._job(connection, p, job_id)))

    def claim(self, token, value):
        s = self.backend
        p = self._principal(token)
        with s.transaction(p, "vision:work", admin=True) as (connection, _):
            row = connection.execute(
                "SELECT * FROM recording_jobs WHERE (status='queued' OR (status='processing' AND lease_expires_at<=clock_timestamp())) "
                "AND camera_id=ANY(%s) AND (%s::text[] IS NULL OR job_id=ANY(%s)) "
                "ORDER BY created_at,job_id FOR UPDATE SKIP LOCKED LIMIT 1",
                (list(p.camera_ids), list(p.recording_ids) if p.recording_ids is not None else None,
                 list(p.recording_ids) if p.recording_ids is not None else None),
            ).fetchone()
            if row is None:
                return envelope(job=None)
            lease_token = secrets.token_urlsafe(32)
            row = connection.execute(
                "UPDATE recording_jobs SET status='processing',attempts=attempts+1,worker_id=%s,worker_user_id=%s,lease_token_sha256=%s,"
                "lease_expires_at=clock_timestamp()+(%s * interval '1 second'),started_at=COALESCE(started_at,clock_timestamp()),updated_at=clock_timestamp() "
                "WHERE job_id=%s RETURNING *",
                (value.worker_id, p.user_id, hashlib.sha256(lease_token.encode()).hexdigest(), s.settings.recording_job_lease_seconds, row["job_id"]),
            ).fetchone()
            return envelope(job=self.public(row), lease_token=lease_token)

    def heartbeat(self, token, job_id, lease_token):
        p = self._principal(token)
        with self.backend.transaction(p, "vision:work", admin=True) as (connection, _):
            row = self._job(connection, p, job_id, lock=True)
            self._lease(connection, p, row, lease_token)
            row = connection.execute(
                "UPDATE recording_jobs SET lease_expires_at=clock_timestamp()+(%s * interval '1 second'),updated_at=clock_timestamp() WHERE job_id=%s RETURNING *",
                (self.backend.settings.recording_job_lease_seconds, job_id),
            ).fetchone()
            return envelope(job=self.public(row))

    def media(self, token, job_id, lease_token):
        p = self._principal(token)
        with self.backend.transaction(p, "vision:work", admin=True) as (connection, _):
            row = self._job(connection, p, job_id)
            self._lease(connection, p, row, lease_token)
            # The path is generated and resolved by MediaStore, never by the client.
            path = self.backend.media.resolve(row["storage_key"])
            try:
                data = path.read_bytes()
            except OSError:
                raise ApiError(404, "media_missing", "原始录像暂不可用。") from None
            if not data or hashlib.sha256(data).hexdigest() != row["video_sha256"]:
                raise ApiError(404, "media_missing", "原始录像暂不可用。")
            return data

    def fail(self, token, job_id, lease_token, value):
        p = self._principal(token)
        with self.backend.transaction(p, "vision:work", admin=True) as (connection, _):
            row = self._job(connection, p, job_id, lock=True)
            self._lease(connection, p, row, lease_token, terminal="failed")
            if row["status"] == "failed":
                if row["error_code"] != value.code:
                    raise ApiError(409, "idempotency_conflict", "任务已保存不同失败原因。")
                return envelope(job=self.public(row))
            row = connection.execute(
                "UPDATE recording_jobs SET status='failed',error_code=%s,lease_expires_at=NULL,finished_at=clock_timestamp(),updated_at=clock_timestamp() WHERE job_id=%s RETURNING *",
                (value.code, job_id),
            ).fetchone()
            return envelope(job=self.public(row))

    def complete(self, token, job_id, lease_token, manifest, uploads):
        s = self.backend
        p = self._principal(token)
        payload = manifest.model_dump(mode="json")
        recording = payload["recording"]
        with s.transaction(p, "vision:work", admin=True) as (connection, _):
            if "data:ingest" not in p.scopes:
                raise ApiError(403, "forbidden", "发布结果还需要 data:ingest。")
            row = self._job(connection, p, job_id)
            self._lease(connection, p, row, lease_token, terminal="completed")
            if (recording["recording_id"] != job_id or recording["source_mode"] != "sdk" or recording["started_at"] is not None
                    or recording["file_name"] != "source.mp4" or any(recording[key] != row[key] for key in ("camera_id", "config_version", "duration_ms"))):
                raise invalid("分析结果与原片任务不一致，SDK 时间不能充当校准 UTC。")
            camera = s.camera(recording)
            if any(event["zone_id"] not in {zone["zone_id"] for zone in camera["zones"]} for event in payload["events"]):
                raise invalid("分析事件引用了此配置不存在的区域。")
        s.limit(payload["events"])
        s.limit(payload["evidence"])
        prepared = s.media.prepare(payload, uploads, source_media=(row["storage_key"], row["video_sha256"]))
        try:
            digest = hashlib.sha256(canonical(dict(manifest=payload, files=prepared.digests))).hexdigest()
            p = self._principal(token)  # Never publish under an authorization captured before decoding.
            with s.transaction(p, "vision:work", admin=True) as (connection, _):
                if "data:ingest" not in p.scopes:
                    raise ApiError(403, "forbidden", "发布结果还需要 data:ingest。")
                connection.execute("SELECT pg_advisory_xact_lock(hashtextextended(%s,0))", ("recording:" + job_id,))
                row = self._job(connection, p, job_id, lock=True)
                self._lease(connection, p, row, lease_token, terminal="completed")
                s.camera(recording)
                if row["status"] == "completed" and row["result_sha256"] != digest:
                    raise ApiError(409, "idempotency_conflict", "任务已发布不同结果。")
                result, replayed = s.publish_recording(connection, p, payload, prepared, digest, job_id=job_id)
                if not replayed:
                    self._lease(connection, p, row, lease_token)
                    row = connection.execute(
                        "UPDATE recording_jobs SET status='completed',result_sha256=%s,event_count=%s,evidence_count=%s,lease_expires_at=NULL,finished_at=clock_timestamp(),updated_at=clock_timestamp() WHERE job_id=%s RETURNING *",
                        (digest, len(payload["events"]), len(payload["evidence"]), job_id),
                    ).fetchone()
                result["job"] = self.public(row)
            if replayed:
                prepared.rollback()
            return result
        except ApiError as error:
            if error.code == "database_unavailable" and prepared.published:
                raise
            prepared.rollback()
            raise
        except BaseException:
            prepared.rollback()
            raise
