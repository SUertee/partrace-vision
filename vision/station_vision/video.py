from __future__ import annotations
from pathlib import Path
import math
import cv2


class VideoSource:
    """Decode sequentially; sample using media time, not processing speed."""
    def __init__(self, path: Path, sample_fps: float, start_s=0.0, end_s=None):
        if not math.isfinite(sample_fps) or not 0 < sample_fps <= 120:
            raise ValueError("sample_fps must be in (0, 120]")
        if start_s < 0 or (end_s is not None and end_s <= start_s):
            raise ValueError("Invalid time range")
        self.path, self.sample_fps = path, sample_fps
        self.start_s, self.end_s = start_s, end_s
        self.cap = cv2.VideoCapture(str(path))
        if not self.cap.isOpened():
            raise RuntimeError(f"Cannot open video: {path}")
        self.fps = float(self.cap.get(cv2.CAP_PROP_FPS))
        self.frame_count = int(self.cap.get(cv2.CAP_PROP_FRAME_COUNT))
        self.width = int(self.cap.get(cv2.CAP_PROP_FRAME_WIDTH))
        self.height = int(self.cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
        if self.fps <= 0:
            self.cap.release()
            raise RuntimeError("Video reports no valid nominal frame rate")

    def __iter__(self):
        next_ms = self.start_s * 1000
        previous_ms = -1.0
        index = 0
        try:
            while True:
                ok, frame = self.cap.read()
                if not ok:
                    break
                media_ms = float(self.cap.get(cv2.CAP_PROP_POS_MSEC))
                basis = "decoder_media_pts"
                if not math.isfinite(media_ms) or (index > 0 and media_ms <= previous_ms):
                    # Explicit fallback; do not pretend nominal time is a device clock.
                    media_ms = max(index * 1000 / self.fps, previous_ms + 1000 / self.fps)
                    basis = "nominal_fps_fallback"
                previous_ms = media_ms
                if self.end_s is not None and media_ms > self.end_s * 1000:
                    break
                if media_ms + 1e-6 >= next_ms:
                    yield index, media_ms, basis, frame
                    next_ms = media_ms + 1000 / self.sample_fps
                index += 1
        finally:
            self.cap.release()

