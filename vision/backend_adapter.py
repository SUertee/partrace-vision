"""Convert existing A/B algorithms into immutable Backend facts, preserving uncertainty."""
import argparse
import hashlib
import importlib.util
import json
import math
from pathlib import Path
import shutil

from .worker_protocol import API_VERSION, ID, PERSON_ID, MAX_MEDIA, WorkerFailure, private_json, read_json, valid_job
from .gallery_publication import completed_archive

VISION_ROOT = Path(__file__).resolve().parent


def _path(value):
    if not isinstance(value, str) or not value:
        raise WorkerFailure('config_mismatch')
    return (VISION_ROOT / value).resolve()


def load_settings(path, check_runtime=False):
    value = read_json(path)
    if (not isinstance(value, dict) or value.get('schema_version') != 1
            or value.get('device') not in ('cpu', 'cuda') or not isinstance(value.get('cameras'), dict)
            or not value['cameras']):
        raise WorkerFailure('camera_not_configured')
    runtime = _path(value.get('runtime_config'))
    if not runtime.is_file():
        raise WorkerFailure('config_mismatch')
    value['runtime_config'] = str(runtime)
    value['artifacts_root'] = str(_path(value.get('artifacts_root', 'outputs/worker_jobs')))
    for camera_id, camera in value['cameras'].items():
        if (not isinstance(camera_id, str) or not ID.fullmatch(camera_id) or not isinstance(camera, dict)
                or camera.get('role') not in ('enrollment', 'shelf')
                or not isinstance(camera.get('config_version'), str)):
            raise WorkerFailure('camera_not_configured')
        if camera['role'] == 'enrollment':
            profile_root = _path(camera.get('enrollment_root'))
            # ProfileStore keeps a registry as well as its gallery; both need their own root.
            if profile_root == VISION_ROOT or profile_root.is_relative_to(VISION_ROOT / 'data/person_enrollment'):
                raise WorkerFailure('config_mismatch')
            camera['enrollment_root'] = str(profile_root)
        else:
            if camera.get('calibration') is not None:
                calibration = _path(camera['calibration'])
                data = read_json(calibration, MAX_MEDIA)
                if (data.get('camera_id') != camera.get('calibration_camera_id')
                        or data.get('shelf_calibration', {}).get('source') not in ('manual', 'manual_image')
                        or not data.get('shelf_zones') or not isinstance(camera.get('region_to_zone'), dict)
                        or not isinstance(camera.get('session_zone_id'), str)
                        or set(camera['region_to_zone']) != {z['id'] for z in data['shelf_zones']}):
                    raise WorkerFailure('calibration_invalid')
                camera['calibration'] = str(calibration)
            # Live B only consumes committed pointer snapshots; a mutable A archive is forbidden.
            if 'gallery' not in camera and 'gallery_root' not in camera:
                raise WorkerFailure('gallery_missing')
            if camera.get('gallery') is not None:
                raise WorkerFailure('gallery_requires_committed_pointer')
            camera['gallery_root'] = str(_path(camera['gallery_root'])) if camera.get('gallery_root') else None
    if check_runtime:
        for module in ('cv2', 'numpy', 'torch', 'torchvision', 'ultralytics', 'lap', 'PIL', 'yaml'):
            if importlib.util.find_spec(module) is None:
                raise WorkerFailure('runtime_dependency_missing')
        parameters = read_json(runtime)
        needs_osnet = any(c['role'] == 'enrollment' or c.get('gallery_root') for c in value['cameras'].values())
        weights = [VISION_ROOT / 'models/yolo26n-pose.pt']
        if needs_osnet:
            weights.append(_path(parameters.get('osnet', {}).get('weights', 'models/reid/osnet_x0_5_msmt17.pth')))
        if any(not weight.is_file() for weight in weights):
            raise WorkerFailure('weights_missing')
        from .station_vision.runtime_parameters import load_parameters
        try:
            load_parameters(VISION_ROOT, str(runtime))
        except ValueError:
            raise WorkerFailure('config_mismatch') from None
    return value


def identity(prefix, job_id, local_id):
    return prefix + hashlib.sha256((job_id + ':' + local_id).encode()).hexdigest()[:40]


