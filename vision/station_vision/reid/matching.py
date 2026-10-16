"""Two public states, persistent unknown aliases, one-to-one gallery matching."""
from dataclasses import dataclass
import math
import numpy as np
from scipy.optimize import linear_sum_assignment


def unit_vector(value):
    v = np.asarray(value, dtype=np.float32)
    if v.ndim != 1 or not np.isfinite(v).all() or np.linalg.norm(v) < 1e-8:
        raise ValueError('Expected a finite nonzero embedding vector')
    return v / np.linalg.norm(v)


@dataclass
class TrackState:
    unknown_id: str
    last_seen: float
    linked_id: str | None = None
    proposal: str | None = None
    supports: int = 0
    last_support: float = -math.inf
    last_feature: float = -math.inf
    contradictions: int = 0
    score: float | None = None


class GalleryMatcher:
    def __init__(self, profiles, threshold=.65, margin=.08, confirmations=3,
                 retain_threshold=.55, max_support_gap_ms=1000, reservation_ms=800,
                 contradiction_limit=3,top_k_templates=3):
        if not -1 <= retain_threshold <= threshold <= 1 or not 0 <= margin <= 2:
            raise ValueError('Invalid similarity thresholds')
        if confirmations < 1 or contradiction_limit < 1 or max_support_gap_ms <= 0 or reservation_ms < 0:
            raise ValueError('Invalid matching settings')
        self.ids = list(profiles)
        self.templates = {}
        dimensions = set()
        for pid, vectors in profiles.items():
            if not vectors:
                raise ValueError(f'Empty profile: {pid}')
            rows = np.stack([unit_vector(v) for v in vectors])
            dimensions.add(rows.shape[1])
            self.templates[pid] = rows
        if len(dimensions) > 1:
            raise ValueError('Gallery embedding dimensions differ')
        self.threshold, self.margin = threshold, margin
        self.confirmations, self.retain_threshold = confirmations, retain_threshold
        self.max_gap, self.reservation_ms = max_support_gap_ms, reservation_ms
        self.contradiction_limit = contradiction_limit
        if not isinstance(top_k_templates,int) or top_k_templates<1:raise ValueError('top_k_templates must be positive integer')
        self.top_k_templates=top_k_templates
        self.states = {}
        self.next_unknown = 1
        self.last_time = -math.inf
        self.transitions = []

    def scores(self, feature):
        vector = unit_vector(feature)
        result = []
        for pid in self.ids:
            values = np.clip(self.templates[pid] @ vector, -1, 1)
            # Use several representatives instead of one accidental maximum.
            k = min(self.top_k_templates, len(values))
            result.append(float(np.sort(values)[-k:].mean()))
        return np.asarray(result)

    def public(self, track_id):
        s = self.states[track_id]
        return dict(person_id=s.linked_id or s.unknown_id,
                    status='matched' if s.linked_id else 'unmatched')

    def update(self, time_ms, observations):
        if not math.isfinite(time_ms) or time_ms < self.last_time:
            raise ValueError('Matcher requires monotonic source time')
        self.last_time = time_ms
        keys = [o['track_id'] for o in observations]
        if len(keys) != len(set(keys)):
            raise ValueError('Duplicate local track in one frame')
        for key, state in self.states.items():
            if state.linked_id and time_ms - state.last_seen > self.reservation_ms:
                previous = self.public(key)
                state.linked_id = None
                state.proposal, state.supports = None, 0
                state.contradictions = 0
                self.transitions.append(dict(time_ms=time_ms, track_id=key, previous=previous,
                                             current=self.public(key), reason='tracking_gap'))
        before = {}
        feature_scores = {}
        for o in observations:
            key = o['track_id']
            if key not in self.states:
                self.states[key] = TrackState(f'U{self.next_unknown:04d}', time_ms)
                self.next_unknown += 1
            state = self.states[key]
            before[key] = self.public(key)
            state.last_seen = time_ms
            if o.get('feature') is not None and time_ms > state.last_feature:
                state.last_feature = time_ms
                feature_scores[key] = self.scores(o['feature'])

        # Recheck existing identities without changing them on a single noisy frame.
        for key, scores in feature_scores.items():
            state = self.states[key]
            if not state.linked_id:
                continue
            j = self.ids.index(state.linked_id)
            state.score = float(scores[j])
            competitor = max((float(v) for i, v in enumerate(scores) if i != j), default=-1.)
            bad = scores[j] < self.retain_threshold or (
                competitor >= self.threshold and competitor - scores[j] >= self.margin)
            state.contradictions = state.contradictions + 1 if bad else 0
            if state.contradictions >= self.contradiction_limit:
                state.linked_id = None
                state.proposal, state.supports = None, 0
                state.contradictions = 0

        reserved = {s.linked_id for s in self.states.values()
                    if s.linked_id and time_ms - s.last_seen <= self.reservation_ms}
        active = [k for k in keys if not self.states[k].linked_id and k in feature_scores]
        proposals = {}
        if active and self.ids:
            matrix = np.stack([feature_scores[k] for k in active])
            cost = np.full((len(active), len(self.ids) + len(active)), 1e6)
            cost[:, len(self.ids):] = 1.0  # Explicit unmatched options, never force a gallery identity.
            for i in range(len(active)):
                order = np.argsort(matrix[i])[::-1]
                j = int(order[0])
                score = float(matrix[i, j])
                second = float(matrix[i, order[1]]) if len(order) > 1 else -1.
                rivals = [float(matrix[r, j]) for r in range(len(active)) if r != i]
                column_gap = score - max(rivals) if rivals else 2.
                if (self.ids[j] not in reserved and score >= self.threshold and
                        score - second >= self.margin and column_gap >= self.margin):
                    cost[i, j] = 1 - score
            rows, cols = linear_sum_assignment(cost)
            proposals = {active[i]: self.ids[j] for i, j in zip(rows, cols)
                         if j < len(self.ids) and cost[i, j] < 1.0}

        for key in active:
            state = self.states[key]
            pid = proposals.get(key)
            scores = feature_scores[key]
            state.score = float(scores.max()) if len(scores) else None
            if pid is None:
                state.proposal, state.supports = None, 0
                continue
            if state.proposal == pid and time_ms - state.last_support <= self.max_gap:
                state.supports += 1
            else:
                state.proposal, state.supports = pid, 1
            state.last_support = time_ms
            if state.supports >= self.confirmations:
                state.linked_id = pid
                state.proposal, state.supports = None, 0

        output = {}
        for key in keys:
            output[key] = self.public(key)
            if output[key] != before[key]:
                self.transitions.append(dict(time_ms=time_ms, track_id=key,
                                             previous=before[key], current=output[key]))
        return output
