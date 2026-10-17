"""Offline Florence localization -> KLT -> wrist/shelf candidates, on source frames."""
from collections import Counter, defaultdict
import json
from pathlib import Path
import time

import cv2
import numpy as np

from ..models import PoseTracker
from ..registration import FrameRegistration
from ..video import VideoSource
from ..hand_object.tracking import background_motion
from ..hand_object.motion import MotionJudge, edge_distance
from ..interaction.candidate import CandidateMachine, Settings
from .probe import FlorenceProbe
from .tracking import FlorenceKLTBank
from .shelf import ShelfState


def wrist_points(people):
    return [(p['id'], hand, p['keypoints'][ki][:2]) for p in people
            for ki, hand in ((9, 'left'), (10, 'right')) if p['keypoints'][ki][2] >= .35]


def actor_crop(person, width, height):
    a, b, c, d = person['box']
    margin = max(100, .35 * (c - a))
    points = [p[:2] for p in person['keypoints'][9:11] if p[2] >= .35]
    if points:
        a = min(a, min(p[0] for p in points))
        c = max(c, max(p[0] for p in points))
        b = min(b, min(p[1] for p in points))
        d = max(d, max(p[1] for p in points))
    return [max(0, int(a - margin)), max(0, int(b - margin)),
            min(width, int(c + margin)), min(height, int(d + margin))]


def source_boxes(result, crop, width, height, wrists, person_hint=None, max_area=.18):
    """Translate crop pixels to source pixels; text never determines actor ownership."""
    kept = []
    for item in result['boxes']:
        label = item['label'].lower()
        if label in ('backpack', 'handbag', 'bag', 'bags', 'person', 'human face', 'cabinet', 'cabinetry', 'hands', 'hand', 'face'):
            continue
        if not any(word in label for word in ('box', 'parcel', 'package', 'mail', 'envelope', 'bag')):
            continue
        box = np.asarray(item['box'], float) + np.tile(crop[:2], 2)
        box[[0, 2]] = np.clip(box[[0, 2]], 0, width)
        box[[1, 3]] = np.clip(box[[1, 3]], 0, height)
        bw, bh = box[2:] - box[:2]
        if min(bw, bh) < 8 or bw * bh > width * height * max_area or max(bw / bh, bh / bw) > 4:
            continue
        diagonal = max(float(np.hypot(bw, bh)), 1)
        distances = [(pid, edge_distance(point, box) / diagonal) for pid, _, point in wrists]
        priority = min((distance for _, distance in distances), default=1e6)
        if person_hint is not None and min((dist for pid, dist in distances if pid == person_hint), default=1e6) > .6:
            continue
        kept.append({'box': box.round(2).tolist(), 'label': item['label'],
                     'task': result['task_name'], 'priority': priority, 'person_hint': person_hint})
    return kept


def percentile(values):
    return {'count': len(values), 'mean_ms': round(float(np.mean(values)), 3),
            'p95_ms': round(float(np.percentile(values, 95)), 3)} if values else {'count': 0}


def related_shelf_changes(candidate, rows):
    """Associate visible changes by time AND region; never infer parcel removal."""
    window = [r for r in rows if candidate['clip_start_ms'] <= r['time_ms'] <= candidate['clip_end_ms']]
    zone_ids = {candidate['target_id']} if candidate['target_type'] == 'region' else set()
    if candidate['target_type'] == 'parcel':
        for row in window:
            parcel = next((p for p in row['parcels'] if p['id'] == candidate['target_id'] and p['box'] is not None), None)
            if parcel is None:
                continue
            a, b, c, d = np.rint(parcel['box']).astype(int)
            if c <= a or d <= b:
                continue
            for zone in row.get('shelf_polygons', []):
                # Raster intersection supports concave shelf polygons too.
                mask = np.zeros((d - b, c - a), np.uint8)
                polygon = np.rint(np.asarray(zone['polygon']) - [a, b]).astype(np.int32)
                cv2.fillPoly(mask, [polygon], 1)
                if mask.any():
                    zone_ids.add(zone['id'])
    return list(dict.fromkeys(s['frame_id'] for row in window for s in row.get('shelf_state', [])
        if s['region_id'] in zone_ids and s['status'] == 'appearance_changed'))


