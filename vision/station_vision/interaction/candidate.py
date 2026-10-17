"""Causal proximity trigger. Missing observations never mean a hand moved away."""
from dataclasses import dataclass


@dataclass
class Settings:
    enter: float = .25
    exit: float = .40
    dwell_ms: int = 200
    max_gap_ms: int = 150
    release_ms: int = 500
    lost_ms: int = 500
    max_active_ms: int = 6000
    context_ms: int = 1500


class CandidateMachine:
    def __init__(self, settings=None):
        self.cfg = settings or Settings()
        self.states = {}
        self.candidates = []

    def _close(self, state, time_ms, reason):
        candidate = state.get('candidate')
        if candidate:
            candidate.update(active_end_ms=round(time_ms, 3), close_reason=reason,
                             clip_end_ms=round(time_ms+self.cfg.context_ms, 3))
        state.clear()

    def update(self, time_ms, relations):
        present = {}
        for r in relations:
            key = (r['person_id'], r['target_id'])
            # Two hands on one target are one interaction, not two takes.
            if key not in present or r['distance'] < present[key]['distance']:
                present[key] = r
        for key in set(self.states) | set(present):
            state = self.states.setdefault(key, {})
            r = present.get(key)
            if r is None:
                if state and time_ms-state['last_valid'] > self.cfg.lost_ms:
                    self._close(state, state['last_valid'], 'observation_lost')
                continue
            near = r['distance'] <= self.cfg.enter
            if not state:
                if near:
                    state.update(first=time_ms, last_valid=time_ms, samples=1)
                else:
                    continue
            else:
                if 'candidate' not in state and time_ms-state['last_valid'] > self.cfg.max_gap_ms:
                    state.clear()
                    if near:
                        state.update(first=time_ms, last_valid=time_ms, samples=1)
                    continue
                state['last_valid'] = time_ms
                state['samples'] += 1
            if 'candidate' not in state:
                if not near:
                    state.clear()
                    continue
                if time_ms-state['first']+1e-5 >= self.cfg.dwell_ms and state['samples'] >= 3:
                    c = dict(id=f'c{len(self.candidates)+1:04d}', person_id=key[0],
                             target_id=key[1], target_type=r['target_type'],
                             trigger_ms=round(state['first'], 3), detected_at_ms=round(time_ms, 3),
                             active_start_ms=round(state['first'], 3), active_end_ms=None,
                             clip_start_ms=max(0, round(state['first']-self.cfg.context_ms, 3)),
                             clip_end_ms=None, close_reason=None, hands=[]) 
                    self.candidates.append(c)
                    state['candidate'] = c
            c = state.get('candidate')
            if not c:
                continue
            if r['hand'] not in c['hands']:
                c['hands'].append(r['hand'])
            if time_ms-c['active_start_ms'] >= self.cfg.max_active_ms:
                self._close(state, time_ms, 'duration_limit')
                continue
            if r['distance'] > self.cfg.exit:
                state.setdefault('far_since', time_ms)
                if time_ms-state['far_since']+1e-5 >= self.cfg.release_ms:
                    self._close(state, state['far_since'], 'hand_left')
            else:
                state.pop('far_since', None)

    def finish(self, last_ms):
        for state in self.states.values():
            if state.get('candidate'):
                self._close(state, min(last_ms, state['last_valid']), 'video_end')
        for c in self.candidates:
            c['post_truncated'] = c['clip_end_ms'] > last_ms
            c['clip_end_ms'] = min(last_ms, c['clip_end_ms'])
            c['pre_truncated'] = c['trigger_ms'] < self.cfg.context_ms
        return self.candidates
