"""Local publication fences using protocol fixtures, never real appearance/identity data."""
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

import httpx

from vision.gallery_publication import GalleryPublication, completed_archive, current_version, exclusive
from vision.worker import Settings, Worker
from vision.worker_protocol import WorkerFailure
from test_worker import JOB, FakeBackend, FIXTURE


def write_fixture(stage, jobs):
    gallery = stage / 'data/person_enrollment'
    gallery.mkdir(parents=True, exist_ok=True)
    (gallery / 'archive.json').write_text(json.dumps({'fixture_jobs': jobs, 'self_path': str(gallery / 'image.png')}))


class GalleryPublicationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.folder = Path(self.temp.name).resolve()
        self.root = self.folder / 'committed-gallery'
        self.artifacts = self.folder / 'artifacts'
        self.artifacts.mkdir()

    def worker(self, backend, mode='ok'):
        command = [sys.executable, str(FIXTURE), '--input', '{input}', '--config', '{config}',
            '--job', '{job}', '--output', '{output}', '--mode', mode,
            '--gallery-stage', '{gallery_stage}']
        settings = Settings('http://127.0.0.1:8000', 'private-test-token', command,
            heartbeat_interval=.02, artifact_root=self.artifacts, work_root=self.folder,
            adapter_settings={'cameras': {'camera_a': {'role': 'enrollment', 'enrollment_root': str(self.root)}}})
        worker = Worker(settings, transport=httpx.MockTransport(backend))
        self.addCleanup(worker.close)
        return worker

    def assert_no_published_gallery(self):
        self.assertFalse((self.root / 'CURRENT.json').exists())
        with self.assertRaisesRegex(WorkerFailure, 'gallery_missing'):
            completed_archive(self.root)

    def test_algorithm_failure_after_writing_staging_does_not_publish(self):
        backend = FakeBackend()
        with self.assertRaisesRegex(WorkerFailure, 'algorithm_failed'):
            self.worker(backend, 'enrollment_fail').run_once()
        self.assertTrue(list(self.artifacts.glob('*/enrollment-staging/data/person_enrollment/archive.json')))
        self.assert_no_published_gallery()

    def test_lost_lease_does_not_publish_staging(self):
        with self.assertRaisesRegex(WorkerFailure, 'lease_lost'):
            self.worker(FakeBackend(lose_lease=True)).run_once()
        self.assert_no_published_gallery()

    def test_revoked_completion_does_not_publish_staging(self):
        class Revoked(FakeBackend):
            def __call__(self, request):
                if request.url.path.endswith('/complete'):
                    return httpx.Response(403, json={'error': {'code': 'forbidden'}})
                return super().__call__(request)
        with self.assertRaisesRegex(WorkerFailure, 'backend_forbidden'):
            self.worker(Revoked()).run_once()
        self.assert_no_published_gallery()

    def test_other_attempts_completed_status_does_not_publish_stale_stage(self):
        class OtherAttempt(FakeBackend):
            def __call__(self, request):
                if request.url.path.endswith('/complete'):
                    return httpx.Response(409, json={})
                if request.url.path.endswith('/job_1'):
                    return httpx.Response(200, json={'job': {**JOB, 'status': 'completed', 'attempts': 2}})
                return super().__call__(request)
        with self.assertRaisesRegex(WorkerFailure, 'completion_unconfirmed'):
            self.worker(OtherAttempt()).run_once()
        self.assert_no_published_gallery()

    def test_lost_completion_response_same_attempt_can_publish_and_reconcile_is_idempotent(self):
        backend = FakeBackend(lose_completion=True)
        worker = self.worker(backend)
        self.assertTrue(worker.run_once())
        before = (self.root / 'CURRENT.json').read_bytes()
        self.assertEqual(json.loads(completed_archive(self.root).read_text())['fixture_jobs'], ['job_1'])
        output = next(self.artifacts.iterdir())
        self.assertFalse(worker.reconcile_gallery(output))
        self.assertEqual((self.root / 'CURRENT.json').read_bytes(), before)

    def test_terminal_heartbeat_409_does_not_cancel_valid_completion_receipt(self):
        class TerminalHeartbeat(FakeBackend):
            def __call__(self, request):
                if request.url.path.endswith('/heartbeat') and self.published:
                    return httpx.Response(409, json={})
                return super().__call__(request)
        original = GalleryPublication.promote
        def slow_publish(publication, receipt):
            time.sleep(.08)
            return original(publication, receipt)
        with patch('vision.worker.GalleryPublication.promote', slow_publish):
            self.assertTrue(self.worker(TerminalHeartbeat()).run_once())
        self.assertTrue(completed_archive(self.root).is_file())

    def test_unversioned_legacy_gallery_is_not_silently_used_as_committed_base(self):
        legacy = self.root / 'data/person_enrollment'
        legacy.mkdir(parents=True)
        (legacy / 'keep.json').write_text('{"keep":true}')
        output = self.artifacts / 'new'
        output.mkdir()
        with exclusive(self.root, lambda: False), self.assertRaisesRegex(WorkerFailure, 'gallery_requires_migration'):
            GalleryPublication(self.root, JOB, output).prepare()
        self.assertEqual((legacy / 'keep.json').read_text(), '{"keep":true}')

    def test_serial_stages_preserve_updates_and_old_promotion_cannot_roll_back(self):
        publications = []
        for number in (1, 2):
            job = {**JOB, 'job_id': f'job_{number}', 'recording_id': f'job_{number}'}
            output = self.artifacts / f'output_{number}'
            output.mkdir()
            with exclusive(self.root, lambda: False):
                publication = GalleryPublication(self.root, job, output)
                stage = publication.prepare()
                if number == 2:
                    old = json.loads((stage / 'data/person_enrollment/archive.json').read_text())
                    self.assertEqual(old['fixture_jobs'], ['job_1'])
                    self.assertTrue(old['self_path'].startswith(str(stage)))
                write_fixture(stage, [f'job_{i}' for i in range(1, number + 1)])
                self.assertTrue(publication.promote({'job': {**job, 'status': 'completed'}}))
                publications.append(publication)
        pointer = (self.root / 'CURRENT.json').read_bytes()
        with exclusive(self.root, lambda: False):
            self.assertFalse(publications[0].promote({'job': {**JOB, 'status': 'completed'}}))
        self.assertEqual((self.root / 'CURRENT.json').read_bytes(), pointer)
        self.assertEqual(json.loads(completed_archive(self.root).read_text())['fixture_jobs'], ['job_1', 'job_2'])

    def test_lock_wait_keeps_heartbeats_and_stale_waiter_exits(self):
        backend = FakeBackend()
        worker = self.worker(backend)
        with ThreadPoolExecutor(max_workers=1) as executor:
            with exclusive(self.root, lambda: False):
                future = executor.submit(worker.run_once)
                deadline = time.monotonic() + 3
                while backend.heartbeats < 2 and time.monotonic() < deadline:
                    time.sleep(.01)
                self.assertGreaterEqual(backend.heartbeats, 2)
                backend.lose_lease = True
                with self.assertRaisesRegex(WorkerFailure, 'lease_lost'):
                    future.result(timeout=3)
        self.assert_no_published_gallery()

    def test_completed_unpublished_stage_can_be_explicitly_recovered(self):
        output = self.artifacts / 'recovery'
        output.mkdir()
        with exclusive(self.root, lambda: False):
            publication = GalleryPublication(self.root, JOB, output)
            stage = publication.prepare()
            write_fixture(stage, ['job_1'])
        self.assert_no_published_gallery()
        backend = FakeBackend()
        backend.published = True
        worker = self.worker(backend)
        self.assertTrue(worker.reconcile_gallery(output))
        self.assertEqual(current_version(self.root)['job_id'], 'job_1')
        self.assertFalse(worker.reconcile_gallery(output))
