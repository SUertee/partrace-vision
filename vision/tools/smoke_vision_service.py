"""Real A/B algorithms via notification routes; isolated gallery, fixture metadata.

No production writes, no model API calls. Not an end-to-end Backend acceptance.
"""
import argparse
from datetime import datetime
import json
import math
import os
from pathlib import Path
import shutil
import time

import cv2
from fastapi.testclient import TestClient

from station_vision.reid.profile_store import atomic_json, digest
from station_vision.service.app import create_app
from station_vision.service.config import load_config
from station_vision.service.runner import VisionRunner


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--device', choices=['cpu', 'cuda'], default='cuda')
    parser.add_argument('--video-a', default='test_video_new/site_20261016/A/2_input.mp4')
    parser.add_argument('--video-b', default='test_video_new/site_20261016/B/1_package.mp4')
    parser.add_argument('--calibration', default='')
    parser.add_argument('--left2right-flag', choices=['true','false'], default='true')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    run = 'vision_service_smoke_' + datetime.now().strftime('%Y%m%d_%H%M%S_%f')
    folder = root / 'outputs' / run
    folder.mkdir(parents=True)
    temp = root / '.runtime' / run
    temp.mkdir()
    values = json.loads((root / 'configs/vision_service.json').read_text(encoding='utf-8'))
    values.update(device=args.device, state_dir=str(temp / 'state'), archive_dir=str(temp / 'person_enrollment'), review_with_qwen=False)
    values['backend']['media_root'] = str(temp / 'media')
    if args.calibration:
        values['b_calibration']['path'] = args.calibration
    master = root / 'data/person_enrollment'
    before = digest(master / 'archive.json') if (master / 'archive.json').exists() else None
    if master.exists():
        shutil.copytree(master, temp / 'person_enrollment', ignore=shutil.ignore_patterns('archive.lock'))
        copied = temp / 'person_enrollment/archive.json'
        if copied.exists():
            value = json.loads(copied.read_text(encoding='utf-8-sig'))
            def relocate(obj):
                if isinstance(obj, dict):
                    return {k: relocate(v) for k, v in obj.items()}
                if isinstance(obj, list):
                    return [relocate(v) for v in obj]
                if isinstance(obj, str) and obj.startswith(str(master) + os.sep):
                    return str(temp / 'person_enrollment') + obj[len(str(master)):]
                return obj
            atomic_json(copied, relocate(value))
    records = {}
    for role, source, camera in (('A', args.video_a, 'cam_A'), ('B', args.video_b, 'cam_B')):
        source = (root / source).resolve()
        cap = cv2.VideoCapture(str(source))
        try:
            fps, frames = cap.get(cv2.CAP_PROP_FPS), cap.get(cv2.CAP_PROP_FRAME_COUNT)
            if not cap.isOpened() or fps <= 0 or frames <= 0:
                raise ValueError('Cannot decode source: ' + str(source))
        finally:
            cap.release()
        key = 'objects/' + role + '.mp4'
        destination = temp / 'media' / key
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, destination)
        records['smoke_' + role] = {'recording_id': 'smoke_' + role, 'camera_id': camera,
            'config_version': 'fixture_only', 'storage_key': key, 'media_status': 'available',
            'duration_ms': math.ceil(frames / fps * 1000)}
    atomic_json(temp / 'service.json', values)
    cfg = load_config(root, str(temp / 'service.json'))
    class FixtureBackend:
        def get_recording(self, recording_id):
            return records[recording_id]
    runner = VisionRunner(cfg, FixtureBackend())
    started = time.perf_counter()
    with TestClient(create_app(cfg, runner)) as client:
        a = client.post('/vision/v1/camera-a/jobs',
            json={'recording_id': 'smoke_A', 'camera_id': 'cam_A', 'left2right_flag': args.left2right_flag=='true'})
        b = client.post('/vision/v1/camera-b/jobs',
            json={'recording_id': 'smoke_B', 'camera_id': 'cam_B'})
        a.raise_for_status(); b.raise_for_status()
        aid, bid = a.json()['job_id'], b.json()['job_id']
        assert b.json()['depends_on_a_jobs'] == [aid], 'B did not capture A dependency'
        replay = client.post('/vision/v1/camera-a/jobs',
            json={'recording_id': 'smoke_A', 'camera_id': 'cam_A', 'left2right_flag': args.left2right_flag=='true'}).json()
        assert replay['job_id'] == aid and replay['created'] is False
        while True:
            states = [client.get('/vision/v1/jobs/' + j).json() for j in (aid, bid)]
            print('A=' + states[0]['status'] + ' B=' + states[1]['status'], flush=True)
            if all(j['status'] in ('completed', 'failed', 'blocked', 'interrupted') for j in states):
                break
            if time.perf_counter() - started > values['job_timeout_s'] + 120:
                raise TimeoutError('Smoke test timed out')
            time.sleep(5)
        packages = {}
        for job in states:
            if job['status'] == 'completed':
                response = client.get('/vision/v1/jobs/' + job['job_id'] + '/result')
                response.raise_for_status()
                packages[job['role']] = response.json()
    after = digest(master / 'archive.json') if (master / 'archive.json').exists() else None
    report = {'status': 'completed' if all(j['status'] == 'completed' for j in states) else 'failed',
        'backend': 'fixture_metadata_with_storage_key; not production API integration',
        'real_algorithms': True, 'device': args.device, 'model_api_calls': 0,
        'master_archive_unchanged': before == after, 'archive_sha256_before': before, 'archive_sha256_after': after,
        'B_waited_for_A': b.json()['depends_on_a_jobs'] == [aid], 'duplicate_notification_reused_job': not replay['created'],
        'jobs': states, 'elapsed_s': round(time.perf_counter() - started, 3),
        'semantic_accuracy_measured': False}
    for role, package in packages.items():
        atomic_json(folder / (role + '_result_package.json'), package)
    atomic_json(folder / 'report.json', report)
    print(json.dumps(report, ensure_ascii=False, indent=2), flush=True)
    print('SERVICE_SMOKE_OUTPUT ' + str(folder), flush=True)
    if report['status'] != 'completed' or not report['master_archive_unchanged']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
