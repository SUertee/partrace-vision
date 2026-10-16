"""Small agent DTOs; no database or application-framework dependency."""

from typing import Annotated, Self

from pydantic import BaseModel, ConfigDict, Field, StringConstraints, model_validator


Id = Annotated[
    str,
    StringConstraints(strict=True, min_length=1, max_length=100,
                      pattern=r"^[A-Za-z0-9][A-Za-z0-9_.:-]*$"),
]
NonBlank = Annotated[str, StringConstraints(strict=True, min_length=1, pattern=r"\S")]
QueryText = Annotated[NonBlank, StringConstraints(max_length=2000)]
Offset = Annotated[int, Field(strict=True, ge=0, le=9223372036854775807)]


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)


class QueryScope(StrictModel):
    recording_id: Id
    camera_id: Id
    zone_id: Id
    start_offset_ms: Offset
    end_offset_ms: Offset

    @model_validator(mode="after")
    def ordered(self) -> Self:
        if self.start_offset_ms >= self.end_offset_ms:
            raise ValueError("query start must precede end")
        return self


class DraftPlan(StrictModel):
    """The model has no field through which it could replace the fixed scope."""

    query_text: QueryText
    steps: list[NonBlank] = Field(min_length=1, max_length=8)


class ConfirmedPlan(StrictModel):
    query: QueryScope
    query_text: QueryText
    steps: list[NonBlank]


class EvidenceReference(StrictModel):
    event_id: Id
    evidence_id: Id


class CandidateAnswer(StrictModel):
    summary: Annotated[NonBlank, StringConstraints(max_length=4000)]
    references: list[EvidenceReference] = Field(max_length=50)

    @model_validator(mode="after")
    def unique_references(self) -> Self:
        pairs = [(item.event_id, item.evidence_id) for item in self.references]
        if len(pairs) != len(set(pairs)):
            raise ValueError("duplicate evidence references are not allowed")
        return self


# Public names usable by the host router without a dependency on Backend DTOs.
Query = QueryScope
AgentPlan = ConfirmedPlan
