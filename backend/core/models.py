"""Validated request and immutable configuration objects for the v0.3 contract.

These models validate shape and relationships available in a request. Resource
authorization, configuration ownership and real media/time provenance remain
service checks; accepting an object does not certify its observations.
"""

from datetime import datetime, timezone
from decimal import Decimal
from typing import Annotated, Literal, Self

from pydantic import (
    model_serializer,
    AfterValidator,
    BaseModel,
    ConfigDict,
    Field,
    StringConstraints,
    model_validator,
)


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, allow_inf_nan=False)


Id = Annotated[
    str,
    StringConstraints(
        strict=True, min_length=1, max_length=100,
        pattern=r"^[A-Za-z0-9][A-Za-z0-9_.:-]*$",
    ),
]


def _utc_time(value: str) -> str:
    try:
        timestamp = datetime.fromisoformat(value[:-1] + "+00:00")
    except ValueError:
        raise ValueError("UTC time must be a valid calendar date") from None
    if timestamp < datetime(1970, 1, 1, tzinfo=timezone.utc):
        raise ValueError("UTC time must not precede 1970-01-01")
    return value


UtcTime = Annotated[
    str,
    StringConstraints(
        strict=True,
        pattern=r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{1,3})?Z$",
    ),
    AfterValidator(_utc_time),
]
OffsetMs = Annotated[int, Field(strict=True, ge=0, le=9223372036854775807)]
DurationMs = Annotated[int, Field(strict=True, ge=1, le=9223372036854775807)]
# 货架层级自下而上从 1 开始；1–20 是校验上限，不代表现场层数，未知或不适用传 null。
ShelfLevel = Annotated[int, Field(strict=True, ge=1, le=20)]
NormalizedCoordinate = Annotated[float, Field(strict=True, ge=0, le=1)]


def _file_name(value: str) -> str:
    if value in {".", ".."} or any(
        character in "/\\" or ord(character) < 32 or ord(character) == 127
        for character in value
    ):
        raise ValueError("file_name must be a single safe basename")
    return value


FileName = Annotated[
    str, StringConstraints(strict=True, min_length=1, max_length=200),
    AfterValidator(_file_name),
]


def _bbox(value: list[float]) -> list[float]:
    x, y, width, height = (Decimal(str(coordinate)) for coordinate in value)
    if width <= 0 or height <= 0 or x + width > 1 or y + height > 1:
        raise ValueError("bbox must have positive dimensions and remain inside the image")
    return value


Bbox = Annotated[
    list[NormalizedCoordinate], Field(min_length=4, max_length=4), AfterValidator(_bbox),
]
SourceMode = Literal["prerecorded", "sdk", "mock"]
EventType = Literal["enter_zone", "dwell", "shelf_interaction", "leave_zone"]
Decision = Literal["confirmed", "dismissed", "uncertain"]


class Query(StrictModel):
    recording_id: Id
    camera_id: Id
    zone_id: Id
    start_offset_ms: OffsetMs
    end_offset_ms: OffsetMs

    @model_validator(mode="after")
    def ordered_window(self) -> Self:
        if self.start_offset_ms >= self.end_offset_ms:
            raise ValueError("query start must precede end")
        return self


class UtcQuery(StrictModel):
    recording_id: Id
    camera_id: Id
    zone_id: Id
    start: UtcTime
    end: UtcTime

    @model_validator(mode="after")
    def ordered_window(self) -> Self:
        if datetime.fromisoformat(self.start[:-1] + "+00:00") >= datetime.fromisoformat(
            self.end[:-1] + "+00:00"
        ):
            raise ValueError("query start must precede end")
        return self


class RecordingInput(StrictModel):
    recording_id: Id
    camera_id: Id
    config_version: Id
    source_mode: SourceMode
    started_at: UtcTime | None
    duration_ms: DurationMs
    file_name: FileName | None

    @model_validator(mode="after")
    def source_requirements(self) -> Self:
        if self.source_mode == "mock":
            if self.started_at is not None or self.file_name is not None:
                raise ValueError("mock recordings must not claim a real start time or media")
        elif self.file_name is None:
            raise ValueError("real recordings require a continuous video file")
        return self


class EventInput(StrictModel):
    event_id: Id
    # 货架编号（如 E3），不表示层级；层级用 shelf_level。未知货架传 null，缺省 E3。
    zone_id: Id | None = "E3"
    track_key: Id | None
    person_id: Annotated[str, StringConstraints(pattern=r"^G[0-9]{4,99}$", max_length=100)] | None = None
    event_type: EventType
    shelf_level: ShelfLevel | None = None
    observation_status: Literal["observed", "candidate"]
    start_offset_ms: OffsetMs
    end_offset_ms: OffsetMs
    detection_source: Literal["automatic", "manual", "mock"]
    summary: Annotated[str, StringConstraints(max_length=1000)] | None

    @model_serializer(mode="wrap")
    def serialize_person_identity(self, handler):
        # Omitted and explicit null stay equivalent to pre-field manifests.
        # Known G identities remain part of the immutable import digest.
        value = handler(self)
        if value.get("person_id") is None:
            value.pop("person_id", None)
        return value

    @model_validator(mode="after")
    def ordered_event(self) -> Self:
        if self.start_offset_ms > self.end_offset_ms:
            raise ValueError("event start must not follow end")
        return self


