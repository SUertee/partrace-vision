"""Conservative image-space hand/object hypothesis rules; not physical contact proof."""
from collections import deque
from dataclasses import dataclass
import math
import numpy as np


def edge_distance(point, box):
    x, y = point
    a, b, c, d = box
    return math.hypot(max(a-x, 0, x-c), max(b-y, 0, y-d))


@dataclass
class Thresholds:
    window_seconds: float = 0.8
    max_gap_seconds: float = 0.3
    near_diagonal: float = 0.25
    move_diagonal: float = 0.10
    stable_diagonal: float = 0.025
    max_relative_error: float = 0.45
    min_direction_cosine: float = 0.70
    max_relative_offset_diagonal: float = 0.12
    max_background_diagonal: float = 0.04


class MotionJudge:
    """One object and one immutable person/hand association per segment.

    Stable support is only a visual stationary baseline, not a detected shelf.
    A pickup candidate is never a verified pickup. Gaps invalidate the baseline.
    """
    def __init__(self, thresholds=None):
        self.cfg = thresholds or Thresholds()
        self.history = deque()
        self.stationary_baseline = False
        self.baseline_center = None
        self.saw_motion = False
        self.last_time = None
        self.owner = None
        self.support_baseline = False

    def reset(self):
        self.history.clear()
        self.stationary_baseline = False
        self.baseline_center = None
        self.saw_motion = False
        self.owner = None
        self.support_baseline = False

    def update(self, time_s, box, wrist=None, owner=None, tracking_ok=True,
               ambiguous=False, background_displacement=(0., 0.), support_observed=False):
        if self.last_time is not None and (time_s <= self.last_time or
                time_s-self.last_time > self.cfg.max_gap_seconds):
            self.reset()
        self.last_time = time_s
        result = {'time_s': round(time_s, 3), 'label': 'uncertain',
                  'verified': False, 'reason': None, 'features': {}}
        if not tracking_ok or box is None:
            self.reset()
            result['reason'] = 'object_tracking_lost'
            return result
        diagonal = math.hypot(box[2]-box[0], box[3]-box[1])
        if diagonal <= 1:
            self.reset()
            result['reason'] = 'invalid_box'
            return result
        if np.linalg.norm(background_displacement)/diagonal > self.cfg.max_background_diagonal:
            self.reset()
            result['reason'] = 'camera_motion_requires_registration'
            return result
        if ambiguous:
            self.reset()
            result['reason'] = 'multiple_people_near_object'
            return result
        if self.owner and owner and self.owner != owner:
            self.reset()
            result['reason'] = 'person_or_hand_changed'
            return result
        if owner:
            self.owner = owner
        center = np.array([(box[0]+box[2])/2, (box[1]+box[3])/2])
        self.history.append((time_s, center, None if wrist is None else np.asarray(wrist),
                             None if wrist is None else edge_distance(wrist, box)/diagonal))
        while len(self.history)>1 and time_s-self.history[1][0] >= self.cfg.window_seconds:
            self.history.popleft()
        if time_s-self.history[0][0] < self.cfg.window_seconds-1e-5:
            result['reason'] = 'collecting_temporal_evidence'
            return result
        entries = list(self.history)
        centers = np.array([r[1] for r in entries])
        excursion = float(np.max(np.linalg.norm(centers-centers[0], axis=1))/diagonal)
        if excursion <= self.cfg.stable_diagonal and not self.saw_motion:
            self.stationary_baseline = True
            self.baseline_center = centers[0].copy()
            self.support_baseline = self.support_baseline or support_observed
        displacement = centers[-1]-centers[0]
        move = float(np.linalg.norm(displacement)/diagonal)
        result['features'] = {'object_motion_diagonal': round(move, 4),
                              'observed_stationary_baseline': self.stationary_baseline,
                              'support_baseline_provided': self.support_baseline}
        if move >= self.cfg.move_diagonal:
            self.saw_motion = True
        if any(r[2] is None for r in entries):
            result['reason'] = 'wrist_missing_in_window'
            return result
        hand_delta = entries[-1][2]-entries[0][2]
        hand_motion = np.linalg.norm(hand_delta)
        relative_error = float(np.linalg.norm(displacement-hand_delta)/max(np.linalg.norm(displacement), 1))
        cosine = float(np.dot(displacement, hand_delta)/max(np.linalg.norm(displacement)*hand_motion, 1e-9))
        near_fraction = sum(r[3] <= self.cfg.near_diagonal for r in entries)/len(entries)
        offsets=np.array([r[2]-r[1] for r in entries])
        offset_change=float(np.max(np.linalg.norm(offsets-offsets[-1],axis=1))/diagonal)
        result['features'].update(relative_motion_error=round(relative_error, 4),
                                  direction_cosine=round(cosine, 4), near_fraction=near_fraction,
                                  wrist_box_distance_diagonal=round(entries[-1][3], 4),
                                  relative_offset_change_diagonal=round(offset_change,4))
        if near_fraction < 0.8:
            result['reason'] = 'hand_not_consistently_near_object'
        elif move < self.cfg.move_diagonal:
            result.update(label='near_only', reason='insufficient_object_motion')
        elif (relative_error <= self.cfg.max_relative_error and cosine >= self.cfg.min_direction_cosine
              and offset_change <= self.cfg.max_relative_offset_diagonal):
            departure = 0 if self.baseline_center is None else float(np.linalg.norm(center-self.baseline_center)/diagonal)
            label = 'pickup_candidate' if self.stationary_baseline and self.support_baseline and departure >= self.cfg.move_diagonal else 'co_motion_candidate'
            result.update(label=label, reason='stationary_then_hand_object_motion' if label=='pickup_candidate' else 'onset_not_observed_or_no_stationary_baseline')
        else:
            result['reason'] = 'hand_object_motion_mismatch'
        return result
