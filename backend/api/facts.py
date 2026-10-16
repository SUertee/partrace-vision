"""The twelve implemented v1 data operations; other draft routes stay absent."""

import json
import re

from fastapi import APIRouter, Request, Response
from pydantic import TypeAdapter, ValidationError
from starlette.datastructures import UploadFile

from backend.core.errors import ApiError, invalid
from backend.core.models import Id, Query, UtcQuery, RecordingJobInput, RecordingManifest, ReviewCreateRequest
from backend.services.facts import envelope, one, utc

router = APIRouter(prefix="/api/v1", tags=["data"])


def service(request):
    return request.app.state.backend


def credential(request):
    header = request.headers.get("authorization", "")
    parts = header.split()
    if len(parts) != 2 or parts[0].lower() != "bearer":
        raise ApiError(401, "unauthorized", "请提供有效 Bearer 凭据。")
    return parts[1]


def principal(request):
    return service(request).authenticate(credential(request))


def identifier(value):
    try:
        return TypeAdapter(Id).validate_python(value)
    except ValidationError:
        raise invalid("资源标识格式无效。") from None


def params(request, allowed):
    pairs = list(request.query_params.multi_items())
    if len({k for k, _ in pairs}) != len(pairs) or any(k not in allowed for k, _ in pairs):
        raise invalid("查询参数未知或重复。")
    return dict(pairs)


def query_input(values):
    values = dict(values)
    for key in ("start_offset_ms", "end_offset_ms"):
        if key in values:
            raw = values[key]
            if not isinstance(raw, str) or not re.fullmatch(r"[0-9]{1,19}", raw):
                raise invalid("视频偏移必须是非负整数。")
            values[key] = int(raw)
    model = UtcQuery if "start" in values or "end" in values else Query
    return model.model_validate(values)


def strict_json(data):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError("duplicate member")
            result[key] = value
        return result

    def invalid_number(value):
        raise ValueError("nonfinite number")

    try:
        return json.loads(data, object_pairs_hook=pairs, parse_constant=invalid_number)
    except (ValueError, UnicodeError):
        raise ApiError(400, "invalid_json", "请求须为合法 JSON，不能含重复字段。") from None


@router.get("/user")
def current_user(request: Request):
    p = principal(request)
    params(request, set())
    with service(request).transaction(p) as (_, user):
        return envelope(user=dict(user) | {"created_at": utc(user["created_at"])})


@router.get("/configs/{config_version}")
def get_config(config_version: str, request: Request):
    p = principal(request)
    params(request, set())
    s = service(request)
    with s.transaction(p, "data:read"):
        config = s.configs.load(identifier(config_version))
        config["cameras"] = [camera for camera in config["cameras"] if camera["camera_id"] in p.camera_ids]
        if not config["cameras"]:
            raise ApiError(403, "forbidden", "没有访问此版本镜头的权限。")
        return envelope(config=config)


@router.get("/recordings")
def list_recordings(request: Request):
    p = principal(request)
    values = params(request, {"camera_id"})
    camera = identifier(values["camera_id"]) if "camera_id" in values else None
    if camera is not None and camera not in p.camera_ids:
        raise ApiError(403, "forbidden", "没有访问此镜头的权限。")
    s = service(request)
    with s.transaction(p, "data:read") as (connection, _):
        rows = connection.execute(
            "SELECT * FROM recordings WHERE publication_status='published' AND camera_id=ANY(%s) AND (%s::text IS NULL OR camera_id=%s) "
            "AND (%s::text[] IS NULL OR recording_id=ANY(%s)) ORDER BY created_at DESC,recording_id DESC LIMIT %s",
            (list(p.camera_ids), camera, camera, list(p.recording_ids) if p.recording_ids is not None else None,
             list(p.recording_ids) if p.recording_ids is not None else None, s.settings.max_result_items + 1),
        ).fetchall()
        s.limit(rows)
        return envelope(recordings=[s.public_recording(row) for row in rows])


