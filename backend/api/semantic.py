"""The two existing v1 vector operations, with no client-selected encoder."""

from typing import Annotated

from fastapi import APIRouter, Request
from pydantic import Field, StringConstraints
from starlette.concurrency import run_in_threadpool
from starlette.responses import JSONResponse

from backend.api.facts import params, principal, service, strict_json
from backend.core.errors import ApiError
from backend.core.errors import invalid
from backend.core.models import Query, StrictModel, UtcQuery

router = APIRouter(prefix="/api/v1", tags=["semantic"])


class SearchRequest(StrictModel):
    query_text: Annotated[str, StringConstraints(min_length=1, max_length=2000, pattern=r"\S")]
    filter: Query | UtcQuery
    top_k: Annotated[int, Field(ge=1, le=50)]
    min_score: Annotated[float, Field(ge=-1, le=1, allow_inf_nan=False)] | None = None


async def payload(request):
    params(request, set())
    if request.headers.get("content-type", "").split(";")[0].strip().lower() != "application/json":
        raise ApiError(415, "unsupported_media_type", "向量请求须为 application/json。")
    raw = await request.body()
    if len(raw) > service(request).settings.max_manifest_bytes:
        raise ApiError(413, "payload_too_large", "向量请求超过部署限额。")
    return strict_json(raw)


@router.post("/event-embeddings")
async def insert_embedding(request: Request):
    p = await run_in_threadpool(principal, request)
    value = await payload(request)
    result, status = await run_in_threadpool(request.app.state.semantic.insert_embedding, p, value)
    return JSONResponse(result, status_code=status)


@router.post("/events/search")
async def search(request: Request):
    p = await run_in_threadpool(principal, request)
    raw = await payload(request)
    if isinstance(raw, dict) and "min_score" in raw and raw["min_score"] is None:
        raise invalid("min_score 可省略，但不能为 null。")
    value = SearchRequest.model_validate(raw)
    result = await request.app.state.semantic.search(p, value.query_text, value.filter, value.top_k, value.min_score)

    def refresh():
        fresh = principal(request)
        if fresh.user_id != p.user_id:
            raise ApiError(403, "forbidden", "检索期间身份已变更。")
        with service(request).transaction(fresh, "data:read") as (connection, _):
            service(request).normalize(connection, value.filter, fresh)

    await run_in_threadpool(refresh)
    return result