def observe_florence(root, config, output, device):
    from ..interaction.pipeline import save_json
    cfg = config['florence_klt']
    if config.get('parcel_seeds'):
        raise ValueError('Florence mode initializes parcels automatically; remove manual seeds')
    if cfg.get('scene_refresh_ms', 3000) <= 0 or cfg.get('actor_refresh_ms', 1500) <= 0:
        raise ValueError('Localization intervals must be positive')
    source = (root / config['video']).resolve()
    src = VideoSource(source, 120)
    if config.get('shelf_calibration'):
        from ..shelf_calibration.manual import verify_scene_calibration
        verify_scene_calibration(config,source,src.width,src.height)
    if src.fps > 120:
        raise ValueError('Source FPS above supported source-frame export rate')
    zones = config.get('shelf_zones') or ([{'id': 'shelf-region', 'level': None,
             'polygon': config['region_polygon']}] if config.get('region_polygon') else [])
    if any(z.get('level') is not None and (isinstance(z['level'], bool) or not isinstance(z['level'], int)
            or not 1 <= z['level'] <= 20) for z in zones):
        raise ValueError('Use bottom-up shelf levels, or null when uncalibrated')
    registration = FrameRegistration(enabled=bool(zones))
    shelf = ShelfState(zones)
    bank = FlorenceKLTBank(max_tracks=int(cfg.get('max_tracks', 8)),
        max_age_ms=float(cfg.get('max_localization_age_ms', 4000)))
    pose = PoseTracker(root, dict(weights='models/yolo26n-pose.pt', device=device,
        imgsz=int(config.get('imgsz', 640)), detection_conf=.1), src.fps)
    localizer = FlorenceProbe(root, cfg.get('device') or device,
        int(cfg.get('cpu_threads', 4)), cfg.get('variant', 'base-ft'))
    machine = CandidateMachine(Settings(**config.get('trigger', {})))
    directory = output / 'localization'
    (directory / 'inputs').mkdir(parents=True)
    rows, people_map, judges, calls = [], {}, {}, []
    timings = defaultdict(list)
    bases = Counter()
    previous_gray, previous_people, previous_time = None, [], None
    next_scene, actor_due, last_loss_retry = 0, {}, -1e9
    started = time.perf_counter()
    max_seconds = float(cfg.get('max_seconds', 0))
    if max_seconds < 0:
        raise ValueError('max_seconds cannot be negative')
    limit_reached = False

    def locate(raw, index, pts, crop, name, text, wrists, hint=None):
        nonlocal limit_reached
        if len(calls) >= int(cfg.get('max_localization_calls', 80)):
            limit_reached = True
            return []
        a, b, c, d = crop
        image = raw[b:d, a:c]
        call_id = f'l{len(calls) + 1:04d}'
        path = directory / 'inputs' / (call_id + '.png')
        ok, encoded = cv2.imencode('.png', image)
        if not ok:
            raise RuntimeError('Cannot preserve localization input crop')
        path.write_bytes(encoded.tobytes())
        record = {'id': call_id, 'frame_id': f'f{index:06d}', 'time_ms': round(pts, 3),
                  'crop_xyxy_source': crop, 'person_hint': hint, 'task': name,
                  'input_path': str(path.relative_to(output)).replace('\\', '/')}
        tick = time.perf_counter()
        try:
            result = localizer.run(image, name, text_input=text)
            record.update(status=result['status'], raw_text=result['raw_text'],
                          parsed_boxes=result['boxes'], text_input=result['text_input'])
            detections = source_boxes(result, crop, src.width, src.height, wrists, hint)
            record['source_boxes'] = detections
            if result['status'] != 'completed':
                detections = []  # A truncated generation cannot calibrate a track.
        except Exception as error:
            record.update(status='failed', error=str(error))
            detections = []
        record['elapsed_ms'] = round((time.perf_counter() - tick) * 1000, 3)
        timings['florence'].append(record['elapsed_ms'])
        calls.append(record)
        with (directory / 'calls.jsonl').open('a', encoding='utf-8') as stream:
            stream.write(json.dumps(record, ensure_ascii=False) + '\n')
        return detections

    for index, pts, basis, raw in src:
        if max_seconds and pts >= max_seconds * 1000 - 1e-5:
            break
        tick_frame = time.perf_counter()
        calls_before = len(calls)
        gray = cv2.cvtColor(raw, cv2.COLOR_BGR2GRAY)
        tick = time.perf_counter()
        people = []
        for p in pose.process(raw, config.get('camera_id', 'cam01')):
            if p.track_id and len(p.keypoints) >= 11:
                people_map.setdefault(p.track_id, f'P{len(people_map) + 1:03d}')
                people.append({'id': people_map[p.track_id], 'tracklet_id': p.track_id,
                               'box': p.bbox, 'keypoints': p.keypoints})
        timings['pose'].append((time.perf_counter() - tick) * 1000)
        wrists = wrist_points(people)
        gap = previous_time is not None and pts - previous_time > 250
        tick = time.perf_counter()
        bank.advance(previous_gray, gray, pts, 'source_time_gap' if gap else None)
        timings['klt'].append((time.perf_counter() - tick) * 1000)
        tick = time.perf_counter()
        aligned, ref_to_source, quality = registration.process(raw)
        source_to_ref = np.linalg.inv(ref_to_source) if quality['valid'] else np.eye(3)
        current_zones = []
        if quality['valid']:
            for zone in zones:
                polygon = cv2.perspectiveTransform(np.asarray(zone['polygon'], np.float32).reshape(-1, 1, 2),
                                                   ref_to_source).reshape(-1, 2)
                current_zones.append({**zone, 'source_polygon': polygon})
        bg = None if previous_gray is None or zones else background_motion(previous_gray, gray, previous_people,
                                                                           [t['flow'] for t in bank.tracks.values()])
        visible = np.full_like(gray, 255)
        for p in people:
            a, b, c, d = np.rint(p['box']).astype(int)
            cv2.rectangle(visible, (max(0, a - 20), max(0, b - 20)),
                          (min(src.width - 1, c + 20), min(src.height - 1, d + 20)), 0, -1)
        for _, _, point in wrists:
            cv2.circle(visible, tuple(np.rint(point).astype(int)), 40, 0, -1)
        visible = cv2.warpPerspective(visible, source_to_ref, (src.width, src.height), flags=cv2.INTER_NEAREST)
        busy = {z['id'] for z in current_zones for _, _, point in wrists
                if cv2.pointPolygonTest(z['source_polygon'].astype(np.float32), tuple(map(float, point)), False) >= 0}
        states = shelf.update(aligned, visible, pts, f'f{index:06d}', quality['valid'], busy)
        timings['shelf_and_registration'].append((time.perf_counter() - tick) * 1000)
        detections = []
        if pts + 1e-5 >= next_scene:
            detections += locate(raw, index, pts, [0, 0, src.width, src.height], 'boxes',
                cfg.get('scene_text', 'cardboard boxes and parcels'), wrists)
            next_scene = pts + float(cfg.get('scene_refresh_ms', 3000))
        lost = any(not t['flow'].ok for t in bank.tracks.values())
        retry_lost = lost and pts - last_loss_retry >= float(cfg.get('loss_retry_ms', 750))
        if retry_lost:
            last_loss_retry = pts
        for p in people:
            person_wrists = [(pid, hand, point) for pid, hand, point in wrists if pid == p['id']]
            near_zone = any(cv2.pointPolygonTest(z['source_polygon'].astype(np.float32), tuple(map(float, point)), True) >= -30
                            for z in current_zones for _, _, point in person_wrists)
            near_track = any(edge_distance(point, t['flow'].box) / max(np.linalg.norm(t['flow'].box[2:] - t['flow'].box[:2]), 1) < .6
                             for _, _, point in person_wrists for t in bank.tracks.values() if t['flow'].ok)
            if person_wrists and (near_zone or near_track) and (pts >= actor_due.get(p['id'], 0) or retry_lost):
                detections += locate(raw, index, pts, actor_crop(p, src.width, src.height), 'held',
                    cfg.get('actor_text', "the box held in the person's hands"), wrists, p['id'])
                actor_due[p['id']] = pts + float(cfg.get('actor_refresh_ms', 1500))
        if detections:
            bank.correct(gray, detections, pts)
        parcels = bank.observations(pts)
        relations, measurements = [], []
        for parcel in parcels:
            if parcel['box'] is None:
                continue
            box = np.asarray(parcel['box'])
            diagonal = max(float(np.linalg.norm(box[2:] - box[:2])), 1)
            near_ids = {pid for pid, _, point in wrists if edge_distance(point, box) / diagonal <= .4}
            parcel['near_person_ids'] = sorted(near_ids)
            parcel['ambiguous_actor'] = len(near_ids) > 1
            if near_ids:
                bank.tracks[parcel['id']]['last_near_ms'] = pts
            for pid, hand, point in wrists:
                distance = edge_distance(point, box) / diagonal
                if parcel['valid'] and not parcel['ambiguous_actor']:
                    relations.append({'person_id': pid, 'target_id': parcel['id'], 'target_type': 'parcel',
                        'hand': hand, 'distance': float(distance), 'wrist': point,
                        'nearest': np.clip(point, box[:2], box[2:]).tolist()})
                key = (pid, parcel['id'], hand)
                judge = judges.setdefault(key, MotionJudge())
                registered = quality['valid'] and bool(zones)
                measured_box, measured_wrist = box, np.asarray(point)
                if registered:
                    a, b, c, d = box
                    corners = np.float32([[a, b], [c, b], [c, d], [a, d]])
                    corners = cv2.perspectiveTransform(corners.reshape(-1, 1, 2), source_to_ref).reshape(-1, 2)
                    measured_box = np.r_[corners.min(axis=0), corners.max(axis=0)]
                    measured_wrist = cv2.perspectiveTransform(np.asarray(point, np.float32).reshape(1, 1, 2), source_to_ref).ravel()
                valid_motion = parcel['motion_observed'] and (registered or (not zones and bg is not None))
                measurement = judge.update(pts / 1000, measured_box, measured_wrist,
                    owner=pid + ':' + hand, tracking_ok=bool(valid_motion), ambiguous=parcel['ambiguous_actor'],
                    background_displacement=(0, 0) if registered or bg is None else bg, support_observed=False)
                if parcel['corrected_now']:
                    measurement['reason'] = 'florence_correction_is_not_motion_evidence'
                measurements.append({'person_id': pid, 'target_id': parcel['id'], 'hand': hand,
                    'coordinate_space': 'registered_reference_pixels' if registered else 'source_pixels', **measurement})
        for z in current_zones:
            for pid, hand, point in wrists:
                signed = cv2.pointPolygonTest(z['source_polygon'].astype(np.float32), tuple(map(float, point)), True)
                relations.append({'person_id': pid, 'target_id': z['id'], 'target_type': 'region',
                    'hand': hand, 'distance': 0. if signed >= 0 else 1., 'wrist': point, 'nearest': point})
        machine.update(pts, relations)
        rows.append({'frame_id': f'f{index:06d}', 'index': index, 'time_ms': round(pts, 3),
            'timestamp_basis': basis, 'people': people, 'parcels': parcels, 'relations': relations,
            'measurements': measurements, 'region_polygon': current_zones[0]['source_polygon'].round(2).tolist() if current_zones else None,
            'registration_valid': bool(quality['valid']), 'shelf_state': states,
            'shelf_polygons': [{'id': z['id'], 'level': z.get('level'), 'polygon': z['source_polygon'].round(2).tolist()} for z in current_zones],
            'parcel_mode': 'florence_klt', 'localization_ran': len(calls) > calls_before})
        bases[basis] += 1
        previous_gray, previous_people, previous_time = gray, people, pts
        timings['frame_total'].append((time.perf_counter() - tick_frame) * 1000)
        if index % max(1, round(src.fps) * 3) == 0:
            print(f'florence_klt t={pts/1000:.1f}s tracks={len(bank.tracks)} localization_calls={len(calls)} candidates={len(machine.candidates)}', flush=True)
    if not rows:
        raise ValueError('No decoded source frames')
    candidates = machine.finish(rows[-1]['time_ms'])
    exact = [c for c in candidates if c['target_type'] == 'parcel']
    for c in candidates:
        c['locator'] = 'florence2' if c['target_type'] == 'parcel' else 'registered_region'
        c['shelf_change_frame_ids'] = related_shelf_changes(c, rows)
        if c['target_type'] == 'region' and any(p['person_id'] == c['person_id'] and
            p['active_start_ms'] <= c['active_start_ms'] and p['active_end_ms'] >= c['active_end_ms'] for p in exact):
            c['suppressed_reason'] = 'covered_by_exact_parcel_candidate'
    with (output / 'observations.jsonl').open('w', encoding='utf-8') as stream:
        for row in rows:
            stream.write(json.dumps(row, ensure_ascii=False) + '\n')
    save_json(output / 'people.json', people_map)
    save_json(output / 'candidates.json', candidates)
    save_json(output / 'parcel_tracking_log.json', bank.log)
    metrics = {'base_commit': '72d0913', 'variant': cfg.get('variant', 'base-ft'),
        'repository': localizer.manifest['repository'], 'revision': localizer.manifest['revision'],
        'weight_sha256': localizer.manifest['files']['model.safetensors']['sha256'],
        'localization_device': localizer.device, 'localization_dtype': str(localizer.dtype),
        'localization_only': True, 'localization_calls': len(calls),
        'localization_failures': sum(c['status'] != 'completed' for c in calls),
        'localization_limit_reached': limit_reached, 'source_frames': len(rows),
        'parcel_track_ids': len({p['id'] for r in rows for p in r['parcels']}),
        'florence_corrections': sum(e['event'] == 'florence_corrected' for e in bank.log),
        'klt_losses': sum(e['event'] == 'klt_lost' for e in bank.log),
        'shelf_appearance_change_frames': sum(any(s['status'] == 'appearance_changed' for s in r['shelf_state']) for r in rows),
        'model_load_s': localizer.load_s, 'observation_wall_s': round(time.perf_counter() - started, 3),
        'timings': {name: percentile(samples) for name, samples in timings.items()},
        'live_stream_implemented': False, 'accuracy_evaluated': False}
    save_json(output / 'florence_klt_metrics.json', metrics)
    return source, rows, candidates, {'width': src.width, 'height': src.height, 'fps': src.fps,
        'duration_ms': rows[-1]['time_ms'] + 1000 / src.fps,
        'timestamp_basis_counts': dict(bases), 'florence_klt': metrics}
