"""Per-execution event and frame reads used to validate answer references."""

from dataclasses import dataclass, field
from typing import Any

from agent.models import CandidateAnswer, QueryScope


class InvalidToolResult(RuntimeError):
    """The host returned data inconsistent with the fixed query or requested IDs."""


class InvalidReferences(RuntimeError):
    """An answer cited evidence that this execution did not successfully read."""


@dataclass
class EvidenceLedger:
    """Local provenance only; Backend must still revalidate before committing."""

    query: QueryScope
    events: dict[str, set[str]] = field(default_factory=dict)
    frames: dict[str, tuple[str, bool]] = field(default_factory=dict)

    def record_event(self, requested_id: str, response: dict[str, Any]) -> None:
        event = response.get("event")
        if not isinstance(event, dict):
            raise InvalidToolResult("event response has no event")
        start, end = event.get("start_offset_ms"), event.get("end_offset_ms")
        if (
            event.get("event_id") != requested_id
            or event.get("recording_id") != self.query.recording_id
            or event.get("zone_id") != self.query.zone_id
            or type(start) is not int
            or type(end) is not int
            or start < 0
            or start > end
            or end < self.query.start_offset_ms
            or (end == self.query.start_offset_ms and start != end)
            or start >= self.query.end_offset_ms
        ):
            raise InvalidToolResult("event does not belong to the execution query")
        ids = event.get("evidence_ids")
        frames = response.get("evidence")
        if not isinstance(ids, list) or not isinstance(frames, list):
            raise InvalidToolResult("event response has no fixed evidence list")
        listed = set()
        for frame in frames:
            if not isinstance(frame, dict) or frame.get("event_id") != requested_id:
                raise InvalidToolResult("event response contains unrelated evidence")
            evidence_id = frame.get("evidence_id")
            if not isinstance(evidence_id, str):
                raise InvalidToolResult("event response has an invalid evidence ID")
            listed.add(evidence_id)
        if any(not isinstance(value, str) for value in ids) or set(ids) != listed:
            raise InvalidToolResult("event evidence IDs are inconsistent")
        self.events[requested_id] = listed

    def record_evidence(self, requested_id: str, response: dict[str, Any]) -> None:
        evidence = response.get("evidence")
        if not isinstance(evidence, dict) or evidence.get("evidence_id") != requested_id:
            raise InvalidToolResult("evidence response does not match the requested ID")
        event_id = evidence.get("event_id")
        status = evidence.get("media_status")
        if not isinstance(event_id, str) or status not in {"available", "missing"}:
            raise InvalidToolResult("evidence response has invalid identity or media state")
        self.frames[requested_id] = (event_id, status == "available")

    def validate(self, answer: CandidateAnswer) -> None:
        for reference in answer.references:
            if (
                reference.evidence_id not in self.events.get(reference.event_id, set())
                or self.frames.get(reference.evidence_id) != (reference.event_id, True)
            ):
                raise InvalidReferences("answer cites unread, missing or unrelated evidence")