def shelf_facts(job, camera, sessions, details, algorithm_output, output):
    """A shelf visit remains a candidate. Local P track IDs never become global G IDs."""
    events, evidence = [], []
    for detail in details:
        session = detail['session']
        if detail.get('review', {}).get('opinion', {}).get('interaction_detected') is False:
            continue
        region = session.get('region_id')
        if region is not None and region not in camera['region_to_zone']:
            raise WorkerFailure('calibration_invalid')
        zone_id = camera['region_to_zone'][region] if region is not None else camera['session_zone_id']
        start, end = round(session['start_ms']), round(session['end_ms'])
        if not 0 <= start <= end <= job['duration_ms']:
            raise WorkerFailure('duration_mismatch')
        event_id = identity('evt_', job['job_id'], session['id'])
        association = session.get('identity') or {}
        person_id = association.get('profile_id')
        opinion = (detail.get('review') or {}).get('opinion') or {}
        if (association.get('status') != 'matched' or not isinstance(person_id, str)
                or not PERSON_ID.fullmatch(person_id)
                or opinion.get('person_id', session['person_id']) != session['person_id']):
            person_id = None
        summary = {'algorithm': 'station_vision.shelf_session', 'candidate': True,
            'local_track': session['person_id'], 'shelf_level': session.get('shelf_level'),
            'review_status': detail.get('review', {}).get('status', 'not_requested'),
            'pickup_detected': None, 'identity_human_verified': False,
            'boundary_uncertain': session.get('boundary_uncertain', True),
            'unique_source_frames': len({f.get('source_frame_index', f['actual_ms']) for f in detail['keyframes']}),
            'context_truncated': any(f.get('truncated', False) for f in detail['keyframes'])}
        # Preserve the six-field candidate as detail, without translating G into track identity.
        summary['six_fields'] = {'id': session['id'], 'person_id': person_id,
            'start_ms': start, 'end_ms': end, 'action': 'shelf_interaction',
            'shelf_level': session.get('shelf_level')}
        events.append({'event_id': event_id, 'zone_id': zone_id,
            'track_key': session['person_id'], 'person_id': person_id, 'event_type': 'shelf_interaction',
            'observation_status': 'candidate', 'start_offset_ms': start, 'end_offset_ms': end,
            'detection_source': 'automatic', 'summary': json.dumps(summary, ensure_ascii=False, separators=(',', ':'))})
        for i, frame in enumerate(detail['keyframes']):
            source = (algorithm_output / frame['raw_path']).resolve()
            if not source.is_relative_to(algorithm_output.resolve()) or source.is_symlink() or not source.is_file():
                raise WorkerFailure('invalid_output')
            offset = round(frame['actual_ms'])
            if not 0 <= offset <= job['duration_ms']:
                raise WorkerFailure('duration_mismatch')
            name = f'{event_id}_{i + 1}.png'
            shutil.copyfile(source, output / name)
            evidence.append({'evidence_id': identity('evd_', job['job_id'], session['id'] + ':' + str(i)),
                'event_id': event_id, 'offset_ms': offset, 'bbox_json': None, 'file_name': name})
    return events, evidence


