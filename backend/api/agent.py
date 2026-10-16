"""Eight authored Agent operations, mounted only after explicit runtime enable."""

import re
from typing import Annotated

from fastapi import APIRouter, Request
from pydantic import StringConstraints
from starlette.concurrency import run_in_threadpool
from starlette.responses import JSONResponse

from agent.models import AgentPlan, QueryScope
from backend.api.facts import identifier, params, principal, service, strict_json
from backend.core.errors import ApiError, invalid
from backend.core.models import Id, StrictModel
from backend.services.facts import envelope

router = APIRouter()


class ConversationCreate(StrictModel):
    conversation_id: Id


class MessageCreate(StrictModel):
    message_id: Id
    content: Annotated[str, StringConstraints(min_length=1, max_length=2000, pattern=r"\S")]


class PlanCreate(StrictModel):
    input_message_id: Id
    scope: QueryScope


class RunCreate(StrictModel):
    run_id: Id
    input_message_id: Id
    plan: AgentPlan


def runtime(request):
    return request.app.state.agent_runtime


def token(request):
    # principal() validates scheme, protected registry and current credential.
    principal(request)
    return request.headers["authorization"].split()[1]


async def body(request, schema):
    params(request, set())
    if request.headers.get("content-type", "").split(";")[0].strip().lower() != "application/json":
        raise ApiError(415, "unsupported_media_type", "请求须为 application/json。")
    raw = await request.body()
    if len(raw) > service(request).settings.max_manifest_bytes:
        raise ApiError(413, "payload_too_large", "请求超过部署限额。")
    return schema.model_validate(strict_json(raw))


def page(request):
    values = params(request, {"limit", "cursor"})
    raw = values.get("limit", "20")
    if not re.fullmatch(r"[0-9]{1,3}", raw) or not 1 <= int(raw) <= 100:
        raise invalid("limit 须为 1 到 100 的整数。")
    return int(raw), values.get("cursor")


@router.post("/conversations")
async def create_conversation(request: Request):
    p = await run_in_threadpool(principal, request)
    value = await body(request, ConversationCreate)
    result, status = await run_in_threadpool(runtime(request).history.create_conversation, p, value.conversation_id)
    return JSONResponse(result, status_code=status)


@router.get("/conversations")
def list_conversations(request: Request):
    return runtime(request).history.list_conversations(principal(request), *page(request))


@router.post("/conversations/{conversation_id}/messages")
async def create_message(conversation_id: str, request: Request):
    p = await run_in_threadpool(principal, request)
    value = await body(request, MessageCreate)
    result, status = await run_in_threadpool(runtime(request).history.create_message, p,
                                            identifier(conversation_id), value.message_id, value.content)
    return JSONResponse(result, status_code=status)


@router.get("/conversations/{conversation_id}/messages")
def list_messages(conversation_id: str, request: Request):
    return runtime(request).history.list_messages(principal(request), identifier(conversation_id), *page(request))


@router.post("/plans")
async def plan(request: Request):
    credential = await run_in_threadpool(token, request)
    value = await body(request, PlanCreate)
    result = await runtime(request).plan(credential, value.input_message_id, value.scope.model_dump(mode="json"))
    # Do not expose an unvalidated provider-generated public plan.
    return envelope(**AgentPlan.model_validate(result).model_dump(mode="json"))


@router.post("/runs")
async def start_run(request: Request):
    credential = await run_in_threadpool(token, request)
    value = await body(request, RunCreate)
    result, status = await runtime(request).start(credential, value.run_id, value.input_message_id,
                                                 value.plan.model_dump(mode="json"))
    return JSONResponse(result, status_code=status)


@router.get("/runs/{run_id}")
def get_run(run_id: str, request: Request):
    params(request, set())
    return runtime(request).history.get_run(principal(request), identifier(run_id))


@router.post("/runs/{run_id}/stop")
async def stop(run_id: str, request: Request):
    credential = await run_in_threadpool(token, request)
    await body(request, StrictModel)
    return await runtime(request).stop(credential, identifier(run_id))
