"""Renewable HTTP queue consumer; inference always runs in a separate process."""
import argparse
from contextlib import nullcontext
from dataclasses import dataclass, field
import json
import ipaddress
import logging
import math
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import threading
import time
from urllib.parse import urlparse
from uuid import uuid4

import httpx

from .worker_protocol import ID, MAX_MEDIA, WorkerFailure, output_bundle, private_json, read_json, valid_job
from .gallery_publication import GalleryPublication, exclusive

LOG = logging.getLogger('vision.worker')
ROOT = Path(__file__).resolve().parents[1]


@dataclass
class Settings:
    api_base: str
    token: str = field(repr=False)
    command: list[str]
    worker_id: str = field(default_factory=lambda: 'vision-' + uuid4().hex)
    algorithm_timeout: float = 1800
    heartbeat_interval: float = 10
    poll_interval: float = 5
    request_timeout: float = 30
    max_media: int = MAX_MEDIA
    work_root: Path | None = None
    artifact_root: Path | None = None
    adapter_settings: dict | None = field(default=None, repr=False)

    def __post_init__(self):
        parsed = urlparse(self.api_base)
        if (parsed.scheme not in ('http', 'https') or not parsed.hostname or parsed.username
                or parsed.password or parsed.query or parsed.fragment or parsed.path not in ('', '/')):
            raise WorkerFailure('invalid_api_base')
        if parsed.scheme == 'http':
            try:
                loopback = parsed.hostname.lower() == 'localhost' or ipaddress.ip_address(parsed.hostname).is_loopback
            except ValueError:
                loopback = False
            if not loopback:
                raise WorkerFailure('insecure_api_base')
        if not self.token or any(c.isspace() for c in self.token):
            raise WorkerFailure('invalid_token')
        if not isinstance(self.command, list) or not self.command or any(not isinstance(a, str) or not a for a in self.command):
            raise WorkerFailure('algorithm_not_configured')
        if (not all(math.isfinite(n) for n in (self.heartbeat_interval, self.algorithm_timeout,
                self.poll_interval, self.request_timeout))
                or not 0 < self.heartbeat_interval < 30 or self.algorithm_timeout <= 0
                or self.poll_interval <= 0 or self.request_timeout <= 0 or self.max_media <= 0):
            raise WorkerFailure('invalid_worker_limits')


class Backend:
    def __init__(self, settings, transport=None):
        self.settings = settings
        self.client = httpx.Client(base_url=settings.api_base.rstrip('/'),
            headers={'Authorization': 'Bearer ' + settings.token}, timeout=settings.request_timeout,
            follow_redirects=False, trust_env=False, transport=transport)

    @staticmethod
    def check(response):
        if 200 <= response.status_code < 300:
            return response
        code = {401: 'backend_unauthorized', 403: 'backend_forbidden', 409: 'lease_conflict'}.get(
            response.status_code, 'backend_unavailable' if response.status_code >= 500 else 'backend_rejected')
        raise WorkerFailure(code)

    def request(self, method, path, **kwargs):
        try:
            response = self.check(self.client.request(method, '/api/v1' + path, **kwargs))
            payload = response.json()
            if not isinstance(payload, dict):
                raise ValueError('invalid envelope')
            return payload
        except httpx.HTTPError:
            raise WorkerFailure('backend_network_error') from None
        except ValueError:
            raise WorkerFailure('invalid_backend_response') from None

    def download(self, job_id, lease, path, cancelled):
        total = 0
        try:
            with self.client.stream('GET', '/api/v1/recording-jobs/' + job_id + '/media',
                    headers={'X-Lease-Token': lease}) as response:
                self.check(response)
                with path.open('xb') as stream:
                    os.chmod(path, 0o600)
                    for chunk in response.iter_bytes():
                        if cancelled():
                            raise WorkerFailure('lease_lost')
                        total += len(chunk)
                        if total > self.settings.max_media:
                            raise WorkerFailure('input_too_large')
                        stream.write(chunk)
            if total == 0:
                raise WorkerFailure('empty_video')
        except httpx.HTTPError:
            raise WorkerFailure('backend_network_error') from None


