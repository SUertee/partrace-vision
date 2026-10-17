"""Fetch the notified recording and publish its saved result directly."""
from contextlib import ExitStack
from datetime import datetime, timezone
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import ssl
import threading
import time
from urllib.parse import quote, urlparse
import uuid

import httpx

from .backend import BackendPending, ServiceError
from ..reid.profile_store import atomic_json, digest


def private_path(value, root):
    return (root / Path(os.path.expandvars(value))).resolve()


def make_manifest(package, record, default_zone_id='E3'):
    """Translate measured local outputs, keeping stable IDs and real image bytes."""
    if package['analysis_status'] != 'completed':
        raise ServiceError('analysis_incomplete', 'Partial analysis cannot be published as final facts')
    if package.get('quality_check', {}).get('publishable') is False:
        raise ServiceError('calibration_coverage_insufficient', 'Insufficient calibration coverage cannot be published as an empty negative result')
    events, evidence, files = [], [], []
    duration = record['duration_ms']
    for row in package['event_records']:
        start, end = row['start_offset_ms'], row['end_offset_ms']
        if not 0 <= start <= end <= duration:
            raise ServiceError('event_time_invalid', 'Event offsets leave the source video')
        events.append({k: row.get(k) for k in (
            'event_id', 'track_key', 'person_id', 'event_type', 'shelf_level',
            'observation_status', 'start_offset_ms', 'end_offset_ms', 'detection_source', 'summary')})
        events[-1]['zone_id'] = row.get('zone_id') or default_zone_id
    allowed = {e['event_id'] for e in events}
    for row in package['evidence_records']:
        if row['event_id'] not in allowed or not 0 <= row['offset_ms'] <= duration:
            raise ServiceError('evidence_relation_invalid', 'Evidence is not in this recording/result')
        path = Path(row['storage_key'])
        if not path.is_file() or path.suffix.lower() not in ('.png', '.jpg', '.jpeg'):
            raise ServiceError('evidence_missing', 'Selected JPEG/PNG evidence is missing')
        # The manifest has safe unique basenames, never an algorithm PC path.
        filename = row['evidence_id'] + path.suffix.lower()
        if not re.fullmatch(r'[A-Za-z0-9_.-]+', filename):
            raise ServiceError('evidence_name_invalid', 'Unsafe evidence file name')
        evidence.append({'evidence_id': row['evidence_id'], 'event_id': row['event_id'],
                         'offset_ms': row['offset_ms'], 'bbox_json': None, 'file_name': filename})
        files.append({'file_name': filename, 'path': str(path), 'sha256': digest(path)})
    manifest = {'schema_version': '0.3-mvp-draft', 'recording': {
        'recording_id': record['recording_id'], 'camera_id': record['camera_id'],
        'config_version': record['config_version'], 'source_mode': record['source_mode'],
        'duration_ms': duration, 'started_at': None, 'file_name': 'source.mp4'},
        'events': events, 'evidence': evidence}
    return manifest, files


class TaskStop:
    """A lost task lease interrupts this task, without stopping the whole queue."""
    def __init__(self, parent, lost):
        self.parent, self.lost = parent, lost

    def is_set(self):
        return self.parent.is_set() or self.lost.is_set()

    def wait(self, timeout):
        until = time.monotonic() + timeout
        while not self.is_set():
            remaining = until - time.monotonic()
            if remaining <= 0:
                return False
            self.parent.wait(min(remaining, .05))
        return True


