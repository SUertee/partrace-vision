"""Eight authored Agent operations, mounted only after explicit runtime enable."""

import asyncio
import re
from typing import Annotated

from fastapi import APIRouter, Request
from pydantic import StringConstraints
from starlette.concurrency import run_in_threadpool
from starlette.responses import JSONResponse, StreamingResponse

from agent.models import AgentPlan, QueryScope
from backend.ai import push
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


@router.get("/runs/{run_id}/events")
async def run_events(run_id: str, request: Request):
    """SSE: snapshot first, then one terminal event; Last-Event-ID aligns replays."""
    params(request, set())
    p = await run_in_threadpool(principal, request)
    agent_runtime = runtime(request)
    run_id = identifier(run_id)
    # Subscribe before the snapshot read: a terminal commit can never slip
    # between the read and the subscription, because publish follows commit.
    queue = agent_runtime.push.subscribe(run_id)
    try:
        snapshot = await run_in_threadpool(agent_runtime.history.get_run, p, run_id)
    except BaseException:
        agent_runtime.push.unsubscribe(run_id, queue)
        raise
    last_event_id = request.headers.get("last-event-id")

    async def stream():
        try:
            if snapshot["status"] == "running":
                if last_event_id != str(push.SNAPSHOT_EVENT_ID):
                    yield push.frame(push.SNAPSHOT_EVENT_ID, push.run_event(snapshot))
                while True:
                    try:
                        await asyncio.wait_for(queue.get(), timeout=push.KEEPALIVE_SECONDS)
                    except TimeoutError:
                        if await request.is_disconnected():
                            return
                        yield ": keep-alive\n\n"
                        continue
                    # Re-authenticate and re-read: pushes only signal commits,
                    # and grants may have changed while the stream was open.
                    fresh = await run_in_threadpool(principal, request)
                    run = await run_in_threadpool(agent_runtime.history.get_run, fresh, run_id)
                    yield push.frame(push.TERMINAL_EVENT_ID, push.run_event(run))
                    return
            elif last_event_id != str(push.TERMINAL_EVENT_ID):
                yield push.frame(push.TERMINAL_EVENT_ID, push.run_event(snapshot))
        except ApiError:
            # Authorization or data changed mid-stream; clients re-GET for the error body.
            return
        finally:
            agent_runtime.push.unsubscribe(run_id, queue)

    return StreamingResponse(stream(), media_type="text/event-stream",
                             headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})
