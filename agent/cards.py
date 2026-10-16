"""Deterministic card assembly from ledger-validated reads; no model involvement.

Cards only restate what this execution actually read: summary wording comes from
the validated answer, facts come from retained tool payloads, and gaps are
reported instead of hidden. Construction validates the answer first, so an
invalid reference can never reach a card.
"""

from typing import Annotated, Literal

from pydantic import Field, StringConstraints

from agent.models import CandidateAnswer, Id, NonBlank, QueryScope, StrictModel
from agent.tools.evidence import EvidenceLedger, InvalidReferences

CardTitle = Annotated[NonBlank, StringConstraints(max_length=200)]
CardText = Annotated[NonBlank, StringConstraints(max_length=4000)]

# One summary card, up to one evidence card per referenced event (answer cap is
# 50), plus at most two gap cards.
_MAX_BUNDLE_CARDS = 60
_MAX_CARD_FRAMES = 200


class EvidenceItem(StrictModel):
    """One read keyframe; clients resolve media via GET /api/v1/evidence/{id}/media."""

    event_id: Id
    evidence_id: Id
    offset_ms: int = Field(ge=0)
    media_status: Literal["available", "missing"]


class EventEvidenceCard(StrictModel):
    """A cited event with its keyframes, copied from validated read payloads."""

    kind: Literal["event_evidence"] = "event_evidence"
    title: CardTitle
    event_id: Id
    event_type: Id
    observation_status: Literal["candidate", "observed"]
    detection_source: Literal["mock", "manual", "automatic"]
    start_offset_ms: int = Field(ge=0)
    end_offset_ms: int = Field(ge=0)
    evidence: list[EvidenceItem] = Field(max_length=_MAX_CARD_FRAMES)


class CoverageGapCard(StrictModel):
    """An explicit retrieval or media gap; counts stay empty for media gaps."""

    kind: Literal["coverage_gap"] = "coverage_gap"
    title: CardTitle
    gap: Literal["unindexed", "no_matches", "media_missing"]
    detail: CardText
    eligible_event_count: int | None = Field(default=None, ge=0)
    indexed_event_count: int | None = Field(default=None, ge=0)


class RunSummaryCard(StrictModel):
    """The assistant answer; references are a validated subset of actual reads."""

    kind: Literal["run_summary"] = "run_summary"
    title: CardTitle
    summary: CardText
    references: list[EvidenceItem] = Field(max_length=50)


class RunStatusCard(StrictModel):
    """A terminal stopped/failed state; carries no business facts."""

    kind: Literal["run_status"] = "run_status"
    title: CardTitle
    status: Literal["stopped", "failed"]
    detail: CardText


Card = Annotated[
    EventEvidenceCard | CoverageGapCard | RunSummaryCard | RunStatusCard,
    Field(discriminator="kind"),
]


class CardBundle(StrictModel):
    """Persisted and pushed payload for one run; query echoes the fixed scope."""

    version: Literal[1] = 1
    run_id: Id
    query: QueryScope
    cards: list[Card] = Field(max_length=_MAX_BUNDLE_CARDS)


def _evidence_item(payload: dict) -> EvidenceItem:
    return EvidenceItem(
        event_id=payload["event_id"],
        evidence_id=payload["evidence_id"],
        offset_ms=payload["offset_ms"],
        media_status=payload["media_status"],
    )


def _summary_card(ledger: EvidenceLedger, answer: CandidateAnswer) -> RunSummaryCard:
    references = []
    for reference in answer.references:
        payload = ledger.frame_payloads.get(reference.evidence_id)
        if payload is None or payload["event_id"] != reference.event_id:
            raise InvalidReferences("answer cites evidence without a retained payload")
        references.append(_evidence_item(payload))
    return RunSummaryCard(title="分析结论", summary=answer.summary, references=references)


def _event_card(ledger: EvidenceLedger, event_id: str) -> EventEvidenceCard:
    event = ledger.event_payloads[event_id]
    frames = sorted(
        (ledger.frame_payloads[evidence_id] for evidence_id in ledger.events[event_id]),
        key=lambda frame: (frame["offset_ms"], frame["evidence_id"]),
    )
    return EventEvidenceCard(
        title=f"事件 {event_id} 证据",
        event_id=event_id,
        event_type=event["event_type"],
        observation_status=event["observation_status"],
        detection_source=event["detection_source"],
        start_offset_ms=event["start_offset_ms"],
        end_offset_ms=event["end_offset_ms"],
        evidence=[_evidence_item(frame) for frame in frames],
    )


def _semantic_gap_card(read: dict) -> CoverageGapCard | None:
    status = read["search_status"]
    counts = read["coverage"]
    eligible, indexed = counts["eligible_event_count"], counts["indexed_event_count"]
    if status == "unindexed":
        return CoverageGapCard(
            title="检索覆盖缺口", gap="unindexed",
            detail=f"本次范围内共有 {eligible} 个事件，其中 {indexed} 个已建立语义索引；"
                   "未索引部分无法通过描述检索，结果可能不完整。",
            eligible_event_count=eligible, indexed_event_count=indexed,
        )
    if status == "no_matches":
        return CoverageGapCard(
            title="未检索到匹配候选", gap="no_matches",
            detail=f"本次范围内共有 {eligible} 个事件（{indexed} 个已建立语义索引），"
                   "未找到与检索描述匹配的候选事件。",
            eligible_event_count=eligible, indexed_event_count=indexed,
        )
    return None


def _media_gap_card(ledger: EvidenceLedger) -> CoverageGapCard | None:
    missing = sum(1 for _, available in ledger.frames.values() if not available)
    if missing == 0:
        return None
    return CoverageGapCard(
        title="关键帧媒体缺失", gap="media_missing",
        detail=f"{missing} 个已回读关键帧的媒体当前缺失，不能作为有效证据引用。",
    )


def build_cards(ledger: EvidenceLedger, answer: CandidateAnswer, run_id: str) -> CardBundle:
    """Assemble the bundle for a completed run; deterministic for a given ledger."""
    ledger.validate(answer)
    cards: list[Card] = [_summary_card(ledger, answer)]
    cited_events = dict.fromkeys(reference.event_id for reference in answer.references)
    cards.extend(_event_card(ledger, event_id) for event_id in cited_events)
    if ledger.semantic_reads:
        semantic_gap = _semantic_gap_card(ledger.semantic_reads[-1])
        if semantic_gap is not None:
            cards.append(semantic_gap)
    media_gap = _media_gap_card(ledger)
    if media_gap is not None:
        cards.append(media_gap)
    return CardBundle(run_id=run_id, query=ledger.query, cards=cards)


def build_status_bundle(run_id: str, query: QueryScope, status: str, detail: str) -> CardBundle:
    """Terminal-state bundle for stopped/failed runs; never carries business facts."""
    titles = {"stopped": "分析已停止", "failed": "分析失败"}
    if status not in titles:
        raise ValueError("status bundles only exist for stopped or failed runs")
    return CardBundle(
        run_id=run_id, query=query,
        cards=[RunStatusCard(title=titles[status], status=status, detail=detail)],
    )