class Lease:
    def __init__(self, backend, job_id, token, stopped):
        self.backend, self.job_id, self.token, self.stopped = backend, job_id, token, stopped
        self.done, self.lost = threading.Event(), threading.Event()
        self.thread = threading.Thread(target=self._renew, daemon=True, name='vision-lease')

    @property
    def headers(self):
        return {'X-Lease-Token': self.token}

    def _renew(self):
        while not self.done.wait(self.backend.settings.heartbeat_interval):
            try:
                self.backend.request('POST', '/recording-jobs/' + self.job_id + '/heartbeat',
                    headers=self.headers, json={})
            except WorkerFailure:
                self.lost.set()
                return

    def cancelled(self):
        return self.lost.is_set() or self.stopped.is_set()

    def close(self):
        self.done.set()
        self.thread.join(timeout=self.backend.settings.request_timeout + 1)


def terminate(process):
    try:
        if os.name == 'posix':
            os.killpg(process.pid, signal.SIGKILL)
        elif process.poll() is None:
            process.kill()
    except ProcessLookupError:
        pass
    process.wait()


def run_algorithm(command, paths, timeout, cancelled):
    try:
        argv = [part.format_map({k: str(v) for k, v in paths.items()}) for part in command]
    except (KeyError, ValueError):
        raise WorkerFailure('invalid_algorithm_command') from None
    # The algorithm does not receive the worker Bearer or Backend credentials.
    env = {k: v for k, v in os.environ.items() if not (
        'TOKEN' in k.upper() or 'SECRET' in k.upper() or 'PASSWORD' in k.upper()
        or k.upper().endswith('API_KEY') or k == 'PARTRACE_DATABASE_URL')}
    try:
        process = subprocess.Popen(argv, cwd=ROOT, env=env, stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=os.name == 'posix')
    except OSError:
        raise WorkerFailure('algorithm_start_failed') from None
    deadline = time.monotonic() + timeout
    try:
        while process.poll() is None:
            if cancelled():
                raise WorkerFailure('lease_lost')
            if time.monotonic() >= deadline:
                raise WorkerFailure('algorithm_timeout')
            time.sleep(0.05)
        if process.returncode:
            error_path = paths['output'] / 'adapter_error.json'
            if error_path.is_file():
                code = read_json(error_path).get('code')
                if code in {'camera_not_configured', 'config_mismatch', 'calibration_invalid',
                        'gallery_missing', 'gallery_staging_invalid', 'gallery_pointer_invalid',
                        'runtime_dependency_missing', 'weights_missing', 'duration_mismatch'}:
                    raise WorkerFailure(code)
            raise WorkerFailure('algorithm_failed')
    finally:
        terminate(process)


