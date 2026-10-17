"""Florence rectangles initialize/correct KLT, without fabricated detector scores."""
import cv2
import numpy as np

from ..hand_object.tracking import ParcelFlow


def iou(a, b):
    a, b = np.asarray(a, float), np.asarray(b, float)
    overlap = np.maximum(0, np.minimum(a[2:], b[2:]) - np.maximum(a[:2], b[:2]))
    intersection = float(np.prod(overlap))
    union = float(np.prod(a[2:] - a[:2]) + np.prod(b[2:] - b[:2]) - intersection)
    return intersection / max(union, 1e-6)


class FlorenceKLTBank:
    def __init__(self, max_tracks=8, max_age_ms=4000, match_iou=.2,
                 min_flow_steps=3, correction_reset_diagonal=.12):
        if max_tracks < 1 or max_age_ms <= 0 or not 0 < match_iou <= 1 or min_flow_steps < 1:
            raise ValueError('Invalid KLT track limits')
        self.max_tracks = max_tracks
        self.max_age_ms = max_age_ms
        self.match_iou = match_iou
        self.min_flow_steps = min_flow_steps
        self.correction_reset_diagonal = correction_reset_diagonal
        self.tracks = {}
        self.next_id = 1
        self.log = []

    def retire(self, pid, time_ms, reason):
        self.tracks.pop(pid)
        self.log.append({'time_ms': time_ms, 'parcel_id': pid, 'event': 'retired', 'reason': reason})

    def advance(self, previous, current, time_ms, reset_reason=None):
        for pid, track in list(self.tracks.items()):
            if reset_reason or time_ms - track['localized_at_ms'] > self.max_age_ms:
                self.retire(pid, time_ms, reset_reason or 'localization_age_expired')
                continue
            track['corrected_now'] = False
            track['identity_reset'] = False
            was_ok = track['flow'].ok
            if previous is not None:
                track['flow'].update(previous, current)
            if track['flow'].ok:
                track['flow_steps'] += 1
            elif was_ok:
                self.log.append({'time_ms': time_ms, 'parcel_id': pid, 'event': 'klt_lost',
                                 'reason': track['flow'].quality.get('loss_reason')})

    def correct(self, gray, detections, time_ms):
        known_before = set(self.tracks)
        # Near-identical generated phrases must not create duplicate parcels.
        unique = []
        for d in sorted(detections, key=lambda item: item.get('priority', 1e6)):
            if not any(iou(d['box'], prior['box']) > .8 for prior in unique):
                unique.append(d)
        proposals = []
        ambiguous_tracks, ambiguous_detections = set(), set()
        for j, d in enumerate(unique):
            options = sorted([(iou(d['box'], t['flow'].box), pid) for pid, t in self.tracks.items()
                              if time_ms - t['localized_at_ms'] <= self.max_age_ms], reverse=True)
            options = [(score, pid) for score, pid in options if score >= self.match_iou]
            if len(options) > 1 and options[0][0] - options[1][0] < .08:
                ambiguous_tracks.update(pid for _, pid in options)
                ambiguous_detections.add(j)
            elif options:
                proposals.append((options[0][0], options[0][1], j))
        used_tracks, used_detections = set(), set(ambiguous_detections)
        for pid in ambiguous_tracks:
            self.tracks[pid]['ambiguous'] = True
            self.log.append({'time_ms': time_ms, 'parcel_id': pid, 'event': 'correction_ambiguous'})
        for _, pid, j in sorted(proposals, reverse=True):
            if pid in used_tracks or j in used_detections or pid in ambiguous_tracks:
                continue
            t, d = self.tracks[pid], unique[j]
            old_box = t['flow'].box.copy()
            new_flow = ParcelFlow(gray, d['box'], 'affine_corners')
            if not new_flow.ok:
                used_detections.add(j)
                self.log.append({'time_ms': time_ms, 'parcel_id': pid, 'event': 'correction_rejected',
                                 'reason': new_flow.quality['loss_reason']})
                continue
            diagonal = max(float(np.linalg.norm(old_box[2:] - old_box[:2])), 1)
            delta = float(np.linalg.norm((np.asarray(d['box'][:2]) + d['box'][2:] -
                                         old_box[:2] - old_box[2:]) / 2) / diagonal)
            reset = not t['flow'].ok or delta > self.correction_reset_diagonal
            t.update(flow=new_flow, localized_at_ms=time_ms, corrected_now=True,
                     identity_reset=reset, label=d['label'], origin_task=d['task'],
                     origin_person_hint=d.get('person_hint'), detector_hits=t['detector_hits'] + 1,
                     ambiguous=False)
            if reset:
                t['flow_steps'] = 0
            used_tracks.add(pid)
            used_detections.add(j)
            self.log.append({'time_ms': time_ms, 'parcel_id': pid, 'event': 'florence_corrected',
                             'correction_diagonal': round(delta, 4), 'motion_evidence': False})
        for j, d in enumerate(unique):
            if j in used_detections:
                continue
            # Do not split a plausible unmatched old track into duplicate IDs.
            if any(iou(d['box'], t['flow'].box) >= self.match_iou for pid, t in self.tracks.items() if pid in known_before):
                continue
            if len(self.tracks) >= self.max_tracks:
                far = [(t['last_near_ms'], pid) for pid, t in self.tracks.items()
                       if time_ms - t['last_near_ms'] > 1000]
                if not far:
                    continue
                self.retire(min(far)[1], time_ms, 'capacity_replaced_far_track')
            flow = ParcelFlow(gray, d['box'], 'affine_corners')
            if not flow.ok:
                self.log.append({'time_ms': time_ms, 'event': 'initialization_rejected',
                                 'box': d['box'], 'reason': flow.quality['loss_reason']})
                continue
            pid = f'K{self.next_id:04d}'
            self.next_id += 1
            self.tracks[pid] = {'flow': flow, 'localized_at_ms': time_ms,
                'created_ms': time_ms, 'last_near_ms': time_ms if d.get('priority', 1e6) < .4 else -1e9,
                'flow_steps': 0, 'detector_hits': 1, 'label': d['label'], 'origin_task': d['task'],
                'origin_person_hint': d.get('person_hint'), 'corrected_now': True,
                'identity_reset': True, 'ambiguous': False}
            self.log.append({'time_ms': time_ms, 'parcel_id': pid, 'event': 'florence_initialized',
                             'box': d['box'], 'label': d['label']})

    def observations(self, time_ms):
        result = []
        for pid, t in self.tracks.items():
            valid = (t['flow'].ok and t['flow_steps'] >= self.min_flow_steps and not t['ambiguous']
                     and time_ms - t['localized_at_ms'] <= self.max_age_ms)
            result.append({'id': pid, 'box': t['flow'].box.round(2).tolist() if t['flow'].ok else None,
                'valid': bool(valid), 'source': 'florence' if t['corrected_now'] else 'klt',
                'label': t['label'], 'score': None, 'origin_task': t['origin_task'],
                'origin_person_hint': t['origin_person_hint'], 'detector_hits': t['detector_hits'],
                'age_since_localization_ms': round(time_ms - t['localized_at_ms'], 3),
                'corrected_now': t['corrected_now'], 'identity_reset': t['identity_reset'],
                'ambiguous': t['ambiguous'], 'motion_observed': bool(valid and not t['corrected_now']),
                'loss_reason': t['flow'].quality.get('loss_reason'), 'flow_quality': t['flow'].quality})
        return result
