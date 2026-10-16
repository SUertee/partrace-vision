"""Raw SDK video intake and lease-fenced Vision callbacks, separate from fact imports."""

import json

from fastapi import APIRouter, Request, Response
from starlette.concurrency import run_in_threadpool
from starlette.datastructures import UploadFile

from backend.api.facts import identifier, params, strict_json
from backend.core.errors import ApiError, invalid
from backend.core.models import EmptyRequest, RecordingJobClaim, RecordingJobFailure, RecordingJobInput, RecordingManifest

router = APIRouter(prefix="/api/v1/recording-jobs", tags=["recording jobs"])


def token(request):
    parts = request.headers.get("authorization", "").split()
    if len(parts) != 2 or parts[0].lower() != "bearer":
        raise ApiError(401, "unauthorized", "请提供有效 Bearer 凭据。")
    return parts[1]


def lease(request):
    value = request.headers.get("x-lease-token", "")
    if not value or len(value) > 200 or any(character.isspace() for character in value):
        raise ApiError(409, "lease_conflict", "请提供当前任务租约。")
    return value


async def authorize(request, scope, *, ingest=False):
    jobs = request.app.state.recording_jobs
    credential = token(request)

    def check():
        principal = jobs.backend.authenticate(credential)
        with jobs.backend.transaction(principal, scope, admin=True):
            if ingest and "data:ingest" not in principal.scopes:
                raise ApiError(403, "forbidden", "发布结果还需要 data:ingest。")
    await run_in_threadpool(check)
    params(request, set())
    return jobs, credential


async def json_input(request, model):
    if request.headers.get("content-type", "").split(";")[0].strip().lower() != "application/json":
        raise ApiError(415, "unsupported_media_type", "请求须为 application/json。")
    body = await request.body()
    if len(body) > request.app.state.settings.max_manifest_bytes:
        raise ApiError(413, "payload_too_large", "请求超过部署限额。")
    return model.model_validate(strict_json(body))


async def multipart_input(request, *, raw):
    settings = request.app.state.settings
    if request.headers.get("content-type", "").split(";")[0].strip().lower() != "multipart/form-data":
        raise ApiError(415, "unsupported_media_type", "真实录像与分析包须使用 multipart。")
    manifest_data = None
    video = None
    uploads = {}
    total = 0
    async with request.form(max_files=2 if raw else settings.max_result_items + 1,
                            max_fields=1, max_part_size=settings.max_manifest_bytes) as form:
        for key, value in form.multi_items():
            if key == "manifest" and manifest_data is None:
                if isinstance(value, UploadFile):
                    if value.content_type != "application/json":
                        raise invalid("manifest part 须为 application/json。")
                    manifest_data = await value.read(settings.max_manifest_bytes + 1)
                else:
                    manifest_data = value.encode()
                if len(manifest_data) > settings.max_manifest_bytes:
                    raise ApiError(413, "payload_too_large", "清单超过部署限额。")
            elif raw and key == "video" and isinstance(value, UploadFile) and video is None:
                video = await value.read(settings.max_upload_bytes + 1)
                total += len(video)
            elif not raw and key == "files" and isinstance(value, UploadFile):
                if not value.filename or value.filename in uploads:
                    raise invalid("证据文件名缺失或重复。")
                data = await value.read(settings.max_upload_bytes - total + 1)
                total += len(data)
                uploads[value.filename] = data
            else:
                raise invalid("multipart 字段未知、重复或类型无效。")
            if total > settings.max_upload_bytes:
                raise ApiError(413, "payload_too_large", "媒体超过部署限额。")
    if manifest_data is None or (raw and video is None):
        raise invalid("缺少 manifest 或原片 video part。")
    model = RecordingJobInput if raw else RecordingManifest
    return model.model_validate(strict_json(manifest_data)), video if raw else uploads


@router.post("")
async def create(request: Request):
    jobs, credential = await authorize(request, "data:ingest")
    value, video = await multipart_input(request, raw=True)
    result, status = await run_in_threadpool(jobs.create, credential, value, video)
    return Response(json.dumps(result, ensure_ascii=False), status_code=status, media_type="application/json")


@router.post("/claim")
async def claim(request: Request):
    jobs, credential = await authorize(request, "vision:work")
    value = await json_input(request, RecordingJobClaim)
    return await run_in_threadpool(jobs.claim, credential, value)


@router.get("/{job_id}")
def get(job_id: str, request: Request):
    params(request, set())
    return request.app.state.recording_jobs.get(token(request), identifier(job_id))


@router.get("/{job_id}/media")
def media(job_id: str, request: Request):
    params(request, set())
    data = request.app.state.recording_jobs.media(token(request), identifier(job_id), lease(request))
    return Response(data, media_type="video/mp4", headers={"Cache-Control": "private, no-store", "X-Content-Type-Options": "nosniff"})


@router.post("/{job_id}/heartbeat")
async def heartbeat(job_id: str, request: Request):
    jobs, credential = await authorize(request, "vision:work")
    await json_input(request, EmptyRequest)
    return await run_in_threadpool(jobs.heartbeat, credential, identifier(job_id), lease(request))


@router.post("/{job_id}/fail")
async def fail(job_id: str, request: Request):
    jobs, credential = await authorize(request, "vision:work")
    value = await json_input(request, RecordingJobFailure)
    return await run_in_threadpool(jobs.fail, credential, identifier(job_id), lease(request), value)


@router.post("/{job_id}/complete")
async def complete(job_id: str, request: Request):
    jobs, credential = await authorize(request, "vision:work", ingest=True)
    current_lease = lease(request)
    manifest, uploads = await multipart_input(request, raw=False)
    result = await run_in_threadpool(jobs.complete, credential, identifier(job_id), current_lease, manifest, uploads)
    return Response(json.dumps(result, ensure_ascii=False), status_code=200 if result["replayed"] else 201, media_type="application/json")