@router.post("/recordings")
async def import_recording(request: Request):
    from starlette.concurrency import run_in_threadpool
    p = await run_in_threadpool(principal, request)
    s = service(request)
    # Reject a readonly credential before decoding or staging a large body.
    def authorize():
        with s.transaction(p, "data:ingest", admin=True):
            pass
    await run_in_threadpool(authorize)
    params(request, set())
    media_type = request.headers.get("content-type", "").split(";")[0].strip().lower()
    uploads = {}
    if media_type == "application/json":
        data = await request.body()
        if len(data) > s.settings.max_manifest_bytes:
            raise ApiError(413, "payload_too_large", "清单超过部署限额。")
        manifest = RecordingManifest.model_validate(strict_json(data))
        if manifest.recording.source_mode != "mock":
            raise ApiError(415, "unsupported_media_type", "真实录像须使用 multipart 上传。")
    elif media_type == "multipart/form-data":
        async with request.form(max_files=s.settings.max_result_items + 2, max_fields=2, max_part_size=s.settings.max_manifest_bytes) as form:
            manifest_data = None
            mode = None
            total = 0
            for key, value in form.multi_items():
                if key == "manifest" and manifest_data is None:
                    if isinstance(value, UploadFile):
                        if value.content_type != "application/json":
                            raise invalid("manifest part 须为 application/json。")
                        manifest_data = await value.read(s.settings.max_manifest_bytes + 1)
                    else:
                        manifest_data = value.encode()
                    if len(manifest_data) > s.settings.max_manifest_bytes:
                        raise ApiError(413, "payload_too_large", "清单超过部署限额。")
                elif key == "mode" and mode is None and isinstance(value, str):
                    if value not in {"source", "result"}:
                        raise invalid("mode 须为 source 或 result。")
                    mode = value
                elif key == "files" and isinstance(value, UploadFile):
                    name = value.filename
                    if not name or name in uploads:
                        raise invalid("媒体文件名缺失或重复。")
                    data = await value.read(s.settings.max_upload_bytes - total + 1)
                    total += len(data)
                    if total > s.settings.max_upload_bytes:
                        raise ApiError(413, "payload_too_large", "媒体超过部署限额。")
                    uploads[name] = data
                else:
                    raise invalid("multipart 字段未知、重复或类型无效。")
            if manifest_data is None:
                raise invalid("缺少 manifest part。")
            if mode == "source":
                if set(uploads) != {"source.mp4"}:
                    raise invalid("原片录入须恰好一个 files/source.mp4。")
                value = RecordingJobInput.model_validate(strict_json(manifest_data))
                result, status = await run_in_threadpool(
                    request.app.state.recording_jobs.create, credential(request), value, uploads["source.mp4"],
                    include_recording=True,
                )
                return Response(content=json.dumps(result, ensure_ascii=False), status_code=status, media_type="application/json")
            manifest = RecordingManifest.model_validate(strict_json(manifest_data))
            if manifest.recording.source_mode == "mock":
                raise ApiError(415, "unsupported_media_type", "无媒体模拟包仅接受 application/json。")
    else:
        raise ApiError(415, "unsupported_media_type", "请使用 JSON 模拟包或 multipart 真实录像包。")
    result, status = await run_in_threadpool(s.import_recording, p, manifest, uploads)
    return Response(content=json.dumps(result, ensure_ascii=False), status_code=status, media_type="application/json")


@router.get("/recordings/{recording_id}")
def get_recording(recording_id: str, request: Request):
    p = principal(request)
    params(request, set())
    s = service(request)
    with s.transaction(p, "data:read") as (connection, _):
        return envelope(recording=s.public_recording(s.recording(connection, identifier(recording_id), p, require_published=False)))


@router.get("/events")
def list_events(request: Request):
    p = principal(request)
    values = params(request, {"recording_id", "camera_id", "zone_id", "start_offset_ms", "end_offset_ms", "start", "end"})
    return service(request).list_events(p, query_input(values))


