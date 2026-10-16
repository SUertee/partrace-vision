"""Protocol-only fixture: generated pixels and facts are NOT real visual inference."""
import argparse
import base64
import json
import os
from pathlib import Path
import time

p = argparse.ArgumentParser()
for name in ('input', 'config', 'job', 'output'):
    p.add_argument('--' + name, type=Path, required=True)
p.add_argument('--mode', default='ok')
p.add_argument('--sleep', type=float, default=.12)
p.add_argument('--gallery-stage', type=Path)
args = p.parse_args()
if args.mode == 'fail':
    raise SystemExit(3)
if args.mode == 'check_secret' and 'PARTRACE_VISION_TOKEN' in os.environ:
    raise SystemExit(4)
job = json.loads(args.job.read_text())
if args.gallery_stage:
    gallery = args.gallery_stage / 'data/person_enrollment'
    gallery.mkdir(parents=True, exist_ok=True)
    archive = gallery / 'archive.json'
    state = json.loads(archive.read_text()) if archive.exists() else {'fixture_jobs': []}
    state['fixture_jobs'].append(job['job_id'])
    archive.write_text(json.dumps(state))
time.sleep(args.sleep)
if args.mode == 'enrollment_fail':
    raise SystemExit(5)
recording = {k: job[k] for k in ('recording_id', 'camera_id', 'config_version', 'duration_ms')}
recording.update(source_mode='sdk', started_at=None, file_name='source.mp4')
frame_name = '../escaped.png' if args.mode == 'escape' else 'frame.png'
if args.mode == 'wrong_id':
    recording['recording_id'] = 'other_job'
manifest = {'schema_version': '0.3-mvp-draft', 'recording': recording,
    'events': [{'event_id': 'fixture_event', 'zone_id': 'zone_a', 'track_key': 'P001',
        'event_type': 'shelf_interaction', 'observation_status': 'candidate',
        'start_offset_ms': 0, 'end_offset_ms': 500, 'detection_source': 'automatic',
        'summary': 'Protocol-only test fixture, not real inference'}],
    'evidence': [{'evidence_id': 'fixture_frame', 'event_id': 'fixture_event',
        'offset_ms': 100, 'bbox_json': None, 'file_name': frame_name}]}
if args.gallery_stage:
    manifest.update(events=[], evidence=[])
png = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a3x8AAAAASUVORK5CYII=')
(args.output / 'frame.png').write_bytes(png)
(args.output / 'manifest.json').write_text(json.dumps(manifest))
