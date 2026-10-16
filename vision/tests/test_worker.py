"""HTTP/process boundary tests. Fixture algorithms do not validate visual accuracy."""
import copy
import json
import os
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

import httpx

from vision.backend_adapter import load_settings, run, shelf_facts
from vision.worker import Settings, Worker
from vision.worker_protocol import WorkerFailure, output_bundle

JOB = {'job_id': 'job_1', 'recording_id': 'job_1', 'camera_id': 'camera_a',
    'config_version': 'site_v1', 'event_type': 'people', 'duration_ms': 1000,
    'sdk_event_id': 'native_1', 'sdk_timestamp_ms': None, 'status': 'processing', 'attempts': 1}
CONFIG = {'config_version': 'site_v1', 'cameras': [{'camera_id': 'camera_a',
    'name': 'Fixture camera', 'zones': [{'zone_id': 'zone_a', 'name': 'Fixture zone',
        'roi': [[0., 0.], [1., 0.], [1., 1.], [0., 1.]]}]}]}
FIXTURE = Path(__file__).parent / 'fixtures/fake_worker_algorithm.py'


class FakeBackend:
    def __init__(self, *, available=True, lose_lease=False, lose_completion=False, video=b'fixture-video-bytes'):
        self.available, self.lose_lease, self.video = available, lose_lease, video
        self.lose_completion, self.published = lose_completion, False
        self.requests, self.completions, self.failures, self.heartbeats = [], [], [], 0

    def __call__(self, request):
        self.requests.append(request)
        assert request.headers['Authorization'] == 'Bearer private-test-token'
        path = request.url.path
        if path.endswith('/claim'):
            return httpx.Response(200, json={'api_version': '0.3-mvp-draft',
                'job': JOB if self.available else None, 'lease_token': 'private-test-lease'})
        if '/configs/' in path:
            return httpx.Response(200, json={'api_version': '0.3-mvp-draft', 'config': CONFIG})
        if path.endswith('/job_1') and request.method == 'GET':
            return httpx.Response(200, json={'api_version': '0.3-mvp-draft',
                'job': {**JOB, 'status': 'completed' if self.published else 'processing'}})
        assert request.headers['X-Lease-Token'] == 'private-test-lease'
        if path.endswith('/media'):
            return httpx.Response(200, content=self.video, headers={'content-type': 'video/mp4'})
        if path.endswith('/heartbeat'):
            self.heartbeats += 1
            return httpx.Response(409 if self.lose_lease else 200,
                json={'api_version': '0.3-mvp-draft', 'job': JOB})
        if path.endswith('/complete'):
            self.completions.append(request.read())
            self.published = True
            if self.lose_completion:
                self.lose_completion = False
                raise httpx.ReadError('fixture response lost after commit', request=request)
            return httpx.Response(201, json={'api_version': '0.3-mvp-draft',
                'job': {**JOB, 'status': 'completed'}})
        if path.endswith('/fail'):
            self.failures.append(json.loads(request.read()))
            return httpx.Response(200, json={'api_version': '0.3-mvp-draft', 'job': {**JOB, 'status': 'failed'}})
        raise AssertionError(path)


class WorkerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)

    def worker(self, backend, mode='ok', **settings):
        command = [sys.executable, str(FIXTURE), '--input', '{input}', '--config', '{config}',
            '--job', '{job}', '--output', '{output}', '--mode', mode]
        value = Settings('http://127.0.0.1:8000', 'private-test-token', command,
            heartbeat_interval=.02, work_root=Path(self.temp.name), **settings)
        worker = Worker(value, transport=httpx.MockTransport(backend))
        self.addCleanup(worker.close)
        return worker

    def test_success_runs_subprocess_renews_and_uploads_only_evidence(self):
        backend = FakeBackend()
        self.assertTrue(self.worker(backend).run_once())
        self.assertGreater(backend.heartbeats, 0)
        body = backend.completions[0]
        self.assertIn(b'filename="manifest.json"', body)
        self.assertIn(b'filename="frame.png"', body)
        self.assertNotIn(b'filename="source.mp4"', body)
        self.assertNotIn(b'fixture-video-bytes', body)
        self.assertEqual(backend.failures, [])
        self.assertEqual(list(Path(self.temp.name).iterdir()), [])

    def test_no_work_does_not_run_algorithm_or_touch_media(self):
        backend = FakeBackend(available=False)
        self.assertFalse(self.worker(backend).run_once())
        self.assertEqual(len(backend.requests), 1)

    def test_lost_completion_response_checks_published_status_without_failing(self):
        backend = FakeBackend(lose_completion=True)
        self.assertTrue(self.worker(backend).run_once())
        self.assertTrue(backend.published)
        self.assertFalse(backend.failures)

    def test_plain_http_is_restricted_to_loopback(self):
        for url in ('http://example.com', 'http://10.0.0.5:8000'):
            with self.subTest(url=url), self.assertRaisesRegex(WorkerFailure, 'insecure_api_base'):
                Settings(url, 'private-test-token', [sys.executable])

    def test_unconfirmed_completion_is_not_reported_as_failure_to_backend(self):
        class UnreachableAfterUpload(FakeBackend):
            def __call__(self, request):
                if request.url.path.endswith(('/complete', '/job_1')):
                    raise httpx.ReadError('fixture network outage', request=request)
                return super().__call__(request)
        backend = UnreachableAfterUpload()
        with self.assertRaisesRegex(WorkerFailure, 'completion_unconfirmed'):
            self.worker(backend).run_once()
        self.assertFalse(backend.failures)

    def test_algorithm_artifacts_survive_temporary_download_cleanup(self):
        backend = FakeBackend()
        artifact_root = Path(self.temp.name) / 'private-artifacts'
        self.assertTrue(self.worker(backend, artifact_root=artifact_root).run_once())
        outputs = list(artifact_root.iterdir())
        self.assertEqual(len(outputs), 1)
        self.assertTrue((outputs[0] / 'manifest.json').is_file())
        self.assertEqual(list(Path(self.temp.name).iterdir()), [artifact_root])

    def test_failed_algorithm_reports_failure_without_completion(self):
        backend = FakeBackend()
        with self.assertRaisesRegex(WorkerFailure, 'algorithm_failed'):
            self.worker(backend, 'fail').run_once()
        self.assertEqual(backend.failures, [{'code': 'algorithm_failed'}])
        self.assertFalse(backend.completions)

    def test_timeout_stops_process_while_lease_renews(self):
        backend = FakeBackend()
        with self.assertRaisesRegex(WorkerFailure, 'algorithm_timeout'):
            self.worker(backend, algorithm_timeout=.06).run_once()
        self.assertTrue(backend.heartbeats)
        self.assertEqual(backend.failures, [{'code': 'algorithm_timeout'}])

    def test_lost_lease_never_completes_or_fails_another_workers_job(self):
        backend = FakeBackend(lose_lease=True)
        with self.assertRaisesRegex(WorkerFailure, 'lease_lost'):
            self.worker(backend).run_once()
        self.assertFalse(backend.completions)
        self.assertFalse(backend.failures)

    def test_path_escape_and_wrong_identity_never_upload(self):
        for mode in ('escape', 'wrong_id'):
            with self.subTest(mode=mode):
                backend = FakeBackend()
                with self.assertRaises(WorkerFailure):
                    self.worker(backend, mode).run_once()
                self.assertFalse(backend.completions)
                self.assertEqual(len(backend.failures), 1)

    def test_download_size_is_bounded(self):
        backend = FakeBackend(video=b'12345')
        with self.assertRaisesRegex(WorkerFailure, 'input_too_large'):
            self.worker(backend, max_media=4).run_once()
        self.assertFalse(backend.completions)

    def test_token_is_redacted_and_not_inherited_by_algorithm(self):
        backend = FakeBackend()
        worker = self.worker(backend, 'check_secret')
        self.assertNotIn('private-test-token', repr(worker.settings))
        with patch.dict(os.environ, {'PARTRACE_VISION_TOKEN': 'private-test-token'}):
            self.assertTrue(worker.run_once())

    def test_symlinked_evidence_is_rejected(self):
        folder = Path(self.temp.name)
        outside = folder / 'outside.png'
        outside.write_bytes(b'outside')
        output = folder / 'output'
        output.mkdir()
        (output / 'frame.png').symlink_to(outside)
        manifest = {'schema_version': '0.3-mvp-draft', 'recording': {k: JOB[k] for k in
            ('recording_id', 'camera_id', 'config_version', 'duration_ms')}, 'events': [], 'evidence': []}
        manifest['recording'].update(source_mode='sdk', started_at=None, file_name='source.mp4')
        manifest['events'] = [{'event_id': 'e1', 'zone_id': 'zone_a', 'event_type': 'shelf_interaction',
            'observation_status': 'candidate', 'detection_source': 'automatic', 'start_offset_ms': 0, 'end_offset_ms': 1}]
        manifest['evidence'] = [{'evidence_id': 'f1', 'event_id': 'e1', 'offset_ms': 0, 'file_name': 'frame.png'}]
        (output / 'manifest.json').write_text(json.dumps(manifest))
        with self.assertRaisesRegex(WorkerFailure, 'invalid_output'):
            output_bundle(output, JOB, CONFIG)


