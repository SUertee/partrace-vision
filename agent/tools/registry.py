"""Restricted tool schemas and closures bound to the confirmed query."""

from copy import deepcopy
from typing import Any, Annotated

from langchain_core.tools import StructuredTool
from pydantic import Field

from agent.contracts import ToolGateway
from agent.models import Id, QueryScope, StrictModel
from agent.tools.evidence import EvidenceLedger


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
        result = await gateway.semantic_search_events(deepcopy(fixed), query_text, top_k, min_score)
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
    return [
        StructuredTool.from_function(
            name=name, coroutine=function, args_schema=schema, description=description
        )
        for name, function, schema, description in descriptions
    ], ledger
