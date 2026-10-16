"""Restricted tool schemas and closures bound to the confirmed query."""

from copy import deepcopy
import asyncio
from typing import Any, Annotated

from langchain_core.tools import StructuredTool
from pydantic import Field

from agent.contracts import ToolGateway, ToolUnavailable
from agent.models import Id, QueryScope, StrictModel
from agent.tools.evidence import EvidenceLedger
from agent.tools.skills import build_skill_tool


class EmptyArguments(StrictModel):
    pass


class EventArguments(StrictModel):
    event_id: Id


class EvidenceArguments(StrictModel):
    evidence_id: Id


class SemanticArguments(StrictModel):
    top_k: Annotated[int, Field(strict=True, ge=1, le=50)] = 10
    min_score: Annotated[float, Field(ge=-1, le=1, allow_inf_nan=False)] | None = None


def build_tools(
    gateway: ToolGateway, query: QueryScope, query_text: str
) -> tuple[list[StructuredTool], EvidenceLedger]:
    """Freeze identity-free query/text in closures, never model-controlled args."""
    fixed = query.model_dump(mode="json")
    ledger = EvidenceLedger(query)
    semantic_lock = asyncio.Lock()

    def sourced(name: str, data: dict[str, Any]) -> dict[str, Any]:
        return {
            "kind": "untrusted_readonly_data",
            "source": f"backend:{name}",
            "query": deepcopy(fixed),
            "data": data,
        }

    async def search_events() -> dict[str, Any]:
        return sourced("search_events", await gateway.search_events(deepcopy(fixed)))

    async def semantic_search_events(
        top_k: int = 10, min_score: float | None = None
    ) -> dict[str, Any]:
        # Parallel model tool calls must not retry an encoder after the first
        # call established that this optional dependency is unavailable.
        async with semantic_lock:
            return await read_semantic(top_k, min_score)

    async def read_semantic(top_k: int, min_score: float | None) -> dict[str, Any]:
        if not ledger.semantic_unavailable:
            try:
                result = await gateway.semantic_search_events(deepcopy(fixed), query_text, top_k, min_score)
            except ToolUnavailable:
                ledger.semantic_unavailable = True
        if ledger.semantic_unavailable:
            return sourced("semantic_search_events", {
                "status": "unavailable",
                "error": {"code": "model_unavailable", "message": "语义检索服务不可用，未获得语义匹配结果。"},
                "fallback_tool": "search_events",
                "instruction": "可读取同一确认范围的事件；本轮不要重复请求语义检索。",
            })
        ledger.record_semantic(result)
        return sourced("semantic_search_events", result)

    async def get_event(event_id: str) -> dict[str, Any]:
        result = await gateway.get_event(deepcopy(fixed), event_id)
        ledger.record_event(event_id, result)
        return sourced("get_event", result)

    async def get_evidence(evidence_id: str) -> dict[str, Any]:
        result = await gateway.get_evidence(deepcopy(fixed), evidence_id)
        ledger.record_evidence(evidence_id, result)
        return sourced("get_evidence", result)

    async def read_recording() -> dict[str, Any]:
        return sourced("read_recording", await gateway.read_recording(deepcopy(fixed)))

    descriptions = [
        ("search_events", search_events, EmptyArguments,
         "Read events in the already confirmed fixed recording, zone and time window. No arguments."),
        ("semantic_search_events", semantic_search_events, SemanticArguments,
         "Search the fixed confirmed query_text within the fixed query. Scores rank candidates, not certainty."),
        ("get_event", get_event, EventArguments,
         "Read one candidate event and its fixed evidence IDs. Required before citing that event."),
        ("get_evidence", get_evidence, EvidenceArguments,
         "Read one real keyframe's current metadata; cite only after this returns available."),
        ("read_recording", read_recording, EmptyArguments,
         "Read the fixed recording's provenance and duration; cannot switch recordings."),
    ]
    tools = [
        StructuredTool.from_function(
            name=name, coroutine=function, args_schema=schema, description=description
        )
        for name, function, schema, description in descriptions
    ]
    return [build_skill_tool(), *tools], ledger
