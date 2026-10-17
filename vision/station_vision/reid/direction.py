"""A direction is observed horizontal movement; no doorway line or ROI required."""
from collections import defaultdict
from statistics import median


def movement_events(rows, width, settings, left2right_flag):
    if type(left2right_flag) is not bool:
        raise ValueError('left2right_flag must be a JSON boolean')
    if width <= 0:
        raise ValueError('Invalid source width')
    tracks = defaultdict(list)
    for row in rows:
        for person in row['people']:
            box = person['box']
            if box[2] <= box[0] or box[3] <= box[1]:
                continue
            tracks[person['track_id']].append({'time_ms': row['time_ms'],
                'frame_id': f"f{row['source_frame_index']:06d}", 'x': (box[0] + box[2]) / (2 * width),
                'profile_id': person.get('profile_id')})
    events, diagnostics = [], []
    threshold = settings['minimum_displacement_ratio']
    for track_id, points in tracks.items():
        chunks = [[]]
        for point in points:
            if chunks[-1]:
                gap = point['time_ms'] - chunks[-1][-1]['time_ms']
                if gap <= 0:
                    raise ValueError('Direction observations must have increasing source times')
                if gap > settings['max_gap_ms']:
                    chunks.append([])
            chunks[-1].append(point)
        count = 0
        for chunk in chunks:
            smoothed = []
            left, right = 0, 0
            for i, p in enumerate(chunk):
                while p['time_ms'] - chunk[left]['time_ms'] > settings['smooth_window_ms']:
                    left += 1
                while right+1 < len(chunk) and chunk[right+1]['time_ms']-p['time_ms'] <= settings['smooth_window_ms']:
                    right += 1
                smoothed.append({**p, 'x': median(q['x'] for q in chunk[left:right+1])})
            if not smoothed:
                continue
            pivot, peak, direction = 0, 0, 0
            segments = []
            def supported(a, b):
                return (b-a+1 >= settings['minimum_samples'] and
                        smoothed[b]['time_ms']-smoothed[a]['time_ms'] >= settings['stable_ms'])
            for i, p in enumerate(smoothed):
                if direction == 0:
                    dx = p['x'] - smoothed[pivot]['x']
                    if abs(dx) >= threshold and supported(pivot, i):
                        direction = 1 if dx > 0 else -1
                        peak = i
                elif direction * (p['x'] - smoothed[peak]['x']) >= 0:
                    peak = i
                elif direction * (smoothed[peak]['x'] - p['x']) >= threshold and supported(peak, i):
                    segments.append((pivot, peak, direction))
                    pivot, peak, direction = peak, i, -direction
            if direction and supported(pivot, peak):
                segments.append((pivot, peak, direction))
            for a, b, sign in segments:
                start, finish = smoothed[a], smoothed[b]
                ids = {p['profile_id'] for p in smoothed[a:b+1] if p.get('profile_id')}
                ltr = sign == 1
                events.append({'id': f'a{len(events)+1:04d}',
                    'person_id': next(iter(ids)) if len(ids) == 1 else None,
                    'track_key': track_id, 'event_type': 'enter_zone' if ltr == left2right_flag else 'leave_zone',
                    'direction': 'left_to_right' if ltr else 'right_to_left', 'left2right_flag': left2right_flag,
                    'start_ms': round(start['time_ms']), 'end_ms': round(finish['time_ms']),
                    'start_x_ratio': round(start['x'], 5), 'end_x_ratio': round(finish['x'], 5),
                    'displacement_ratio': round(finish['x']-start['x'], 5),
                    'source_frame_ids': [start['frame_id'], finish['frame_id']],
                    'direction_basis': 'same_track_horizontal_movement_user_mapping_no_line',
                    'observation_status': 'candidate', 'identity_human_verified': False})
                count += 1
        diagnostics.append({'track_key': track_id, 'direction_event_count': count,
            'status': 'horizontal_movement_observed' if count else 'no_reliable_horizontal_movement',
            'note': 'Stationary/vertical motion, jitter and movement hidden by a tracking gap do not imply entry/exit'})
    events.sort(key=lambda e: (e['start_ms'], e['track_key']))
    return events, diagnostics
