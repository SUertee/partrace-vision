"""Small wire/file boundary shared by the HTTP worker and real algorithm adapter."""
import json
from pathlib import Path
import re

API_VERSION = '0.3-mvp-draft'
MAX_MANIFEST = 2 * 1024 * 1024
MAX_MEDIA = 64 * 1024 * 1024
PERSON_ID = re.compile(r'G[0-9]{4,99}\Z')
ID = re.compile(r'[A-Za-z0-9][A-Za-z0-9_.:-]{0,99}\Z')


class WorkerFailure(Exception):
    """Only controlled codes cross the logging/API boundary; never exception text."""
    def __init__(self, code):
        self.code = code
        super().__init__(code)


def read_json(path, limit=MAX_MANIFEST):
    path = Path(path)
    if path.is_symlink() or not path.is_file() or path.stat().st_size > limit:
        raise WorkerFailure('invalid_output')
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError('duplicate key')
            result[key] = value
        return result
    try:
        with path.open('rb') as stream:
            data = stream.read(limit + 1)
        if len(data) > limit:
            raise ValueError('too large')
        return json.loads(data.decode('utf-8-sig'), object_pairs_hook=pairs,
            parse_constant=lambda _: (_ for _ in ()).throw(ValueError('nonfinite')))
    except (OSError, ValueError, UnicodeError):
        raise WorkerFailure('invalid_output') from None


def private_json(path, value):
    path = Path(path)
    with path.open('x', encoding='utf-8') as stream:
        import os
        os.chmod(path, 0o600)
        json.dump(value, stream, ensure_ascii=False, allow_nan=False)


def valid_job(job):
    if not isinstance(job, dict):
        raise WorkerFailure('invalid_job')
    for key in ('job_id', 'recording_id', 'camera_id', 'config_version'):
        if not isinstance(job.get(key), str) or not ID.fullmatch(job[key]):
            raise WorkerFailure('invalid_job')
    if (job['recording_id'] != job['job_id'] or job.get('event_type') != 'people'
            or type(job.get('duration_ms')) is not int or job['duration_ms'] <= 0):
        raise WorkerFailure('invalid_job')
    return job


def output_bundle(folder, job, config, max_media=MAX_MEDIA):
    """Validate identity and safe evidence paths; Backend still validates all facts/media."""
    folder = Path(folder).resolve()
    manifest = read_json(folder / 'manifest.json')
    expected = {key: job[key] for key in ('recording_id', 'camera_id', 'config_version', 'duration_ms')}
    expected.update(source_mode='sdk', started_at=None, file_name='source.mp4')
    if (not isinstance(manifest, dict) or manifest.get('schema_version') != API_VERSION
            or manifest.get('recording') != expected):
        raise WorkerFailure('manifest_job_mismatch')
    try:
        camera = next(c for c in config['cameras'] if c['camera_id'] == job['camera_id'])
        zones = {z['zone_id'] for z in camera['zones']}
    except (KeyError, TypeError, StopIteration):
        raise WorkerFailure('config_mismatch') from None
    events, evidence = manifest.get('events'), manifest.get('evidence')
    if (not isinstance(events, list) or not isinstance(evidence, list)
            or len(events) > 5000 or len(evidence) > 5000):
        raise WorkerFailure('invalid_output')
    ids = set()
    for event in events:
        if not isinstance(event, dict):
            raise WorkerFailure('invalid_output')
        event_id = event.get('event_id')
        start, end = event.get('start_offset_ms'), event.get('end_offset_ms')
        if (not isinstance(event_id, str) or not ID.fullmatch(event_id) or event_id in ids
                or (event.get('person_id') is not None and (not isinstance(event['person_id'], str) or not PERSON_ID.fullmatch(event['person_id'])))
                or event.get('zone_id') not in zones
                or event.get('event_type') not in ('enter_zone', 'dwell', 'shelf_interaction', 'leave_zone')
                or event.get('observation_status') not in ('candidate', 'observed')
                or event.get('detection_source') not in ('automatic', 'manual')
                or type(start) is not int or type(end) is not int
                or not 0 <= start <= end <= job['duration_ms']):
            raise WorkerFailure('invalid_output')
        ids.add(event_id)
    files, names, frame_ids, total = [], set(), set(), 0
    for frame in evidence:
        if not isinstance(frame, dict):
            raise WorkerFailure('invalid_output')
        name, frame_id, offset = frame.get('file_name'), frame.get('evidence_id'), frame.get('offset_ms')
        if (not isinstance(frame_id, str) or not ID.fullmatch(frame_id) or frame_id in frame_ids
                or frame.get('event_id') not in ids or type(offset) is not int
                or not 0 <= offset <= job['duration_ms']):
            raise WorkerFailure('invalid_output')
        frame_ids.add(frame_id)
        if name is None:
            continue
        if (not isinstance(name, str) or not name or len(name) > 200
                or any(c in '/\\' or ord(c) < 32 for c in name)
                or name.casefold() in names or Path(name).suffix.lower() not in ('.png', '.jpg', '.jpeg')):
            raise WorkerFailure('invalid_output')
        path = folder / name
        if path.is_symlink() or path.resolve().parent != folder or not path.is_file():
            raise WorkerFailure('invalid_output')
        total += path.stat().st_size
        if total > max_media or path.stat().st_size == 0:
            raise WorkerFailure('output_too_large' if total > max_media else 'invalid_output')
        names.add(name.casefold())
        # Bounded immutable bytes avoid a later path/symlink swap during multipart upload.
        with path.open('rb') as stream:
            data = stream.read(max_media + 1)
        if len(data) != path.stat().st_size or len(data) > max_media:
            raise WorkerFailure('invalid_output')
        content_type = 'image/png' if path.suffix.lower() == '.png' else 'image/jpeg'
        files.append(('files', (name, data, content_type)))
    encoded = json.dumps(manifest, ensure_ascii=False, allow_nan=False).encode()
    if len(encoded) > MAX_MANIFEST:
        raise WorkerFailure('output_too_large')
    return [('manifest', ('manifest.json', encoded, 'application/json')), *files]
