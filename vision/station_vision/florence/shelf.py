"""Registered shelf appearance differences, never inventory or removal decisions."""
import cv2
import numpy as np


class ShelfState:
    def __init__(self, zones, pixel_threshold=25, change_fraction=.06, min_visible=.35):
        self.zones = zones
        self.pixel_threshold = pixel_threshold
        self.change_fraction = change_fraction
        self.min_visible = min_visible
        self.baselines = {}
        self.pending = {}
        self.last_change = {}

    def update(self, aligned, visible_mask, time_ms, frame_id, valid, busy_zones=()):
        gray = cv2.GaussianBlur(cv2.cvtColor(aligned, cv2.COLOR_BGR2GRAY), (5, 5), 0)
        outputs = []
        for zone in self.zones:
            mask = np.zeros_like(gray)
            cv2.fillPoly(mask, [np.rint(zone['polygon']).astype(np.int32)], 255)
            pixels = mask > 0
            usable = pixels & (visible_mask > 0)
            area = max(int(pixels.sum()), 1)
            item = {'region_id': zone['id'], 'shelf_level_hint': zone.get('level'),
                    'status': 'unknown', 'change_fraction': None,
                    'visible_fraction': round(float(usable.sum() / area), 4),
                    'frame_id': frame_id, 'time_ms': time_ms, 'verified': False}
            if not valid or usable.sum() / area < self.min_visible:
                item['reason'] = 'registration_invalid_or_occluded'
                self.pending.pop(zone['id'], None)
                outputs.append(item)
                continue
            base = self.baselines.get(zone['id'])
            if base is None:
                self.baselines[zone['id']] = (gray.copy(), usable.copy(), frame_id, time_ms)
                item.update(status='baseline', reason='first_unoccluded_reference')
                outputs.append(item)
                continue
            old, old_visible, before_id, before_ms = base
            common = usable & old_visible
            if common.sum() / area < self.min_visible:
                item['reason'] = 'insufficient_common_visible_pixels'
                if zone['id'] not in busy_zones:
                    self.baselines[zone['id']] = (gray.copy(), usable.copy(), frame_id, time_ms)
                outputs.append(item)
                continue
            differences = gray.astype(float) - old.astype(float)
            illumination = float(np.median(differences[common]))
            fraction = float((np.abs(differences[common] - illumination) > self.pixel_threshold).mean())
            item.update(change_fraction=round(fraction, 4), before_frame_id=before_id,
                        before_time_ms=before_ms, reason='registered_visible_appearance_only')
            if fraction >= self.change_fraction:
                since = self.pending.setdefault(zone['id'], time_ms)
                item['status'] = 'appearance_changed' if time_ms - since >= 200 else 'change_pending'
                if item['status'] == 'appearance_changed':
                    if self.last_change.get(zone['id'], -1e9) < since:
                        self.last_change[zone['id']] = time_ms
            else:
                item['status'] = 'stable'
                self.pending.pop(zone['id'], None)
            # Hold the pre-action reference while a hand is in this region;
            # otherwise refresh only after a complete settling interval.
            if zone['id'] not in busy_zones and time_ms - self.last_change.get(zone['id'], -1e9) > 1500:
                if item['status'] in ('stable', 'appearance_changed'):
                    self.baselines[zone['id']] = (gray.copy(), usable.copy(), frame_id, time_ms)
                    self.pending.pop(zone['id'], None)
            outputs.append(item)
        return outputs