class Worker:
    def __init__(self, settings, transport=None, stopped=None):
        self.settings = settings
        self.backend = Backend(settings, transport)
        self.stopped = stopped or threading.Event()

    def complete(self, job, lease, files, require_own_attempt=False):
        """Retry identical bytes; a lost response must not turn published facts into failure."""
        path = '/recording-jobs/' + job['job_id']
        for _ in range(2):
            try:
                result = self.backend.request('POST', path + '/complete', headers=lease.headers, files=files)
                if (result.get('job', {}).get('status') == 'completed' and result['job'].get('job_id') == job['job_id']
                        and (not require_own_attempt or result['job'].get('attempts') == job.get('attempts'))):
                    return result
            except WorkerFailure as exc:
                if exc.code not in ('backend_network_error', 'backend_unavailable', 'lease_conflict'):
                    raise
            try:
                status = self.backend.request('GET', path)
                if (status.get('job', {}).get('status') == 'completed' and status['job'].get('job_id') == job['job_id']
                        and (not require_own_attempt or status['job'].get('attempts') == job.get('attempts'))):
                    return status
                if status.get('job', {}).get('status') not in ('processing',):
                    break
            except WorkerFailure:
                pass
        raise WorkerFailure('completion_unconfirmed')

    def run_once(self):
        claimed = self.backend.request('POST', '/recording-jobs/claim', json={'worker_id': self.settings.worker_id})
        if claimed.get('job') is None:
            return False
        job = valid_job(claimed['job'])
        token = claimed.get('lease_token')
        if not isinstance(token, str) or not token:
            raise WorkerFailure('invalid_backend_response')
        lease = Lease(self.backend, job['job_id'], token, self.stopped)
        lease.thread.start()
        backend_completed = False
        try:
            if self.settings.work_root:
                self.settings.work_root.mkdir(parents=True, mode=0o700, exist_ok=True)
            with tempfile.TemporaryDirectory(prefix='vision-', dir=self.settings.work_root) as directory:
                folder = Path(directory)
                if self.settings.artifact_root:
                    self.settings.artifact_root.mkdir(parents=True, mode=0o700, exist_ok=True)
                    output = Path(tempfile.mkdtemp(prefix=job['job_id'] + '-', dir=self.settings.artifact_root))
                else:
                    output = folder / 'output'
                    output.mkdir(mode=0o700)
                private_json(folder / 'job.json', job)
                config = self.backend.request('GET', '/configs/' + job['config_version']).get('config')
                if not isinstance(config, dict) or config.get('config_version') != job['config_version']:
                    raise WorkerFailure('config_mismatch')
                private_json(folder / 'config.json', config)
                self.backend.download(job['job_id'], token, folder / 'video.mp4', lease.cancelled)
                paths = {'input': folder / 'video.mp4', 'config': folder / 'config.json',
                    'job': folder / 'job.json', 'output': output, 'gallery_stage': output / 'enrollment-staging'}
                camera = (self.settings.adapter_settings or {}).get('cameras', {}).get(job['camera_id'], {})
                publication = None
                if camera.get('role') == 'enrollment':
                    if not self.settings.artifact_root:
                        raise WorkerFailure('gallery_requires_persistent_artifacts')
                    publication = GalleryPublication(camera['enrollment_root'], job, output)
                with exclusive(publication.root, lease.cancelled) if publication else nullcontext():
                    if publication:
                        paths['gallery_stage'] = publication.prepare()
                    if lease.cancelled():
                        raise WorkerFailure('lease_lost')
                    run_algorithm(self.settings.command, paths, self.settings.algorithm_timeout, lease.cancelled)
                    files = output_bundle(output, job, config, self.settings.max_media)
                    if lease.cancelled():
                        raise WorkerFailure('lease_lost')
                    receipt = self.complete(job, lease, files, require_own_attempt=publication is not None)
                    backend_completed = True
                    if publication:
                        private_json(output / 'enrollment_completed.json', receipt)
                        publication.promote(receipt)
                LOG.info('job=%s status=completed', job['job_id'])
                return True
        except (WorkerFailure, OSError) as exc:
            code = exc.code if isinstance(exc, WorkerFailure) else 'worker_io_error'
            if backend_completed:
                # Do not continue advancing the gallery after a local publication failure.
                self.stopped.set()
                LOG.error('job=%s status=gallery_publication_pending', job['job_id'])
                raise WorkerFailure('gallery_publication_pending') from None
            # A stale worker must not mark another worker's lease failed.
            if not lease.lost.is_set() and code not in ('lease_conflict', 'lease_lost', 'completion_unconfirmed'):
                try:
                    self.backend.request('POST', '/recording-jobs/' + job['job_id'] + '/fail',
                        headers=lease.headers, json={'code': code})
                except WorkerFailure:
                    pass  # Expiry makes the durable task claimable after a network failure.
            LOG.error('job=%s status=%s code=%s', job['job_id'],
                'completion_unconfirmed' if code == 'completion_unconfirmed' else 'failed', code)
            raise WorkerFailure(code) from None
        finally:
            lease.close()

    def close(self):
        self.backend.client.close()

    def reconcile_gallery(self, output):
        """Explicitly recover a completed-but-unpublished local A stage after a crash."""
        from .gallery_publication import VERSION
        output = Path(output).resolve()
        if not self.settings.artifact_root or not output.is_relative_to(self.settings.artifact_root.resolve()):
            raise WorkerFailure('gallery_staging_invalid')
        marker = read_json(output / 'enrollment_stage.json')
        if not isinstance(marker.get('job_id'), str) or not ID.fullmatch(marker['job_id']):
            raise WorkerFailure('gallery_staging_invalid')
        camera = (self.settings.adapter_settings or {}).get('cameras', {}).get(marker.get('camera_id'), {})
        root = Path(camera.get('enrollment_root', '')).resolve()
        if (camera.get('role') != 'enrollment' or marker.get('publication_root') != str(root)
                or marker.get('profile_root') != str(output / 'enrollment-staging')
                or not isinstance(marker.get('version'), str) or not VERSION.fullmatch(marker['version'])):
            raise WorkerFailure('gallery_staging_invalid')
        with exclusive(root, self.stopped.is_set):
            receipt = self.backend.request('GET', '/recording-jobs/' + marker['job_id'])
            job = receipt.get('job', {})
            if (job.get('status') != 'completed' or job.get('job_id') != marker['job_id']
                    or job.get('camera_id') != marker['camera_id'] or job.get('config_version') != marker['config_version']
                    or job.get('attempts') != marker['attempts']):
                raise WorkerFailure('gallery_completion_unconfirmed')
            publication = GalleryPublication(root, job, output)
            publication.version = marker['version']
            base = marker.get('base_version')
            if base is not None:
                if not isinstance(base, str) or not VERSION.fullmatch(base):
                    raise WorkerFailure('gallery_pointer_invalid')
                publication.base = read_json(root / 'versions' / (base + '.json'))
            promoted = publication.promote(receipt)
            LOG.info('job=%s gallery=%s', job['job_id'], 'reconciled' if promoted else 'already_published')
            return promoted


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--api-base', default=os.environ.get('PARTRACE_API_BASE', ''))
    parser.add_argument('--token-file', default=os.environ.get('PARTRACE_VISION_TOKEN_FILE', ''))
    parser.add_argument('--worker-config', default=os.environ.get('PARTRACE_VISION_WORKER_CONFIG', ''))
    parser.add_argument('--command-json', default=os.environ.get('PARTRACE_VISION_COMMAND_JSON', ''))
    parser.add_argument('--algorithm-timeout', type=float, default=1800)
    parser.add_argument('--heartbeat-interval', type=float, default=10)
    parser.add_argument('--poll-interval', type=float, default=5)
    parser.add_argument('--work-root', type=Path)
    parser.add_argument('--artifact-root', type=Path)
    parser.add_argument('--reconcile-gallery', type=Path)
    parser.add_argument('--once', action='store_true')
    args = parser.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format='%(levelname)s %(message)s')
    logging.getLogger('httpx').setLevel(logging.WARNING)
    logging.getLogger('httpcore').setLevel(logging.WARNING)
    worker = None
    try:
        if args.token_file:
            token_path = Path(args.token_file)
            if token_path.is_symlink() or token_path.stat().st_size > 8192:
                raise WorkerFailure('invalid_token_file')
            token = token_path.read_text().strip()
        else:
            token = os.environ.get('PARTRACE_VISION_TOKEN', '')
        artifact_root = args.artifact_root
        adapter_settings = None
        if args.command_json:
            try:
                command = json.loads(args.command_json)
            except ValueError:
                raise WorkerFailure('invalid_algorithm_command') from None
            if args.worker_config:
                from .backend_adapter import load_settings
                adapter_settings = load_settings(Path(args.worker_config).resolve())
                artifact_root = artifact_root or Path(adapter_settings['artifacts_root'])
        else:
            from .backend_adapter import load_settings
            if not args.worker_config:
                raise WorkerFailure('camera_not_configured')
            configuration = Path(args.worker_config).resolve()
            adapter_settings = load_settings(configuration, check_runtime=True)
            artifact_root = artifact_root or Path(adapter_settings['artifacts_root'])
            command = [sys.executable, '-m', 'vision.backend_adapter', '--worker-config', str(configuration),
                '--input', '{input}', '--config', '{config}', '--job', '{job}', '--output', '{output}',
                '--enrollment-stage', '{gallery_stage}']
        settings = Settings(args.api_base, token, command, algorithm_timeout=args.algorithm_timeout,
            heartbeat_interval=args.heartbeat_interval, poll_interval=args.poll_interval, work_root=args.work_root,
            artifact_root=artifact_root, adapter_settings=adapter_settings)
        worker = Worker(settings)
        for sig in (signal.SIGTERM, signal.SIGINT):
            signal.signal(sig, lambda *_: worker.stopped.set())
        if args.reconcile_gallery:
            worker.reconcile_gallery(args.reconcile_gallery)
            return 0
        while not worker.stopped.is_set():
            try:
                worker.run_once()
            except WorkerFailure as exc:
                LOG.error('worker code=%s', exc.code)
                if args.once or exc.code in ('backend_unauthorized', 'backend_forbidden', 'gallery_publication_pending'):
                    return 1
            if args.once:
                return 0
            worker.stopped.wait(settings.poll_interval)
        return 0
    except (WorkerFailure, OSError) as exc:
        LOG.error('worker startup code=%s', exc.code if isinstance(exc, WorkerFailure) else 'worker_io_error')
        return 2
    finally:
        if worker:
            worker.close()


if __name__ == '__main__':
    raise SystemExit(main())