class EvidenceInput(StrictModel):
    evidence_id: Id
    event_id: Id
    offset_ms: OffsetMs
    bbox_json: Bbox | None
    file_name: FileName | None


class RecordingManifest(StrictModel):
    schema_version: Literal["0.3-mvp-draft"]
    recording: RecordingInput
    events: list[EventInput]
    evidence: list[EvidenceInput]

    @model_validator(mode="after")
    def validate_relationships(self) -> Self:
        event_ids = [event.event_id for event in self.events]
        evidence_ids = [frame.evidence_id for frame in self.evidence]
        if len(event_ids) != len(set(event_ids)) or len(evidence_ids) != len(set(evidence_ids)):
            raise ValueError("event and evidence IDs must each be unique in a manifest")
        event_set = set(event_ids)
        for event in self.events:
            if event.end_offset_ms > self.recording.duration_ms:
                raise ValueError("event offsets must remain inside the recording")
            if (self.recording.source_mode == "mock") != (event.detection_source == "mock"):
                raise ValueError("mock and real detection sources must not be mixed")
        for frame in self.evidence:
            if frame.event_id not in event_set:
                raise ValueError("evidence must reference an event in the same manifest")
            if frame.offset_ms > self.recording.duration_ms:
                raise ValueError("evidence offsets must remain inside the recording")
            if self.recording.source_mode == "mock" and frame.file_name is not None:
                raise ValueError("mock evidence must not claim real media")
        file_names = [
            name for name in [self.recording.file_name, *(frame.file_name for frame in self.evidence)]
            if name is not None
        ]
        if len(file_names) != len(set(file_names)):
            raise ValueError("each manifest file_name must identify exactly one file")
        return self


class ReviewCreateRequest(StrictModel):
    review_id: Id
    event_id: Id
    query: Query | UtcQuery
    decision: Decision
    note: Annotated[str, StringConstraints(max_length=2000)] | None


def _roi(value: list[list[float]]) -> list[list[float]]:
    vertices = [tuple(Decimal(str(coordinate)) for coordinate in point) for point in value]
    if len(vertices) > 1 and vertices[0] == vertices[-1]:
        vertices = vertices[:-1]
    if len(vertices) < 3 or len(set(vertices)) != len(vertices):
        raise ValueError("ROI needs at least three distinct vertices")
    area = sum(
        left[0] * right[1] - right[0] * left[1]
        for left, right in zip(vertices, vertices[1:] + vertices[:1])
    )
    if area == 0:
        raise ValueError("ROI must enclose a nonzero area")

    def cross(a, b, c):
        return (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])

    def on_segment(a, b, point):
        return min(a[0], b[0]) <= point[0] <= max(a[0], b[0]) and min(
            a[1], b[1]
        ) <= point[1] <= max(a[1], b[1])

    edges = list(zip(vertices, vertices[1:] + vertices[:1]))
    for index, (a, b) in enumerate(edges):
        for other_index in range(index + 1, len(edges)):
            if other_index == index + 1 or (index == 0 and other_index == len(edges) - 1):
                continue
            c, d = edges[other_index]
            first, second, third, fourth = cross(a, b, c), cross(a, b, d), cross(c, d, a), cross(c, d, b)
            crossing = first * second < 0 and third * fourth < 0
            touching = (
                (first == 0 and on_segment(a, b, c))
                or (second == 0 and on_segment(a, b, d))
                or (third == 0 and on_segment(c, d, a))
                or (fourth == 0 and on_segment(c, d, b))
            )
            if crossing or touching:
                raise ValueError("ROI edges must not self-intersect")
    return value


Roi = Annotated[
    list[Annotated[list[NormalizedCoordinate], Field(min_length=2, max_length=2)]],
    Field(min_length=3), AfterValidator(_roi),
]


class ZoneConfig(StrictModel):
    zone_id: Id
    name: Annotated[str, StringConstraints(min_length=1)]
    roi: Roi


class CameraConfig(StrictModel):
    camera_id: Id
    name: Annotated[str, StringConstraints(min_length=1)]
    zones: list[ZoneConfig]

    @model_validator(mode="after")
    def unique_zones(self) -> Self:
        identifiers = [zone.zone_id for zone in self.zones]
        if len(identifiers) != len(set(identifiers)):
            raise ValueError("zone IDs must be unique within a camera")
        return self


class Config(StrictModel):
    config_version: Id
    cameras: list[CameraConfig]

    @model_validator(mode="after")
    def unique_cameras(self) -> Self:
        identifiers = [camera.camera_id for camera in self.cameras]
        if len(identifiers) != len(set(identifiers)):
            raise ValueError("camera IDs must be unique in a configuration")
        return self
