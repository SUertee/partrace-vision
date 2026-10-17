import copy
import hashlib
import json
import os
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch

import httpx

from station_vision.service.config import load_config
from station_vision.service.backend import BackendPending, WritebackPending, ServiceError
from station_vision.service.lan_jobs import LanJobSource, make_manifest, TaskStop
from station_vision.service.runner import VisionRunner


ROOT = Path(__file__).resolve().parents[1]


class LanTests(unittest.TestCase):
    def direct_handler(self, request, conflict=False):
        path = request.url.path
        self.assertFalse(path.endswith(('/claim', '/heartbeat', '/fail')) or path == '/openapi.json')
        self.assertNotIn('x-lease-token', request.headers)
        if path.endswith('/complete'):
            self.paths.append(path)
            self.assertIn(b'actual-image-bytes', request.content)
            self.assertNotIn(b'"recording":', request.content)
            self.assertNotIn('authorization', request.headers)
            if conflict:
                return httpx.Response(409, json={'error': {'code': 'idempotency_conflict',
                    'message': 'Already published a different result', 'request_id': 'direct-conflict-1'}})
            self.completed = True
            return httpx.Response(201, json={'event_count': 1, 'evidence_count': 1})
        return self.handle(request)

    def enable_direct(self):
        self.config.values['backend'].update(publication_mode='direct', auth_mode='none')

    def test_direct_upload_without_claim_lease_or_heartbeat_and_verifies_image(self):
        self.enable_direct()
        with self.mock_client(self.direct_handler), LanJobSource(self.config, self.job, threading.Event()) as source:
            self.assertIsNone(source.lease)
            self.assertIsNone(source.thread)
            result = source.publish(self.package, self.root)
            self.assertEqual(result['status'], 'published')
            self.assertEqual(result['submission_mode'], 'direct_complete_no_claim_no_lease')
            self.assertEqual(result['evidence_bytes_verified'], ['evd_fixed'])
            manifest = json.loads((self.root/'backend_manifest.json').read_text())
            self.assertEqual(set(manifest), {'events', 'evidence'})
        self.assertNotIn('/api/v1/recording-jobs/claim', self.paths)

    def test_direct_completed_conflict_is_submitted_and_preserved_without_claim_fallback(self):
        self.enable_direct()
        self.completed = True
        with self.mock_client(lambda r: self.direct_handler(r, conflict=True)), LanJobSource(self.config, self.job, threading.Event()) as source:
            result = source.publish(self.package, self.root)
        self.assertEqual(result['status'], 'failed')
        self.assertEqual(result['complete_response']['http_status'], 409)
        self.assertEqual(result['complete_response']['body']['error']['request_id'], 'direct-conflict-1')
        self.assertEqual(self.paths.count('/api/v1/recording-jobs/rA/complete'), 1)
        self.assertNotIn('/api/v1/recording-jobs/rA/fail', self.paths)

    def test_direct_runner_reuses_saved_analysis_and_stable_ids(self):
        self.enable_direct()
        outer = self
        calls = []
        class Runner(VisionRunner):
            def analyse(inner, job, stop, record, source, started):
                calls.append(source.read_bytes())
                work = outer.root/'direct_saved';work.mkdir()
                package = {**outer.package, 'recording_id': job['recording_id'],
                    'camera_id': job['camera_id'], 'config_version': record['config_version'],
                    'job_id': job['job_id'], 'role': job['role']}
                path = work/'result_package.json';path.write_text(json.dumps(package), encoding='utf-8')
                return {'analysis_status': 'completed', 'writeback_status': 'pending_backend_contract',
                    'result_file': str(path), 'output_dir': str(work), 'elapsed_s': .1}
        runner = Runner(self.config)
        job = {'job_id': 'vj_direct', 'attempt': 1, **self.job}
        with self.mock_client(self.direct_handler):
            first = runner.run(job, threading.Event())
            second = runner.run({**job, 'attempt': 2, 'result': first}, threading.Event())
        self.assertEqual(second['writeback_status'], 'published')
        self.assertEqual(len(calls), 1)
        self.assertEqual(self.paths.count('/api/v1/recordings/rA/media'), 1)
        saved = json.loads(Path(second['result_file']).read_text())
        self.assertEqual(saved['event_records'][0]['event_id'], 'evt_fixed')
        self.assertEqual(saved['evidence_records'][0]['evidence_id'], 'evd_fixed')

    def test_invalid_publication_mode_is_rejected(self):
        self.settings['backend']['publication_mode'] = 'unsupported'
        (self.root/'service.json').write_text(json.dumps(self.settings), encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'publication_mode'):
            load_config(self.root, 'service.json')

    def test_local_analysis_runs_once_before_backend_ready_then_only_saved_result_is_published(self):
        calls=[];ready=[False];outer=self
        job={'job_id':'vj_saved','attempt':1,**self.job}
        class Runner(VisionRunner):
            def analyse(inner,job,stop,record,source,started):
                calls.append(source.read_bytes())
                work=outer.root/'saved';work.mkdir()
                package={**outer.package,'recording_id':job['recording_id'],'camera_id':job['camera_id'],
                    'config_version':record['config_version'],'job_id':job['job_id'],'role':job['role']}
                path=work/'result_package.json';path.write_text(json.dumps(package),encoding='utf-8')
                return {'analysis_status':'completed','writeback_status':'pending_backend_contract',
                    'result_file':str(path),'output_dir':str(work),'elapsed_s':.1}
        def handler(request):
            if request.url.path=='/openapi.json' and not ready[0]:
                return httpx.Response(200,json={'paths':{}})
            return self.handle(request)
        runner=Runner(self.config)
        with self.mock_client(handler):
            for attempt in (1,2):
                with self.assertRaises(WritebackPending) as error:
                    runner.run({**job,'attempt':attempt},threading.Event())
                job['result']=error.exception.result
                self.assertEqual(job['result']['analysis_status'],'completed')
                self.assertEqual(len(calls),1)
                self.assertNotIn('/api/v1/recording-jobs/claim',self.paths)
            ready[0]=True
            result=runner.run({**job,'attempt':3},threading.Event())
        self.assertEqual(result['writeback_status'],'published')
        self.assertEqual(len(calls),1)
        self.assertEqual(self.paths.count('/api/v1/recordings/rA/media'),1)
        package=json.loads(Path(result['result_file']).read_text())
        self.assertEqual(package['event_records'][0]['event_id'],'evt_fixed')
        self.assertEqual(package['evidence_records'][0]['evidence_id'],'evd_fixed')

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.settings = json.loads((ROOT/'configs/vision_service.lan.json').read_text(encoding='utf-8'))
        self.settings['backend'].update(base_url='https://backend.test', token_files={}, ca_file=None,
            auth_mode='bearer', publication_mode='leased', source_sha256={'rA': hashlib.sha256(b'original-video').hexdigest()}, heartbeat_interval_s=1)
        (self.root/'service.json').write_text(json.dumps(self.settings), encoding='utf-8')
        self.config = load_config(self.root, 'service.json')
        self.env = patch.dict(os.environ, {'PARTRACE_API_TOKEN':'lan-test-only'})
        self.env.start()
        self.job = {'recording_id':'rA', 'camera_id':'lan_video_1', 'role':'A'}
        self.remote = {'job_id':'rA', 'recording_id':'rA', 'camera_id':'lan_video_1',
            'config_version':'v1', 'duration_ms':1000, 'source_mode':'prerecorded', 'status':'queued'}
        self.paths = []
        self.completed = False
        self.image = self.root/'frame.png'
        self.image.write_bytes(b'actual-image-bytes')
        self.package = {'analysis_status':'completed', 'event_records':[
            {'event_id':'evt_fixed', 'recording_id':'rA', 'zone_id':'E3', 'track_key':'P001', 'person_id':'G0001',
             'event_type':'enter_zone','shelf_level':None,'observation_status':'candidate',
             'start_offset_ms':100,'end_offset_ms':500,'detection_source':'automatic','summary':'horizontal movement'}],
            'evidence_records':[{'evidence_id':'evd_fixed','event_id':'evt_fixed','offset_ms':300,
                                 'storage_key':str(self.image),'bbox_json':None}]}

    def tearDown(self):
        self.env.stop()
        self.temp.cleanup()

    def handle(self, request):
        self.paths.append(request.url.path)
        if self.config.values['backend']['auth_mode'] == 'bearer':
            self.assertEqual(request.headers['authorization'], 'Bearer lan-test-only')
        else:
            self.assertNotIn('authorization',request.headers)
        path = request.url.path
        if path == '/api/v1/recordings/rA':
            return httpx.Response(200,json={'recording':{**self.remote,'media_status':'available','started_at':None}})
        if path == '/openapi.json':
            return httpx.Response(200,json={'paths':{'/api/v1/recording-jobs/claim':{'post':{'requestBody':{
                'content':{'application/json':{'schema':{'type':'object','properties':{
                    'worker_id':{},'recording_id':{},'camera_id':{}}}}}}}}}})
        if path.endswith('/claim'):
            payload = json.loads(request.content)
            self.assertEqual(payload['recording_id'],'rA')
            self.assertEqual(payload['camera_id'],'lan_video_1')
            return httpx.Response(200, json={'job':{**self.remote,'status':'processing'},'lease_token':'lease-test-only'})
        if path.endswith('/heartbeat'):
            self.assertEqual(request.headers['x-lease-token'],'lease-test-only')
            return httpx.Response(200,json={'job':self.remote})
        if path.endswith('/fail'):
            self.assertEqual(json.loads(request.content)['code'], 'vision_result_not_published')
            return httpx.Response(200,json={'job':{**self.remote,'status':'failed'}})
        if path == '/api/v1/recordings/rA/media':
            self.assertNotIn('x-lease-token',request.headers)
            return httpx.Response(200,content=b'original-video',headers={'content-type':'video/mp4'})
        if path.endswith('/complete'):
            self.assertEqual(request.headers['x-lease-token'],'lease-test-only')
            self.assertIn('multipart/form-data',request.headers['content-type'])
            self.assertIn(b'actual-image-bytes',request.content)
            self.assertIn(b'"zone_id":"E3"',request.content)
            self.assertNotIn(str(self.image).encode(),request.content)
            self.assertNotIn(b'original-video',request.content)
            self.completed = True
            return httpx.Response(201,json={'event_count':1,'evidence_count':1})
        if path == '/api/v1/evidence/evd_fixed/media':
            return httpx.Response(200,content=b'actual-image-bytes')
        if path.startswith('/api/v1/configs/'):
            return httpx.Response(200,json={'config':{'config_version':'v1','cameras':[{'camera_id':'lan_video_1','zones':[]}]}})
        if path == '/api/v1/recording-jobs/rA':
            return httpx.Response(200,json={'job':{**self.remote,**({'status':'completed','event_count':1,'evidence_count':1} if self.completed else {})}})
        raise AssertionError(path)

    def mock_client(self, handler=None):
        original = httpx.Client
        return patch('station_vision.service.lan_jobs.httpx.Client',
                     side_effect=lambda **kwargs: original(transport=httpx.MockTransport(handler or self.handle), **kwargs))

    def test_real_bytes_download_stable_manifest_upload_and_readback(self):
        with self.mock_client(), LanJobSource(self.config,self.job,threading.Event()) as source:
            self.assertEqual(source.source.read_bytes(),b'original-video')
            self.assertEqual(source.metrics['source_sha256'],hashlib.sha256(b'original-video').hexdigest())
            result=source.publish(self.package,self.root)
            self.assertEqual(result['status'],'published')
            self.assertEqual(result['evidence_bytes_verified'],['evd_fixed'])
            manifest=json.loads((self.root/'backend_manifest.json').read_text())
            self.assertIsNone(manifest['recording']['started_at'])
            self.assertEqual(manifest['recording']['file_name'],'source.mp4')
            self.assertEqual(manifest['events'][0]['person_id'],'G0001')
        self.assertNotIn('/api/v1/recording-jobs/rA/fail',self.paths)

    def test_wrong_notification_camera_does_not_claim(self):
        with self.mock_client(), self.assertRaises(ServiceError) as error:
            with LanJobSource(self.config,{**self.job,'camera_id':'lan_video_2'},threading.Event()):pass
        self.assertEqual(error.exception.code,'recording_camera_mismatch')
        self.assertNotIn('/api/v1/recording-jobs/claim',self.paths)

    def test_incorrect_hash_stops_before_algorithm_and_removes_partial(self):
        self.config.values['backend']['source_sha256']['rA']='0'*64
        with self.mock_client(), self.assertRaises(ServiceError) as error:
            with LanJobSource(self.config,self.job,threading.Event()):pass
        self.assertEqual(error.exception.code,'backend_media_hash_mismatch')
        self.assertFalse(list(self.config.state_dir.glob('media/**/*.mp4')))
        self.assertFalse(list(self.config.state_dir.glob('media/**/*.part')))

    def test_heartbeat_keeps_running_during_algorithm(self):
        with self.mock_client(), LanJobSource(self.config,self.job,threading.Event()) as source:
            self.assertFalse(source.lost.wait(1.3))
            self.assertGreaterEqual(source.metrics['heartbeat_count'],1)

    def test_lost_lease_interrupts_only_this_task(self):
        def handler(request):
            if request.url.path.endswith('/heartbeat'):return httpx.Response(409,json={'error':{'code':'lease_conflict'}})
            return self.handle(request)
        parent=threading.Event()
        with self.mock_client(handler), LanJobSource(self.config,self.job,parent) as source:
            self.assertTrue(source.lost.wait(2))
            self.assertTrue(source.task_stop.is_set())
            self.assertFalse(parent.is_set())
            result=source.publish(self.package,self.root)
            self.assertEqual(result['status'],'failed')
            self.assertEqual(result['error']['code'],'backend_lease_lost')
        self.assertNotIn('/api/v1/recording-jobs/rA/complete',self.paths)

    def test_publish_rejection_is_explicit_and_does_not_fake_zero_results(self):
        def handler(request):
            if request.url.path.endswith('/complete'):return httpx.Response(422,json={'error':{'code':'invalid_zone','message':'lan-test-only sensitive'}})
            return self.handle(request)
        with self.mock_client(handler), LanJobSource(self.config,self.job,threading.Event()) as source:
            result=source.publish(self.package,self.root)
            self.assertEqual(result['status'],'failed')
            self.assertIn('invalid_zone',result['error']['message'])
            self.assertNotIn('lan-test-only',json.dumps(result))
        self.assertIn('/api/v1/recording-jobs/rA/fail',self.paths)

    def test_partial_analysis_never_publishes(self):
        with self.assertRaises(ServiceError):
            make_manifest({**self.package,'analysis_status':'partial'},self.remote)

    def test_inadequately_calibrated_empty_result_is_not_published_as_no_detections(self):
        package={**self.package,'event_records':[],'evidence_records':[],
                 'quality_check':{'publishable':False,'registration_valid_ratio':27/171}}
        with self.assertRaises(ServiceError) as error:make_manifest(package,self.remote)
        self.assertEqual(error.exception.code,'calibration_coverage_insufficient')

    def test_manifest_defaults_E3_and_checks_video_bounds(self):
        package=copy.deepcopy(self.package)
        package['event_records'][0]['zone_id']=None
        manifest,files=make_manifest(package,self.remote)
        self.assertEqual(manifest['events'][0]['zone_id'],'E3')
        self.assertEqual(files[0]['sha256'],hashlib.sha256(b'actual-image-bytes').hexdigest())
        package['evidence_records'][0]['offset_ms']=1001
        with self.assertRaises(ServiceError):make_manifest(package,self.remote)

    def test_http_without_explicit_private_lan_option_is_rejected(self):
        self.config.values['backend']['base_url']='http://10.0.4.72:8080'
        self.config.values['backend']['allow_lan_http']=False
        with self.assertRaises(ServiceError) as error:
            LanJobSource(self.config,self.job,threading.Event())
        self.assertEqual(error.exception.code,'lan_http_disabled')

    def test_download_does_not_interpret_server_absolute_path(self):
        self.remote['storage_key']='D:/server-private/secret.mp4'
        with self.mock_client(), LanJobSource(self.config,self.job,threading.Event()) as source:
            self.assertTrue(source.source.is_relative_to(self.config.state_dir))
            self.assertEqual(source.source.read_bytes(),b'original-video')

    def test_explicit_no_bearer_ignores_existing_credentials_and_keeps_lease(self):
        self.config.values['backend'].update(auth_mode='none',base_url='http://10.0.4.72:8080',allow_lan_http=True,
            token_files={'A':'missing-private-token.txt'})
        def handler(request):
            self.assertNotIn('authorization',request.headers)
            return self.handle(request)
        with self.mock_client(handler), LanJobSource(self.config,self.job,threading.Event()) as source:
            self.assertIsNone(source.token)
            self.assertEqual(source.source.read_bytes(),b'original-video')

    def test_unsupported_exact_claim_downloads_only_target_and_never_claims_global_queue(self):
        def handler(request):
            if request.url.path == '/openapi.json':
                return httpx.Response(200,json={'paths':{'/api/v1/recording-jobs/claim':{'post':{'requestBody':{
                    'content':{'application/json':{'schema':{'properties':{'worker_id':{}}}}}}}}}})
            return self.handle(request)
        with self.mock_client(handler):
            for _ in range(2):
                with self.assertRaises(BackendPending) as error:
                    with LanJobSource(self.config,self.job,threading.Event()):pass
                self.assertEqual(error.exception.code,'backend_exact_claim_unavailable')
        self.assertEqual(self.paths.count('/api/v1/recordings/rA/media'),1)
        self.assertNotIn('/api/v1/recording-jobs/claim',self.paths)
        self.assertFalse(any(p.endswith('/fail') for p in self.paths))
        receipts=list(self.config.state_dir.glob('media/**/source_receipt.json'))
        self.assertEqual(len(receipts),1)
        self.assertEqual(json.loads(receipts[0].read_text())['recording']['recording_id'],'rA')

    def test_exact_claim_returns_wrong_recording_stops_before_inference(self):
        def handler(request):
            if request.url.path.endswith('/claim'):
                return httpx.Response(200,json={'job':{**self.remote,'job_id':'other','recording_id':'other'},'lease_token':'not-our-lease'})
            return self.handle(request)
        with self.mock_client(handler),self.assertRaises(ServiceError) as error:
            with LanJobSource(self.config,self.job,threading.Event()):pass
        self.assertEqual(error.exception.code,'backend_claim_mismatch')
        self.assertFalse(any(p.endswith('/heartbeat') or p.endswith('/fail') or 'other' in p for p in self.paths))

    def test_busy_exact_claim_waits_without_failure_or_wrong_task(self):
        def handler(request):
            if request.url.path.endswith('/claim'):
                return httpx.Response(409,json={'error':{'code':'lease_conflict'}})
            return self.handle(request)
        with self.mock_client(handler),self.assertRaises(BackendPending) as error:
            with LanJobSource(self.config,self.job,threading.Event()):pass
        self.assertEqual(error.exception.code,'backend_exact_claim_pending')
        self.assertFalse(any(p.endswith('/fail') for p in self.paths))

    def test_cache_corruption_downloads_original_again(self):
        with self.mock_client():
            with LanJobSource(self.config,self.job,threading.Event()) as source:
                path=source.source
            path.write_bytes(b'corrupted-video')
            with LanJobSource(self.config,self.job,threading.Event()) as source:
                self.assertEqual(source.source.read_bytes(),b'original-video')
        self.assertEqual(self.paths.count('/api/v1/recordings/rA/media'),2)

    def test_expired_target_lease_may_be_reclaimed_only_with_exact_id(self):
        self.remote.update(status='processing',lease_expires_at='2000-01-01T00:00:00Z')
        with self.mock_client(),LanJobSource(self.config,self.job,threading.Event()) as source:
            self.assertEqual(source.remote['recording_id'],'rA')

    def test_metadata_camera_conflict_prevents_download_and_claim(self):
        self.remote['camera_id']='another-camera'
        with self.mock_client(),self.assertRaises(ServiceError) as error:
            with LanJobSource(self.config,self.job,threading.Event()):pass
        self.assertEqual(error.exception.code,'recording_camera_mismatch')
        self.assertEqual(self.paths,['/api/v1/recordings/rA'])


if __name__=='__main__':unittest.main()