class LanJobSource:
    def __init__(self, config, local_job, stop_event):
        self.config, self.local_job = config, local_job
        self.settings = config.values['backend']
        self.base = self.settings['base_url'].rstrip('/')
        self.publication_mode = self.settings.get('publication_mode', 'direct')
        self.token = None
        if self.settings.get('auth_mode', 'bearer') == 'bearer':
            self.token = os.environ.get(self.settings['token_env'])
            token_file = self.settings.get('token_files', {}).get(local_job['role'])
            if token_file:
                try:
                    self.token = private_path(token_file, config.root).read_text(encoding='utf-8-sig').strip()
                except OSError:
                    raise ServiceError('backend_credentials_missing', 'LAN API token file is unavailable') from None
            if not self.token:
                raise ServiceError('backend_credentials_missing', 'Configure the LAN API token outside the project')
        parsed = urlparse(self.base)
        if parsed.scheme == 'http':
            try:
                private = ipaddress.ip_address(parsed.hostname).is_private
            except ValueError:
                private = parsed.hostname == 'localhost'
            if not self.settings.get('allow_lan_http', False) or not private:
                raise ServiceError('lan_http_disabled', 'Use verified HTTPS or explicitly configured private LAN HTTP')
        ca = self.settings.get('ca_file')
        context = ssl.create_default_context(cafile=str(private_path(ca, config.root)) if ca else None)
        self.client = httpx.Client(verify=context, trust_env=False, follow_redirects=False,
                                  timeout=self.settings['timeout_s'])
        self.headers = {'Authorization': 'Bearer ' + self.token} if self.token else {}
        self.lease = None
        self.done = threading.Event()
        self.lost = threading.Event()
        self.task_stop = TaskStop(stop_event, self.lost)
        self.thread = None
        self.metrics = {'heartbeat_count': 0}
        self.published = False
        self.publication_unknown = False
        self.last_error = None
        self.complete_response = None

    def request(self, method, path, *, leased=False, **kwargs):
        headers = dict(self.headers)
        if leased:
            headers['X-Lease-Token'] = self.lease
        try:
            response = self.client.request(method, self.base + path, headers=headers, **kwargs)
        except httpx.HTTPError:
            raise ServiceError('backend_connection_failed', 'LAN Backend request could not complete') from None
        if path.endswith('/complete'):
            try:
                body = response.json()
            except ValueError:
                body = {'non_json_body': response.text[:8000]}
            def redact(value):
                if isinstance(value, dict):
                    return {k: ('[REDACTED]' if any(s in k.lower() for s in ('authorization', 'lease_token', 'x-lease-token', 'api_key', 'password')) else redact(v)) for k, v in value.items()}
                if isinstance(value, list):
                    return [redact(v) for v in value]
                if isinstance(value, str):
                    for secret in (self.token, self.lease):
                        if secret:
                            value = value.replace(secret, '[REDACTED]')
                return value
            self.complete_response = {'http_status': response.status_code, 'body': redact(body)}
        if response.status_code >= 300:
            try:
                error = response.json().get('error', {})
                code = error.get('code', '')
            except ValueError:
                error, code = {}, ''
            safe = code if isinstance(code, str) and re.fullmatch('[a-z][a-z0-9_]{0,99}', code) else ''
            message = error.get('message', '')
            message = message[:500] if isinstance(message, str) else ''
            for secret in (self.token, self.lease):
                if secret:
                    message = message.replace(secret, '[REDACTED]')
            self.last_error = {'http_status': response.status_code, 'path': path,
                               'code': safe, 'message': message, 'request_id': error.get('request_id')}
            raise ServiceError('backend_http_' + str(response.status_code),
                               'LAN Backend rejected request' + (': ' + safe if safe else '') + ('; ' + message if message else ''))
        try:
            return response.json()
        except ValueError:
            raise ServiceError('backend_response_invalid', 'Expected a Backend JSON response') from None

    def job_path(self, suffix=''):
        return '/api/v1/recording-jobs/' + quote(self.remote['job_id'], safe='') + suffix

    def pending(self, code, message):
        return BackendPending(code, message, self.settings.get('wait_retry_s', 30))

    def require_exact_claim(self):
        """Never try a global queue claim against an incompatible deployed API."""
        try:
            spec = self.request('GET', '/openapi.json')
            schema = spec['paths']['/api/v1/recording-jobs/claim']['post']['requestBody']['content']['application/json']['schema']
            if '$ref' in schema:
                schema = spec['components']['schemas'][schema['$ref'].rsplit('/', 1)[-1]]
            if not {'recording_id', 'camera_id'}.issubset(schema.get('properties', {})):
                raise KeyError('exact fields absent')
        except (KeyError, TypeError, ServiceError):
            raise self.pending('backend_exact_claim_unavailable',
                'Backend claim must declare recording_id and camera_id before saved results can be published') from None

    @staticmethod
    def expired_lease(target):
        try:
            expiry = datetime.fromisoformat(target['lease_expires_at'].replace('Z', '+00:00'))
            return expiry <= datetime.now(timezone.utc)
        except (KeyError, TypeError, ValueError):
            return False

    def prepare(self):
        """Read/download the exact source without claiming or changing Backend state."""
        rid = self.local_job['recording_id']
        self.record = self.request('GET', '/api/v1/recordings/' + quote(rid, safe=''))['recording']
        if (self.record.get('recording_id'), self.record.get('camera_id')) != (rid, self.local_job['camera_id']):
            raise ServiceError('recording_camera_mismatch', 'Notification does not match the recording metadata')
        if self.record.get('media_status') != 'available':
            raise ServiceError('backend_media_missing', 'The notified recording has no available video')
        self.source = self.download()
        return self

    def acquire(self):
        """Acquire only the notified task, for heartbeat and final publication."""
        rid = self.local_job['recording_id']
        if not hasattr(self, 'record'):
            raise ServiceError('backend_source_not_prepared', 'Prepare and verify the source before publication')
        return self._acquire(rid)

    def prepare_publication(self):
        """Direct mode verifies IDs only; never claims, leases, or gates on status."""
        if self.publication_mode == 'leased':
            return self.acquire()
        if not hasattr(self, 'record'):
            raise ServiceError('backend_source_not_prepared', 'Prepare and verify the source before publication')
        rid = self.local_job['recording_id']
        self.remote = self.request('GET', '/api/v1/recording-jobs/' + quote(rid, safe=''))['job']
        if (self.remote.get('recording_id'), self.remote.get('camera_id')) != (rid, self.local_job['camera_id']):
            raise ServiceError('recording_camera_mismatch', 'Notification does not match the remote job')
        for key in ('config_version', 'duration_ms', 'source_mode'):
            if self.remote.get(key) != self.record.get(key):
                raise ServiceError('backend_source_changed', 'Recording metadata changed before publication')
        self.backend_config = self.request('GET', '/api/v1/configs/' + quote(self.record['config_version'], safe=''))['config']
        return self

    def _acquire(self, rid):
        target = self.request('GET', '/api/v1/recording-jobs/' + quote(rid, safe=''))['job']
        if (target['recording_id'], target['camera_id']) != (rid, self.local_job['camera_id']):
            raise ServiceError('recording_camera_mismatch', 'Notification does not match the remote job')
        if target['status'] == 'processing' and not self.expired_lease(target):
            raise self.pending('backend_job_busy', 'The notified recording already has an active Backend lease')
        if target['status'] not in ('queued', 'processing'):
            raise ServiceError('backend_job_not_queued', 'Remote task must be queued before this notification')
        self.require_exact_claim()
        claim_body = {'worker_id': self.settings.get('worker_id', 'station-vision-lan'),
                      'recording_id': rid, 'camera_id': self.local_job['camera_id']}
        try:
            claimed = self.request('POST', '/api/v1/recording-jobs/claim', json=claim_body)
        except ServiceError as exc:
            if exc.code in ('backend_http_409', 'backend_http_422', 'backend_http_503', 'backend_connection_failed'):
                raise self.pending('backend_exact_claim_pending', exc.public_message) from None
            raise
        remote = claimed.get('job')
        if remote is None:
            raise self.pending('backend_job_busy', 'Exact Backend claim did not grant the notified recording')
        if (remote.get('job_id'), remote.get('recording_id'), remote.get('camera_id')) != (target['job_id'], rid, self.local_job['camera_id']):
            raise ServiceError('backend_claim_mismatch',
                'Exact Backend claim returned another recording/camera; no results were published')
        if not isinstance(claimed.get('lease_token'), str) or not claimed['lease_token']:
            raise ServiceError('backend_lease_invalid', 'Exact claim returned no valid task lease')
        self.remote, self.lease = remote, claimed['lease_token']
        for key in ('config_version', 'duration_ms', 'source_mode'):
            if remote.get(key) != self.record.get(key):
                raise ServiceError('backend_source_changed', 'Recording metadata changed during exact claim')
        self.thread = threading.Thread(target=self.heartbeat_loop, daemon=True, name='lan-task-heartbeat')
        self.thread.start()
        self.backend_config = self.request('GET', '/api/v1/configs/' + quote(remote['config_version'], safe=''))['config']
        return self

    def __enter__(self):
        try:
            self.prepare()
            return self.prepare_publication()
        except Exception:
            self.__exit__(None, None, None)
            raise

    def heartbeat_loop(self):
        while not self.done.wait(self.settings.get('heartbeat_interval_s', 10)):
            if self.published:
                return
            try:
                self.request('POST', self.job_path('/heartbeat'), leased=True, json={})
                self.metrics['heartbeat_count'] += 1
            except ServiceError:
                if not self.published:
                    self.lost.set()
                return

    def check(self):
        if self.lost.is_set():
            raise ServiceError('backend_lease_lost', 'Lease renewal failed; do not publish stale results')
        if self.task_stop.parent.is_set():
            raise ServiceError('service_interrupted', 'Service stopped during the remote task')

    def download(self):
        started = time.perf_counter()
        rid = self.record['recording_id']
        folder = self.config.state_dir / 'media' / hashlib.sha256(rid.encode()).hexdigest()[:24]
        folder.mkdir(parents=True, exist_ok=True)
        path = folder / 'source.mp4'
        receipt_path = folder / 'source_receipt.json'
        binding = {k: self.record.get(k) for k in ('recording_id', 'camera_id', 'config_version', 'duration_ms', 'created_at')}
        expected = self.settings.get('source_sha256', {}).get(rid)
        if path.is_file() and receipt_path.is_file():
            try:
                receipt = json.loads(receipt_path.read_text(encoding='utf-8'))
                checksum = digest(path)
                if (receipt['recording'] == binding and receipt['source_bytes'] == path.stat().st_size
                        and receipt['source_sha256'] == checksum and (not expected or expected == checksum)):
                    self.metrics.update(download_elapsed_s=round(time.perf_counter()-started, 3),
                        source_bytes=path.stat().st_size, source_sha256=checksum, source_path=str(path),
                        source_mode='recording_id_http_download', download_reused=True)
                    return path
            except (OSError, ValueError, KeyError, TypeError):
                pass
        partial = folder / ('source.' + uuid.uuid4().hex + '.part')
        checksum, size = hashlib.sha256(), 0
        headers = dict(self.headers)
        limit = self.settings.get('max_download_bytes', 512 * 1024 * 1024)
        try:
            with self.client.stream('GET', self.base + '/api/v1/recordings/' + quote(rid, safe='') + '/media', headers=headers) as response:
                if response.status_code != 200:
                    raise ServiceError('backend_media_http_' + str(response.status_code), 'LAN original video download failed')
                if response.headers.get('content-type', '').split(';')[0] not in ('video/mp4', 'application/octet-stream'):
                    raise ServiceError('backend_media_type_invalid', 'Expected original MP4 bytes')
                with partial.open('xb') as stream:
                    for chunk in response.iter_bytes(256 * 1024):
                        self.check()
                        size += len(chunk)
                        if size > limit:
                            raise ServiceError('backend_media_too_large', 'Video exceeds configured download limit')
                        checksum.update(chunk)
                        stream.write(chunk)
                length = response.headers.get('content-length')
                if size == 0 or (length and size != int(length)):
                    raise ServiceError('backend_media_incomplete', 'Original video download is incomplete')
            if expected and checksum.hexdigest() != expected:
                raise ServiceError('backend_media_hash_mismatch', 'Downloaded original differs from the handoff checksum')
            partial.replace(path)
            atomic_json(receipt_path, {'recording': binding, 'source_sha256': checksum.hexdigest(), 'source_bytes': size})
        except httpx.HTTPError:
            raise ServiceError('backend_media_connection_failed', 'Original video download was interrupted') from None
        finally:
            if partial.exists():
                partial.unlink()
        self.metrics.update(download_elapsed_s=round(time.perf_counter() - started, 3),
                            source_bytes=size, source_sha256=checksum.hexdigest(), source_path=str(path),
                            source_mode='recording_id_http_download', download_reused=False)
        return path

    def publish(self, package, work):
        """One final measured manifest. A failed/unknown publication stays explicit."""
        started = time.perf_counter()
        try:
            self.check()
            manifest, files = make_manifest(package, self.record, self.config.values.get('default_zone_id', 'E3'))
            if self.publication_mode == 'direct':
                manifest = {key: manifest[key] for key in ('events', 'evidence')}
            atomic_json(work / 'backend_manifest.json', manifest)
            atomic_json(work / 'backend_files.json', files)
            with ExitStack() as stack:
                multipart = [('manifest', (None, json.dumps(manifest, ensure_ascii=False, separators=(',', ':')), 'application/json'))]
                for item in files:
                    if digest(item['path']) != item['sha256']:
                        raise ServiceError('evidence_changed', 'Evidence changed before publication')
                    stream = stack.enter_context(Path(item['path']).open('rb'))
                    mime = 'image/png' if item['file_name'].endswith('.png') else 'image/jpeg'
                    multipart.append(('files', (item['file_name'], stream, mime)))
                response = self.request('POST', self.job_path('/complete'),
                                        leased=self.publication_mode == 'leased', files=multipart)
            self.published = True
            state = self.request('GET', self.job_path())['job']
            if state['status'] != 'completed' or state['event_count'] != len(manifest['events']) or state['evidence_count'] != len(files):
                raise ServiceError('backend_readback_mismatch', 'Published job readback does not match the result')
            verified = []
            for item, evidence in zip(files, manifest['evidence']):
                path = '/api/v1/evidence/' + quote(evidence['evidence_id'], safe='') + '/media'
                r = self.client.get(self.base + path, headers=self.headers)
                if r.status_code != 200 or hashlib.sha256(r.content).hexdigest() != item['sha256']:
                    raise ServiceError('backend_evidence_readback_failed', 'Uploaded evidence bytes could not be verified')
                verified.append(evidence['evidence_id'])
            return {'status': 'published', 'remote_job': state, 'response': response,
                    'submission_mode': 'direct_complete_no_claim_no_lease' if self.publication_mode == 'direct' else 'leased_complete',
                    'complete_response': self.complete_response,
                    'evidence_bytes_verified': verified, 'elapsed_s': round(time.perf_counter() - started, 3)}
        except ServiceError as exc:
            # A timeout may follow a committed transaction. Do not fail a remote
            # completed job or recompute/randomize a second result blindly.
            if exc.code == 'backend_connection_failed':
                self.publication_unknown = True
            return {'status': 'verification_failed' if self.published else ('unknown' if self.publication_unknown else 'failed'),
                    'error': {'code': exc.code, 'message': exc.public_message},
                    'server_error': self.last_error,
                    'complete_response': self.complete_response,
                    'elapsed_s': round(time.perf_counter() - started, 3)}
        except httpx.HTTPError:
            return {'status': 'verification_failed', 'error': {'code': 'backend_readback_connection_failed',
                    'message': 'Published result could not be read back'}, 'elapsed_s': round(time.perf_counter() - started, 3)}

    def __exit__(self, *args):
        self.done.set()
        if self.thread:
            self.thread.join(timeout=self.settings['timeout_s'] + 1)
        if self.lease and not self.published and not self.publication_unknown and not self.lost.is_set():
            try:
                self.request('POST', self.job_path('/fail'), leased=True, json={'code': 'vision_result_not_published'})
            except ServiceError:
                pass
        self.client.close()
        return False