def run(settings, source, config, job, output, enrollment_stage=None):
    valid_job(job)
    camera = settings['cameras'].get(job['camera_id'])
    if not camera:
        raise WorkerFailure('camera_not_configured')
    if camera.get('role') == 'shelf' and not camera.get('calibration'):
        raise WorkerFailure('calibration_invalid')
    try:
        backend_camera = next(c for c in config['cameras'] if c['camera_id'] == job['camera_id'])
    except (KeyError, StopIteration):
        raise WorkerFailure('config_mismatch') from None
    if (config.get('config_version') != job['config_version']
            or camera['config_version'] != job['config_version']):
        raise WorkerFailure('config_mismatch')
    if camera['role'] == 'enrollment':
        stage = Path(enrollment_stage).resolve() if enrollment_stage else None
        marker = read_json(output / 'enrollment_stage.json') if stage else {}
        if (stage != (output / 'enrollment-staging').resolve() or not stage.is_dir()
                or marker.get('job_id') != job['job_id'] or marker.get('attempts') != job.get('attempts')
                or marker.get('camera_id') != job['camera_id'] or marker.get('config_version') != job['config_version']
                or marker.get('profile_root') != str(stage)
                or marker.get('publication_root') != str(Path(camera['enrollment_root']).resolve())):
            raise WorkerFailure('gallery_staging_invalid')
    from .station_vision.video import VideoSource
    from .station_vision.runtime_parameters import load_parameters
    probe = VideoSource(source, 1)
    actual_duration = probe.frame_count / probe.fps * 1000
    probe.cap.release()
    if not math.isfinite(actual_duration) or abs(actual_duration - job['duration_ms']) > max(100, 1000 / probe.fps):
        raise WorkerFailure('duration_mismatch')
    parameters = load_parameters(VISION_ROOT, settings['runtime_config'])
    algorithm_output = output / 'algorithm'
    if camera['role'] == 'enrollment':
        from .station_vision.reid.enroll_video import run as enroll
        enroll(VISION_ROOT, source, algorithm_output, settings['device'],
            runtime_config=settings['runtime_config'], profile_root=stage)
        # Enrollment has no supported zone-action claim. Its gallery and source evidence remain local.
        events, evidence = [], []
    else:
        zones = {z['zone_id'] for z in backend_camera['zones']}
        if camera['session_zone_id'] not in zones or any(zone not in zones for zone in camera['region_to_zone'].values()):
            raise WorkerFailure('config_mismatch')
        from .station_vision.shelf_session.observer import observe, save_json
        from .station_vision.shelf_session.detector import SessionSettings, detect_sessions
        from .station_vision.shelf_session.pipeline import extract, basic_and_details, review_once
        from .station_vision.reid.session_identity import resolve_session_identities
        calibration = read_json(camera['calibration'], MAX_MEDIA)
        calibration.update(video=str(source), camera_id=job['camera_id'], runtime_parameters=parameters)
        gallery_root = camera.get('gallery_root')
        if gallery_root:
            # Snapshot under the job directory; B never writes to the enrolled archive.
            snapshot = output / 'gallery_snapshot.json'
            shutil.copyfile(completed_archive(gallery_root), snapshot)
            calibration['person_archive_path'] = str(snapshot)
        algorithm_output.mkdir()
        try:
            source, rows, info = observe(VISION_ROOT, calibration, algorithm_output, settings['device'])
        except ValueError as exc:
            # The existing observer explicitly rejects a scene/reference with zero valid registrations.
            if '配准' in str(exc) or '标定' in str(exc):
                raise WorkerFailure('calibration_invalid') from None
            raise
        if not info.get('registration_valid_frames') or not any(row.get('registration_valid') for row in rows):
            raise WorkerFailure('calibration_invalid')
        sessions = detect_sessions(rows, SessionSettings(**parameters['interaction']), parameters)
        if gallery_root:
            resolve_session_identities(rows, sessions, parameters['b_session'])
        plans = extract(source, rows, sessions, algorithm_output)
        reviews = {s['id']: review_once(None, s, plans[s['id']], rows, algorithm_output,
            settings=parameters['vlm']) for s in sessions}
        six_fields, details = basic_and_details(sessions, plans, reviews)
        save_json(algorithm_output / 'events.json', six_fields)
        save_json(algorithm_output / 'event_details.json', details)
        save_json(algorithm_output / 'summary.json', {'status': 'completed', **info,
            'review_requested': False, 'identity_human_verified': False})
        events, evidence = shelf_facts(job, camera, sessions, details, algorithm_output, output)
    recording = {key: job[key] for key in ('recording_id', 'camera_id', 'config_version', 'duration_ms')}
    recording.update(source_mode='sdk', started_at=None, file_name='source.mp4')
    private_json(output / 'manifest.json', {'schema_version': API_VERSION, 'recording': recording,
        'events': events, 'evidence': evidence})


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('worker-config', 'input', 'config', 'job', 'output'):
        parser.add_argument('--' + name, required=True, type=Path)
    parser.add_argument('--enrollment-stage', type=Path)
    args = parser.parse_args(argv)
    try:
        settings = load_settings(args.worker_config, check_runtime=True)
        run(settings, args.input.resolve(), read_json(args.config), read_json(args.job), args.output.resolve(), args.enrollment_stage)
        return 0
    except Exception as exc:
        code = exc.code if isinstance(exc, WorkerFailure) else (
            'runtime_dependency_missing' if isinstance(exc, ImportError) else 'algorithm_failed')
        private_json(args.output / 'adapter_error.json', {'code': code})
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