@router.get("/events/{event_id}")
def get_event(event_id: str, request: Request):
    p = principal(request)
    params(request, set())
    s = service(request)
    with s.transaction(p, "data:read") as (connection, _):
        return s.event_bundle(connection, identifier(event_id), p)


@router.get("/evidence/{evidence_id}")
def get_evidence(evidence_id: str, request: Request):
    p = principal(request)
    params(request, set())
    s = service(request)
    with s.transaction(p, "data:read") as (connection, _):
        frame = one(connection, "SELECT * FROM evidence WHERE evidence_id=%s", (identifier(evidence_id),), "evidence_not_found")
        event = one(connection, "SELECT * FROM events WHERE event_id=%s", (frame["event_id"],))
        s.recording(connection, event["recording_id"], p)
        return envelope(evidence=s.evidence(frame))


@router.get("/evidence/{evidence_id}/media")
def get_media(evidence_id: str, request: Request):
    p = principal(request)
    params(request, set())
    s = service(request)
    with s.transaction(p, "data:read") as (connection, _):
        frame = one(connection, "SELECT * FROM evidence WHERE evidence_id=%s", (identifier(evidence_id),), "evidence_not_found")
        event = one(connection, "SELECT * FROM events WHERE event_id=%s", (frame["event_id"],))
        s.recording(connection, event["recording_id"], p)
        if frame["media_status"] != "available" or frame["storage_key"] is None:
            raise ApiError(404, "media_missing", "关键帧文件不可用，原始引用仍保留。")
        data = s.media.read(frame["storage_key"])
        content_type = "image/png" if data.startswith(b"\x89PNG\r\n\x1a\n") else "image/jpeg"
        return Response(data, media_type=content_type, headers={"Cache-Control": "private, no-store", "X-Content-Type-Options": "nosniff"})


@router.get("/review-records")
def list_reviews(request: Request):
    p = principal(request)
    values = params(request, {"recording_id"})
    recording_id = identifier(values["recording_id"]) if "recording_id" in values else None
    s = service(request)
    with s.transaction(p, "data:read") as (connection, _):
        if recording_id is not None:
            s.recording(connection, recording_id, p)
        rows = connection.execute(
            "SELECT v.* FROM review_records v JOIN events e USING(event_id) JOIN recordings r USING(recording_id) "
            "WHERE r.publication_status='published' AND r.camera_id=ANY(%s) AND (%s::text IS NULL OR r.recording_id=%s) "
            "AND (%s::text[] IS NULL OR r.recording_id=ANY(%s)) ORDER BY v.created_at DESC,v.review_id DESC LIMIT %s",
            (list(p.camera_ids), recording_id, recording_id,
             list(p.recording_ids) if p.recording_ids is not None else None,
             list(p.recording_ids) if p.recording_ids is not None else None, s.settings.max_result_items + 1),
        ).fetchall()
        s.limit(rows)
        return envelope(reviews=[s.public_review(row) for row in rows])


@router.post("/review-records")
async def save_review(request: Request):
    from starlette.concurrency import run_in_threadpool
    p = await run_in_threadpool(principal, request)
    params(request, set())
    if request.headers.get("content-type", "").split(";")[0].strip().lower() != "application/json":
        raise ApiError(415, "unsupported_media_type", "人工核查请求须为 application/json。")
    data = await request.body()
    if len(data) > service(request).settings.max_manifest_bytes:
        raise ApiError(413, "payload_too_large", "请求超过部署限额。")
    value = ReviewCreateRequest.model_validate(strict_json(data))
    result, status = await run_in_threadpool(service(request).save_review, p, value)
    return Response(content=json.dumps(result, ensure_ascii=False), status_code=status, media_type="application/json")


@router.get("/review-records/{review_id}")
def get_review(review_id: str, request: Request):
    p = principal(request)
    params(request, set())
    s = service(request)
    with s.transaction(p, "data:read") as (connection, _):
        row = one(connection, "SELECT * FROM review_records WHERE review_id=%s", (identifier(review_id),), "review_not_found")
        return s.review_bundle(connection, row, p)
