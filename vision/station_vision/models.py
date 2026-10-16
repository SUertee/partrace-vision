from __future__ import annotations
import os
from pathlib import Path
import math
import uuid
import yaml
from .contracts import PersonObservation


class PoseTracker:
    def __init__(self, root: Path, config: dict, sample_fps: float):
        config_directory = root / ".runtime" / "ultralytics"
        (config_directory / "Ultralytics").mkdir(parents=True, exist_ok=True)
        os.environ["YOLO_CONFIG_DIR"] = str(config_directory)
        from ultralytics import YOLO, settings
        import torch
        torch.set_num_threads(int(config.get("cpu_threads", 4)))
        settings.update({"sync": False})
        (root / "models").mkdir(exist_ok=True)
        self.weights = root / config.get("weights", "models/yolo26n-pose.pt")
        self.model = YOLO(str(self.weights))
        if self.model.task != "pose":
            raise ValueError("The person_model adapter requires pose weights")
        self.device = config.get("device", "cpu")
        self.imgsz = int(config.get("imgsz", 640))
        self.conf = float(config.get("detection_conf", .1))
        tracker_type = config.get('tracker_type', 'bytetrack')
        if tracker_type not in ('bytetrack', 'botsort'):
            raise ValueError('tracker_type must be bytetrack or botsort')
        self.tracker_file = root / '.runtime' / f'{tracker_type}_{uuid.uuid4().hex[:12]}.yaml'
        self.tracker_file.parent.mkdir(parents=True, exist_ok=True)
        tracker_settings = {
            "tracker_type": tracker_type, "track_high_thresh": config.get('track_high_thresh',.25),
            "track_low_thresh": config.get('track_low_thresh',.1), "new_track_thresh": config.get('new_track_thresh',.25),
            "track_buffer": max(1, math.ceil(sample_fps * config.get("lost_seconds", 1.2))),
            "match_thresh": config.get('match_thresh',.8), "fuse_score": True,
        }
        if tracker_type == 'botsort':
            tracker_settings.update(gmc_method=config.get('gmc_method', 'none'),
                                    proximity_thresh=.5, appearance_thresh=.8,
                                    with_reid=False, model='auto')
            # OSNet gallery association is a separate consumer. Do not run a
            # second implicit appearance model inside this baseline tracker.
        self.tracker_file.write_text(yaml.safe_dump(tracker_settings), encoding="utf-8")

    def reset(self):
        predictor = self.model.predictor
        if predictor is not None:
            for tracker in getattr(predictor, "trackers", []):
                tracker.reset()

    def process(self, image, session_id: str):
        result = self.model.track(image, persist=True, tracker=str(self.tracker_file),
                                  device=self.device, imgsz=self.imgsz, conf=self.conf,
                                  verbose=False)[0]
        if result.boxes is None:
            return []
        boxes = result.boxes.xyxy.cpu().numpy()
        scores = result.boxes.conf.cpu().numpy()
        ids = result.boxes.id.cpu().numpy() if result.boxes.id is not None else None
        kps = result.keypoints.data.cpu().numpy() if result.keypoints is not None else None
        return [PersonObservation(
            f"{session_id}-t{int(ids[i])}" if ids is not None else None,
            [round(float(x), 2) for x in box], float(scores[i]),
            [[float(x) for x in p] for p in kps[i]] if kps is not None else [],
        ) for i, box in enumerate(boxes)]
