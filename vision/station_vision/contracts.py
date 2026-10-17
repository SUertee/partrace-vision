"""Person observations shared by pose tracking and interaction analysis."""
from __future__ import annotations
from dataclasses import dataclass


@dataclass
class PersonObservation:
    track_id: str | None
    bbox: list[float]
    score: float
    keypoints: list[list[float]]

    def to_dict(self):
        return {"tracklet_id": self.track_id, "bbox_xyxy": self.bbox,
                "score": self.score, "keypoints_xy_conf": self.keypoints}
