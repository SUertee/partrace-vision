import copy
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
import tempfile
import sys
import threading
import time
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient
import httpx

from station_vision.service.app import create_app
from station_vision.service.backend import BackendClient, BackendPending, ServiceError, resolve_media
from station_vision.service.config import load_config
from station_vision.service.jobs import JobStore, QueueWorker, single_worker_lock
from station_vision.service.runner import VisionRunner
from station_vision.reid.profile_store import read_archive, atomic_json


ROOT = Path(__file__).resolve().parents[1]


class ServiceCase(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.settings = json.loads((ROOT / 'configs/vision_service.json').read_text(encoding='utf-8'))
        self.settings['backend']['media_root'] = 'media'
        self.settings['backend']['auth_mode'] = 'bearer'
        self.settings['poll_interval_s'] = .05
        self.settings['runtime_config'] = 'runtime.json'
        (self.root / 'runtime.json').write_text('{}', encoding='utf-8')
        self.env = patch.dict(os.environ, {'PARTRACE_API_TOKEN': 'backend-test-only'})
        self.env.start()

    def tearDown(self):
        self.env.stop()
        self.temp.cleanup()

    def config(self):
        (self.root / 'service.json').write_text(json.dumps(self.settings), encoding='utf-8')
        return load_config(self.root, 'service.json')

    @staticmethod
    def wait(predicate, timeout=5):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            if predicate():
                return
            time.sleep(.01)
        raise AssertionError('Task did not reach the expected state')


class QueueTests(ServiceCase):
    def test_A_local_analysis_unblocks_B_while_publication_waits_and_retry_keeps_analysis(self):
        store=JobStore(self.root);a,_=store.submit('a','cam_A','A',True);b,_=store.submit('b','cam_B','B')
        store.claim()
        saved={'analysis_status':'completed','writeback_status':'waiting_backend','result_file':'saved.json'}
        store.defer(a['job_id'],{'code':'backend_exact_claim_unavailable'},30,result=saved)
        self.assertEqual(store.get(a['job_id'])['status'],'waiting_writeback')
        self.assertEqual(store.claim()['job_id'],b['job_id'])
        store.finish(b['job_id'],result={'analysis_status':'completed'})
        store.retry(a['job_id'])
        self.assertEqual(store.claim()['result'],saved)
        store.finish(a['job_id'],error={'code':'backend_connection_failed'})
        self.assertEqual(store.get(a['job_id'])['result'],saved)
        self.assertEqual(store.retry(a['job_id'])['result'],saved)

    def test_waiting_backend_is_persisted_and_does_not_block_independent_A(self):
        store=JobStore(self.root)
        b,_=store.submit('b','cam_B','B')
        self.assertEqual(store.claim()['job_id'],b['job_id'])
        store.defer(b['job_id'],{'code':'backend_exact_claim_unavailable'},30)
        a,_=store.submit('a','cam_A','A',True)
        self.assertEqual(store.claim()['job_id'],a['job_id'])
        store.finish(a['job_id'],result={'ok':True})
        restarted=JobStore(self.root)
        restarted.recover()
        self.assertEqual(restarted.get(b['job_id'])['status'],'waiting_backend')
        self.assertIsNone(restarted.claim())
        self.assertTrue(restarted.get(b['job_id'])['next_retry_at'])
        self.assertEqual(restarted.retry(b['job_id'])['job_id'],b['job_id'])
        self.assertEqual(restarted.claim()['job_id'],b['job_id'])

    def test_B_still_waits_for_A_with_pending_backend_prerequisite(self):
        store=JobStore(self.root)
        a,_=store.submit('a','cam_A','A',True)
        b,_=store.submit('b','cam_B','B')
        store.claim()
        store.defer(a['job_id'],{'code':'backend_exact_claim_unavailable'},30)
        self.assertIsNone(store.claim())
        self.assertEqual(store.get(b['job_id'])['status'],'waiting_for_a')
        store.retry(a['job_id']);store.claim();store.finish(a['job_id'],result={'ok':True})
        self.assertEqual(store.claim()['job_id'],b['job_id'])

    def test_worker_resumes_pending_prerequisite_without_stopping_receiver(self):
        store=JobStore(self.root);job,_=store.submit('b','cam_B','B');seen=[]
        class Runner:
            def run(inner,job,stop):
                seen.append(job['job_id'])
                if len(seen)==1:raise BackendPending('backend_job_busy','Target busy',.05)
                return {'ok':True}
        worker=QueueWorker(store,Runner(),.01);worker.start()
        try:
            self.wait(lambda:store.get(job['job_id'])['status']=='completed')
            self.assertEqual(seen,[job['job_id'],job['job_id']])
            self.assertIsNone(worker.fatal_error)
        finally:worker.stop()

    def test_saved_publication_refreshes_only_the_matching_completed_task(self):
        store = JobStore(self.root)
        job, _ = store.submit('rec_A', 'cam_A', 'A')
        store.claim()
        path = self.root / 'result_package.json'
        store.finish(job['job_id'], result={'writeback_status':'failed','result_file':str(path)})
        package = {'recording_id':'wrong','camera_id':'cam_A','writeback_status':'published'}
        atomic_json(path, package)
        self.assertFalse(store.sync_published_result(job['job_id'], path))
        self.assertEqual(store.get(job['job_id'])['result']['writeback_status'],'failed')
        atomic_json(path, {**package,'recording_id':'rec_A'})
        self.assertTrue(store.sync_published_result(job['job_id'], path))
        self.assertEqual(store.get(job['job_id'])['result']['writeback_status'],'published')
        self.assertEqual(store.get(job['job_id'])['status'],'completed')

    def test_concurrent_duplicate_notifications_create_one_job(self):
        store = JobStore(self.root)
        with ThreadPoolExecutor(max_workers=8) as executor:
            responses = list(executor.map(lambda _: store.submit('rec_A', 'cam_A', 'A'), range(24)))
        self.assertEqual(sum(new for _, new in responses), 1)
        self.assertEqual(len({job['job_id'] for job, _ in responses}), 1)

    def test_B_waits_for_all_accepted_A_but_later_A_does_not_jump_queue(self):
        store = JobStore(self.root)
        a1, _ = store.submit('a1', 'cam_A', 'A')
        a2, _ = store.submit('a2', 'cam_A', 'A')
        b, _ = store.submit('b', 'cam_B', 'B')
        a3, _ = store.submit('a3', 'cam_A', 'A')
        self.assertEqual(b['dependencies'], [a1['job_id'], a2['job_id']])
        for expected in (a1, a2, b, a3):
            job = store.claim()
            self.assertEqual(job['job_id'], expected['job_id'])
            store.finish(job['job_id'], result={'ok': True})

    def test_A_failure_blocks_B_and_A_retry_unblocks_it(self):
        store = JobStore(self.root)
        a, _ = store.submit('a', 'cam_A', 'A')
        b, _ = store.submit('b', 'cam_B', 'B')
        store.claim()
        store.finish(a['job_id'], error={'code': 'test_failure'})
        self.assertIsNone(store.claim())
        self.assertEqual(store.get(b['job_id'])['status'], 'blocked')
        with self.assertRaises(ValueError):
            store.retry(b['job_id'])
        store.retry(a['job_id'])
        job = store.claim()
        self.assertEqual(job['attempt'], 2)
        store.finish(job['job_id'], result={'ok': True})
        self.assertEqual(store.claim()['job_id'], b['job_id'])

    def test_restart_retains_dedup_and_requires_retry_of_interrupted_A(self):
        store = JobStore(self.root)
        a, _ = store.submit('a', 'cam_A', 'A')
        b, _ = store.submit('b', 'cam_B', 'B')
        store.claim()
        restarted = JobStore(self.root)
        restarted.recover()
        self.assertEqual(restarted.get(a['job_id'])['status'], 'interrupted')
        self.assertFalse(restarted.submit('a', 'cam_A', 'A')[1])
        self.assertIsNone(restarted.claim())
        self.assertEqual(restarted.get(b['job_id'])['status'], 'blocked')

    def test_B_without_A_can_start_immediately(self):
        store = JobStore(self.root)
        b, _ = store.submit('b', 'cam_B', 'B')
        self.assertEqual(store.claim()['job_id'], b['job_id'])

    def test_completed_jobs_cannot_be_retried_accidentally(self):
        store = JobStore(self.root)
        a, _ = store.submit('a', 'cam_A', 'A')
        store.claim()
        store.finish(a['job_id'], result={'ok': True})
        with self.assertRaises(ValueError):
            store.retry(a['job_id'])

    def test_worker_is_serial_and_B_reads_after_A_finished(self):
        store = JobStore(self.root)
        a, _ = store.submit('a', 'cam_A', 'A')
        b, _ = store.submit('b', 'cam_B', 'B')
        gate = threading.Event()
        seen = []
        class Runner:
            def run(inner, job, stop):
                seen.append(job['role'])
                if job['role'] == 'A':
                    gate.wait(3)
                    (self.root / 'archive_ready').write_text('new A', encoding='utf-8')
                else:
                    self.assertEqual((self.root / 'archive_ready').read_text(), 'new A')
                return {'ok': True}
        worker = QueueWorker(store, Runner(), .05)
        worker.start()
        try:
            self.wait(lambda: store.get(a['job_id'])['status'] == 'running')
            self.assertEqual(store.get(b['job_id'])['status'], 'waiting_for_a')
            self.assertEqual(seen, ['A'])
            gate.set()
            self.wait(lambda: store.get(b['job_id'])['status'] == 'completed')
            self.assertEqual(seen, ['A', 'B'])
        finally:
            gate.set()
            worker.stop()

    def test_single_worker_lock_rejects_second_instance(self):
        with single_worker_lock(self.root):
            with self.assertRaises(RuntimeError):
                with single_worker_lock(self.root):
                    pass


class HttpTests(ServiceCase):
    def test_local_results_can_be_read_while_backend_publication_is_waiting(self):
        with TestClient(create_app(self.config(),start_worker=False)) as client:
            reply=client.post('/vision/v1/camera-b/jobs',json={'recording_id':'b','camera_id':'cam_B'}).json()
            store=client.app.state.store;store.claim()
            path=self.root/'result.json';path.write_text(json.dumps({'analysis_status':'completed','writeback_status':'waiting_backend','event_records':[]}),encoding='utf-8')
            store.defer(reply['job_id'],{'code':'backend_exact_claim_unavailable'},30,
                result={'analysis_status':'completed','result_file':str(path),'writeback_status':'waiting_backend'})
            result=client.get(reply['status_url']+'/result')
            self.assertEqual(result.status_code,200)
            self.assertEqual(result.json()['writeback_status'],'waiting_backend')

    def test_concurrent_notifications_are_accepted_while_algorithm_is_busy(self):
        gate=threading.Event();started=threading.Event();seen=[]
        class Runner:
            def run(inner,job,stop):
                seen.append(job['recording_id']);started.set()
                gate.wait(10)
                return {'ok':True}
        with TestClient(create_app(self.config(),runner=Runner())) as client:
            try:
                a=client.post('/vision/v1/camera-a/jobs',json={'recording_id':'a','camera_id':'cam_A','left2right_flag':True})
                self.assertTrue(started.wait(2))
                began=time.perf_counter()
                with ThreadPoolExecutor(max_workers=8) as executor:
                    replies=list(executor.map(lambda i:client.post('/vision/v1/camera-b/jobs',
                        json={'recording_id':'burst_'+str(i),'camera_id':'cam_B'}),range(20)))
                self.assertLess(time.perf_counter()-began,3)
                self.assertTrue(all(r.status_code==202 for r in replies))
                self.assertEqual(len({r.json()['job_id'] for r in replies}),20)
                self.assertEqual(seen,['a'])
                self.assertEqual(client.get('/healthz').json()['queue']['waiting_for_a'],20)
            finally:gate.set()

    def test_A_flag_required_strict_and_persisted(self):
        with TestClient(create_app(self.config(),start_worker=False)) as client:
            for extra in ({},{'left2right_flag':'false'},{'left2right_flag':1}):
                self.assertEqual(client.post('/vision/v1/camera-a/jobs',
                    json={'recording_id':'a','camera_id':'cam_A',**extra}).status_code,422)
            first=client.post('/vision/v1/camera-a/jobs',
                json={'recording_id':'a','camera_id':'cam_A','left2right_flag':False})
            self.assertEqual(first.status_code,202)
            state=client.get(first.json()['status_url']).json()
            self.assertIs(state['options']['left2right_flag'],False)
            self.assertEqual(client.post('/vision/v1/camera-a/jobs',
                json={'recording_id':'a','camera_id':'cam_A','left2right_flag':True}).status_code,409)

    def test_recording_id_is_required_for_both_routes(self):
        with TestClient(create_app(self.config(),start_worker=False)) as client:
            self.assertEqual(client.post('/vision/v1/camera-a/jobs',
                json={'camera_id':'cam_A','left2right_flag':True}).status_code,422)
            self.assertEqual(client.post('/vision/v1/camera-b/jobs',
                json={'camera_id':'cam_B'}).status_code,422)

    def test_public_role_validation_and_duplicate_status(self):
        with TestClient(create_app(self.config(), start_worker=False)) as client:
            self.assertEqual(client.get('/vision/v1/jobs').status_code, 200)
            self.assertEqual(client.post('/vision/v1/camera-a/jobs', 
                json={'recording_id': 'r', 'camera_id': 'cam_B'}).status_code, 422)
            self.assertEqual(client.post('/vision/v1/camera-a/jobs', 
                json={'recording_id': '../bad', 'camera_id': 'cam_A'}).status_code, 422)
            self.assertEqual(client.post('/vision/v1/camera-a/jobs', 
                json={'recording_id': 'r', 'camera_id': 'cam_A', 'video_path': '/private'}).status_code, 422)
            first = client.post('/vision/v1/camera-a/jobs', 
                json={'recording_id': 'r', 'camera_id': 'cam_A', 'left2right_flag': True})
            self.assertEqual(first.status_code, 202)
            replay = client.post('/vision/v1/camera-a/jobs', 
                json={'recording_id': 'r', 'camera_id': 'cam_A', 'left2right_flag': True}).json()
            self.assertFalse(replay['created'])
            self.assertEqual(first.json()['job_id'], replay['job_id'])
            self.assertEqual(client.get(first.json()['status_url']).status_code, 200)
            self.assertEqual(client.get(first.json()['status_url'] + '/result').status_code, 409)
            self.assertEqual(client.post(first.json()['status_url'] + '/retry').status_code, 409)
            self.assertEqual(client.get('/vision/v1/jobs/unknown').status_code, 404)

    def test_HTTP_notification_returns_while_A_is_still_running(self):
        gate = threading.Event()
        class Runner:
            def run(inner, job, stop):
                gate.wait(3)
                return {'ok': True}
        with TestClient(create_app(self.config(), runner=Runner())) as client:
            try:
                response = client.post('/vision/v1/camera-a/jobs',
                    json={'recording_id': 'a', 'camera_id': 'cam_A', 'left2right_flag': True})
                self.assertEqual(response.status_code, 202)
                self.assertFalse(gate.is_set())
            finally:
                gate.set()

    def test_all_service_routes_are_public_without_token_or_configured_secret(self):
        with patch.dict(os.environ, {'STATION_VISION_SERVICE_TOKEN': ''}):
            cfg = self.config()
            self.assertNotIn('service_token_env', cfg.values)
            with TestClient(create_app(cfg, start_worker=False)) as client:
                self.assertEqual(client.get('/vision/v1/jobs').status_code, 200)
                self.assertEqual(client.get('/vision/v1/jobs', headers={'Authorization':'Bearer obsolete'}).status_code, 200)
                schema = client.get('/openapi.json').json()
                self.assertNotIn('securitySchemes', schema['components'])
                for route in schema['paths'].values():
                    for operation in route.values():
                        self.assertFalse(operation.get('security'))
                b = client.post('/vision/v1/camera-b/jobs', json={'recording_id':'b','camera_id':'cam_B'})
                self.assertEqual(b.status_code, 202)
                url = b.json()['status_url']
                self.assertEqual(client.get(url).status_code, 200)
                self.assertEqual(client.get(url+'/result').status_code, 409)
                self.assertEqual(client.post(url+'/retry').status_code, 409)
                self.assertEqual(client.get('/healthz').json()['authentication'], 'none')

    def test_backend_request_uses_only_configured_API_and_hides_error_body(self):
        cfg = self.config()
        def handle(request):
            self.assertEqual(request.url.path, '/api/v1/recordings/rec_A')
            self.assertEqual(request.headers['authorization'], 'Bearer backend-test-only')
            return httpx.Response(403, text='backend-test-only secret details')
        original = httpx.Client
        with patch('station_vision.service.backend.httpx.Client',
                   side_effect=lambda **kw: original(transport=httpx.MockTransport(handle), **kw)):
            with self.assertRaises(ServiceError) as error:
                BackendClient(cfg).get_recording('rec_A')
        self.assertEqual(error.exception.code, 'backend_http_403')
        self.assertNotIn('backend-test-only', str(error.exception))

    def test_backend_looks_up_private_source_by_recording_and_camera(self):
        cfg=self.config();seen=[]
        def handle(request):
            seen.append(request.url.path)
            if request.url.path=='/api/v1/recordings/rec_A':
                return httpx.Response(200,json={'recording':{'recording_id':'rec_A','camera_id':'cam_A'}})
            self.assertEqual(request.url.params['camera_id'],'cam_A')
            return httpx.Response(200,json={'source':{'recording_id':'rec_A','camera_id':'cam_A','storage_key':'objects/source.mp4'}})
        original=httpx.Client
        with patch('station_vision.service.backend.httpx.Client',side_effect=lambda **kw: original(transport=httpx.MockTransport(handle),**kw)):
            record=BackendClient(cfg).get_recording('rec_A')
        self.assertEqual(record['storage_key'],'objects/source.mp4')
        self.assertEqual(seen,['/api/v1/recordings/rec_A','/api/v1/vision/recordings/rec_A/source'])

    def test_backend_source_camera_conflict_rejected(self):
        cfg=self.config()
        def handle(request):
            if request.url.path=='/api/v1/recordings/rec_A':
                return httpx.Response(200,json={'recording':{'recording_id':'rec_A','camera_id':'cam_A'}})
            return httpx.Response(200,json={'source':{'recording_id':'rec_A','camera_id':'cam_B','storage_key':'objects/source.mp4'}})
        original=httpx.Client
        with patch('station_vision.service.backend.httpx.Client',side_effect=lambda **kw: original(transport=httpx.MockTransport(handle),**kw)):
            with self.assertRaises(ServiceError) as e:BackendClient(cfg).get_recording('rec_A')
        self.assertEqual(e.exception.code,'backend_source_mismatch')


class MediaTests(ServiceCase):
    def record(self, key='objects/test.mp4'):
        return {'recording_id': 'r', 'camera_id': 'cam_A', 'storage_key': key,
                'media_status': 'available', 'duration_ms': 1000}

    def test_backend_camera_mismatch_rejected_before_media_access(self):
        with self.assertRaises(ServiceError) as e:
            resolve_media(self.config(), self.record(), {'recording_id': 'r', 'camera_id': 'cam_B'})
        self.assertEqual(e.exception.code, 'recording_camera_mismatch')

    def test_safe_media_key_opens_real_file(self):
        file = self.root / 'media/objects/test.mp4'
        file.parent.mkdir(parents=True)
        file.write_bytes(b'video')
        self.assertEqual(resolve_media(self.config(), self.record(), {'recording_id': 'r', 'camera_id': 'cam_A'}), file)

    def test_storage_traversal_URL_and_absolute_paths_rejected(self):
        for key in ('../private.mp4', '/private.mp4', 'C:/private.mp4', 'objects\\private.mp4', 'https://private/video'):
            with self.subTest(key=key), self.assertRaises(ServiceError):
                resolve_media(self.config(), self.record(key), {'recording_id': 'r', 'camera_id': 'cam_A'})

    def test_current_backend_hidden_key_is_explicit_contract_gap(self):
        record = self.record()
        record.pop('storage_key')
        with self.assertRaises(ServiceError) as e:
            resolve_media(self.config(), record, {'recording_id': 'r', 'camera_id': 'cam_A'})
        self.assertEqual(e.exception.code, 'recording_source_not_exposed')

    def test_missing_media_is_not_success(self):
        with self.assertRaises(ServiceError) as e:
            resolve_media(self.config(), self.record(), {'recording_id': 'r', 'camera_id': 'cam_A'})
        self.assertEqual(e.exception.code, 'media_missing')

    def test_permission_denied_reports_shared_mount_problem(self):
        cfg = self.config()
        with patch.object(Path, 'open', side_effect=PermissionError):
            with self.assertRaises(ServiceError) as e:
                resolve_media(cfg, self.record(), {'recording_id': 'r', 'camera_id': 'cam_A'})
        self.assertEqual(e.exception.code, 'media_permission_denied')


class SubprocessTests(ServiceCase):
    def test_failed_subprocess_log_is_redacted(self):
        runner = VisionRunner(self.config())
        log = self.root / 'failure.log'
        args = [sys.executable, '-c', "import os; print(os.environ['PARTRACE_API_TOKEN']); raise SystemExit(1)"]
        with self.assertRaises(ServiceError) as e:
            runner.execute(args, log, threading.Event())
        self.assertEqual(e.exception.code, 'analysis_process_failed')
        self.assertIn('[REDACTED]', log.read_text())
        self.assertNotIn('backend-test-only', log.read_text())

    def test_shutdown_interrupts_inference_instead_of_waiting_for_completion(self):
        runner = VisionRunner(self.config())
        stop = threading.Event()
        stop.set()
        with self.assertRaises(ServiceError) as e:
            runner.execute([sys.executable, '-c', 'import time; time.sleep(60)'], self.root / 'interrupted.log', stop)
        self.assertEqual(e.exception.code, 'service_interrupted')


def archive_value():
    return {'format': 'station_person_archive_v1', 'weights_sha256': 'test-weights',
            'profiles': {}, 'occurrences': {}, 'next_id': 1, 'legacy_imports': []}


class GalleryTests(ServiceCase):
    def test_windows_archive_photo_resolves_after_linux_directory_migration(self):
        folder = self.root / 'data/person_enrollment'
        photo = folder / 'G0001/history/test.png'
        photo.parent.mkdir(parents=True)
        photo.write_bytes(b'original')
        data = archive_value()
        vector = [1.] + [0.] * 511
        data['profiles']['G0001'] = {'templates': [{'feature': vector,
            'archive_crop': r'C:\old\code\data\person_enrollment\G0001\test.png',
            'crop_sha256': hashlib.sha256(b'original').hexdigest()}]}
        atomic_json(folder / 'archive.json', data)
        loaded = read_archive(folder / 'archive.json')
        self.assertEqual(loaded['profiles']['G0001']['templates'][0]['archive_crop'], str(photo))
        self.assertEqual(json.loads((folder / 'archive.json').read_text())['profiles'], data['profiles'])

    def test_migrated_photo_with_wrong_hash_is_not_used(self):
        folder = self.root / 'data/person_enrollment'
        photo = folder / 'G0001/test.png'
        photo.parent.mkdir(parents=True)
        photo.write_bytes(b'wrong')
        data = archive_value()
        data['profiles']['G0001'] = {'templates': [{'feature': [1.] + [0.] * 511,
            'archive_crop': r'C:\old\G0001\test.png', 'crop_sha256': '0' * 64}]}
        atomic_json(folder / 'archive.json', data)
        self.assertEqual(read_archive(folder / 'archive.json')['profiles']['G0001']['templates'][0]['archive_crop'], r'C:\old\G0001\test.png')

    def test_B_freezes_latest_root_archive_and_next_B_reloads(self):
        cfg = self.config()
        photo = self.root / 'media/objects/test.mp4'
        photo.parent.mkdir(parents=True)
        photo.write_bytes(b'video')
        calibration = self.root / 'calibration.json'
        calibration.write_text('{}', encoding='utf-8')
        self.settings['b_calibration']['path'] = 'calibration.json'
        cfg = self.config()
        data = archive_value()
        atomic_json(cfg.archive, data)
        class Backend:
            def get_recording(inner, recording_id):
                return {'recording_id': recording_id, 'camera_id': 'cam_B', 'storage_key': 'objects/test.mp4',
                        'media_status': 'available', 'duration_ms': 1000, 'config_version': 'v1'}
        versions = []
        class Runner(VisionRunner):
            def execute(inner, args, log_path, stop):
                gallery = Path(args[args.index('--gallery') + 1])
                versions.append(json.loads(gallery.read_text())['next_id'])
                output = cfg.root / 'outputs' / args[args.index('--run-name') + 1]
                output.mkdir(parents=True)
                for name, val in [('summary.json', {'status': 'completed'}), ('events.json', []), ('event_details.json', [])]:
                    atomic_json(output / name, val)
                # Simulate a later update; frozen input must not change.
                changed = copy.deepcopy(data)
                changed['next_id'] = 99
                atomic_json(cfg.archive, changed)
                self.assertEqual(json.loads(gallery.read_text())['next_id'], versions[-1])
        runner = Runner(cfg, Backend())
        job = {'job_id': 'vj_test1', 'attempt': 1, 'recording_id': 'b1', 'camera_id': 'cam_B', 'role': 'B'}
        result = runner.run(job, threading.Event())
        self.assertEqual(result['writeback_status'], 'pending_backend_contract')
        runner.run({**job, 'job_id': 'vj_test2', 'recording_id': 'b2'}, threading.Event())
        self.assertEqual(versions, [1, 99])


if __name__ == '__main__':
    unittest.main()
