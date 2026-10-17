"""Local event/evidence records for Backend alignment, never an upload manifest."""
from datetime import datetime, timedelta, timezone
from pathlib import Path
import uuid

from ..reid.profile_store import atomic_json, digest


def records(job, output, work, payload, default_zone_id='E3'):
    map_file = work.parent / 'id_map.json'
    import json
    id_map = json.loads(map_file.read_text(encoding='utf-8')) if map_file.exists() else {}
    def identifier(key, prefix):
        if key not in id_map:
            date = datetime.now(timezone(timedelta(hours=8))).strftime('%Y%m%d')
            id_map[key] = prefix + '_' + date + '_' + uuid.uuid4().hex[:16]
            atomic_json(map_file, id_map)
        return id_map[key]
    events, evidence = [], []
    if job['role'] == 'A':
        local_events = payload['direction_events']
        profiles = {p['track_id']: p for p in payload['profiles']}
    else:
        local_events = payload['events']
        details = {d['session']['id']: d for d in payload['details']}
    for item in local_events:
        local_id = item['id']
        event_id = identifier('event:' + local_id, 'evt')
        if job['role'] == 'A':
            summary = ('按已配置左右方向规则推定进门' if item['event_type'] == 'enter_zone' else '按已配置左右方向规则推定出门')
            track = item['track_key']
            frames = profiles.get(track, {}).get('keyframes', [])
            level, zone, event_type = None, default_zone_id, item['event_type']
        else:
            detail = details[local_id]
            opinion = detail['review'].get('opinion') or {}
            summary = opinion.get('reason') or '货架交互候选；复核未完成，是否取件未知'
            track = detail['session']['person_id']
            frames = detail['keyframes']
            level, zone, event_type = item['shelf_level'], default_zone_id, 'shelf_interaction'
        events.append({'event_id': event_id, 'recording_id': job['recording_id'], 'zone_id': zone,
            'track_key': track, 'person_id': item.get('person_id'), 'event_type': event_type, 'shelf_level': level,
            'observation_status': 'candidate', 'start_offset_ms': int(round(item['start_ms'])),
            'end_offset_ms': int(round(item['end_ms'])), 'detection_source': 'automatic', 'summary': summary[:1000]})
        for index, f in enumerate(frames):
            if job['role'] == 'A':
                current = output / 'crops' / track / (f['frame_id'] + '.png')
                image = current if current.is_file() else Path(f['archive_crop'])
                raw = output / 'evidence' / (f['frame_id'] + '.png')
                if not raw.is_file():
                    raw = Path(f.get('raw_frame_path', ''))
                offset, expected, kind = f['time_ms'], f.get('crop_sha256'), 'person_crop'
            else:
                image, raw = output / f.get('display_image_path',f['model_image_path']), output / f['raw_path']
                offset, expected, kind = f['actual_ms'], f.get('display_image_sha256',f['model_image_sha256']), 'annotated_frame'
            if not image.is_file() or (expected and digest(image) != expected):
                raise ValueError('Missing or changed selected evidence image')
            evidence.append({'evidence_id': identifier(f'evidence:{local_id}:{index}:{f["frame_id"]}', 'evd'),
                'event_id': event_id, 'offset_ms': int(round(offset)), 'bbox_json': None,
                'storage_key': str(image.resolve()), 'media_status': 'available',
                'image_kind': kind, 'raw_path': str(raw.resolve()) if raw.is_file() else None,
                'frame_id': f['frame_id']})
    return events, evidence