class RealAdapterMappingTests(unittest.TestCase):
    def test_shelf_candidates_keep_local_track_and_unknown_identity(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            algorithm, output = root / 'algorithm', root / 'output'
            algorithm.mkdir(); output.mkdir()
            (algorithm / 'raw.png').write_bytes(b'real-frame-placeholder-for-mapping-test')
            session = {'id': 's1', 'person_id': 'P003', 'region_id': 'manual_01',
                'start_ms': 100, 'end_ms': 800, 'shelf_level': None,
                'identity': {'profile_id': 'G9999'}}
            detail = {'session': session, 'review': {'status': 'not_requested'},
                'keyframes': [{'raw_path': 'raw.png', 'actual_ms': 150}]}
            events, frames = shelf_facts(JOB, {'region_to_zone': {'manual_01': 'zone_a'}},
                [session], [detail], algorithm, output)
            self.assertEqual(events[0]['track_key'], 'P003')
            self.assertEqual(events[0]['observation_status'], 'candidate')
            summary = json.loads(events[0]['summary'])
            self.assertIsNone(summary['six_fields']['person_id'])
            self.assertIsNone(summary['pickup_detected'])
            self.assertNotIn('G9999', events[0]['summary'])
            self.assertEqual((output / frames[0]['file_name']).read_bytes(), (algorithm / 'raw.png').read_bytes())

    def test_matched_gallery_identity_never_replaces_local_track(self):
        cases = [({'status': 'matched', 'profile_id': 'G0007'}, {}, 'G0007'),
                 ({'status': 'conflict', 'profile_id': 'G0007'}, {}, None),
                 ({'status': 'unmatched', 'profile_id': None}, {}, None),
                 ({'status': 'matched', 'profile_id': 'P003'}, {}, None),
                 ({'status': 'matched', 'profile_id': 'G0007'}, {'person_id': 'P004'}, None)]
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            for identity, opinion, expected in cases:
                with self.subTest(identity=identity, opinion=opinion):
                    session = {'id': 's1', 'person_id': 'P003', 'region_id': 'r1',
                               'start_ms': 100, 'end_ms': 800, 'identity': identity}
                    detail = {'session': session, 'review': {'status': 'not_requested', 'opinion': opinion}, 'keyframes': []}
                    events, _ = shelf_facts(JOB, {'region_to_zone': {'r1': 'zone_a'}}, [session], [detail], root, root)
                    self.assertEqual(events[0]['person_id'], expected)
                    self.assertEqual(events[0]['track_key'], 'P003')
                    self.assertEqual(json.loads(events[0]['summary'])['six_fields']['person_id'], expected)

    def test_unknown_camera_or_version_fails_before_model_execution(self):
        with self.assertRaisesRegex(WorkerFailure, 'camera_not_configured'):
            run({'cameras': {}}, Path('not-used.mp4'), CONFIG, JOB, Path('.'))
        with self.assertRaisesRegex(WorkerFailure, 'config_mismatch'):
            run({'cameras': {'camera_a': {'config_version': 'wrong'}}},
                Path('not-used.mp4'), CONFIG, JOB, Path('.'))

    def test_missing_role_mapping_does_not_default_both_cameras_to_shelves(self):
        with tempfile.TemporaryDirectory() as temp:
            config = Path(temp) / 'worker.json'
            config.write_text(json.dumps({'schema_version': 1, 'device': 'cpu',
                'runtime_config': 'configs/vision_runtime.json', 'cameras': {'camera_a': {}}}))
            with self.assertRaisesRegex(WorkerFailure, 'camera_not_configured'):
                load_settings(config)

    def test_unconfigured_real_b_scene_fails_before_model_execution(self):
        settings = {'cameras': {'camera_a': {'role': 'shelf', 'calibration': None}}}
        with self.assertRaisesRegex(WorkerFailure, 'calibration_invalid'):
            run(settings, Path('not-used.mp4'), CONFIG, JOB, Path('.'))

    def test_completely_unregistered_b_scene_cannot_publish_zero_detections(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            calibration = folder / 'calibration.json'
            calibration.write_text(json.dumps({'shelf_calibration': {'source': 'manual_image'}}))
            camera = {'role': 'shelf', 'calibration': str(calibration), 'config_version': 'site_v1',
                'session_zone_id': 'zone_a', 'region_to_zone': {'manual_01': 'zone_a'}, 'gallery': None}
            settings = {'device': 'cpu', 'runtime_config': str(Path(__file__).resolve().parents[1] / 'configs/vision_runtime.json'),
                'cameras': {'camera_a': camera}}
            probe = SimpleNamespace(frame_count=30, fps=30, cap=SimpleNamespace(release=lambda: None))
            with patch('vision.station_vision.video.VideoSource', return_value=probe), \
                    patch('vision.station_vision.shelf_session.observer.observe', return_value=(
                        folder / 'video.mp4', [{'registration_valid': False}], {'registration_valid_frames': 0})):
                with self.assertRaisesRegex(WorkerFailure, 'calibration_invalid'):
                    run(settings, folder / 'video.mp4', CONFIG, JOB, folder)
            self.assertFalse((folder / 'manifest.json').exists())

    def test_explicit_future_b_gallery_does_not_prevent_first_a_start(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            calibration = folder / 'calibration.json'
            calibration.write_text(json.dumps({'camera_id': 'B', 'shelf_calibration': {'source': 'manual_image'},
                'shelf_zones': [{'id': 'manual_01'}]}))
            config = folder / 'worker.json'
            config.write_text(json.dumps({'schema_version': 1, 'device': 'cpu',
                'runtime_config': 'configs/vision_runtime.json', 'cameras': {
                    'camera_a': {'role': 'enrollment', 'config_version': 'site_v1', 'enrollment_root': str(folder / 'new-gallery')},
                    'camera_b': {'role': 'shelf', 'config_version': 'site_v1', 'calibration': str(calibration),
                        'calibration_camera_id': 'B', 'region_to_zone': {'manual_01': 'zone_a'},
                        'session_zone_id': 'zone_a', 'gallery_root': str(folder / 'future-gallery')}}}))
            settings = load_settings(config)
            self.assertEqual(settings['cameras']['camera_a']['role'], 'enrollment')
            self.assertFalse(Path(settings['cameras']['camera_b']['gallery_root']).exists())


if __name__ == '__main__':
    unittest.main()
