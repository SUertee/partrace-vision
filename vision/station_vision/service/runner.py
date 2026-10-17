"""Run existing algorithms as isolated subprocesses using this Python environment."""
import json
import os
from pathlib import Path
import subprocess
import sys
import time

from .backend import BackendClient, BackendPending, WritebackPending, ServiceError, resolve_media
from ..reid.profile_store import atomic_json, archive_lock, digest, read_archive


class VisionRunner:
    def __init__(self, config, backend=None):
        self.config = config
        self.backend = backend or BackendClient(config)

    def run(self, job, stop_event):
        started = time.perf_counter()
        if self.config.values['backend'].get('mode') == 'recording_jobs':
            from .lan_jobs import LanJobSource
            remote = LanJobSource(self.config, job, stop_event)
            try:
                saved = job.get('result')
                if saved:
                    package = json.loads(Path(saved['result_file']).read_text(encoding='utf-8'))
                    if (package.get('recording_id'),package.get('camera_id'),package.get('analysis_status')) != (job['recording_id'],job['camera_id'],'completed'):
                        raise ServiceError('saved_result_mismatch', 'Saved analysis does not match the notified recording')
                    expected = package.get('remote_source',{}).get('source_sha256')
                    if not expected:
                        raise ServiceError('saved_source_hash_missing', 'Cannot publish saved analysis without its verified source hash')
                    remote.settings.setdefault('source_sha256',{})[job['recording_id']] = expected
                remote.prepare()
                if saved and package.get('config_version') != remote.record.get('config_version'):
                    raise ServiceError('saved_config_mismatch','Saved analysis belongs to a different recording configuration')
                # Local analysis runs first; saved outputs are reused for publication.
                # On every later attempt, reuse the durable result and original IDs.
                result = dict(saved) if saved else self.analyse(job, stop_event, remote.record, remote.source, started)
                work = Path(result['result_file']).parent
                package = json.loads(Path(result['result_file']).read_text(encoding='utf-8'))
                if not saved:
                    package['remote_source'] = remote.metrics
                    package['analysis_elapsed_s'] = result['elapsed_s']
                metrics = package.get('summary', {})
                if job['role']=='B' and not package.get('event_records') and metrics.get('source_frames',0)>0:
                    ratio = metrics.get('registration_valid_frames',0)/metrics['source_frames']
                    minimum = self.config.values.get('b_empty_result_min_registration_ratio',.8)
                    package['quality_check'] = {'registration_valid_ratio':ratio,'minimum_ratio':minimum,
                        'publishable':ratio>=minimum,'scope':'empty_B_result_calibration_coverage'}
                pending = None
                try:
                    if package.get('analysis_status') != 'completed':
                        raise ServiceError('analysis_incomplete','Partial analysis is saved locally and cannot be published')
                    if package.get('quality_check',{}).get('publishable') is False:
                        raise ServiceError('calibration_coverage_insufficient',
                            'Local analysis finished but calibration coverage is too low to publish an empty result')
                    remote.prepare_publication()
                    atomic_json(work / 'backend_config.json', remote.backend_config)
                    publication = remote.publish(package, work)
                except BackendPending as exc:
                    pending = exc
                    publication = {'status':'waiting_backend','error':{'code':exc.code,'message':exc.public_message},
                                   'analysis_reused':bool(saved)}
                except ServiceError as exc:
                    publication = {'status':'blocked_quality' if exc.code=='calibration_coverage_insufficient' else 'failed',
                                   'error':{'code':exc.code,'message':exc.public_message}}
                package['writeback'] = publication
                package['writeback_status'] = publication['status']
                package['publication_attempt_elapsed_s'] = round(time.perf_counter()-started,3)
                if not saved:
                    package['elapsed_s'] = package['publication_attempt_elapsed_s']
                atomic_json(work / 'result_package.json', package)
                atomic_json(work / 'writeback.json', publication)
                result.update(writeback_status=publication['status'])
                if pending:
                    raise WritebackPending(pending, result)
                return result
            finally:
                remote.__exit__(None,None,None)
        record = self.backend.get_recording(job['recording_id'])
        source = resolve_media(self.config, record, job)
        return self.analyse(job, stop_event, record, source, started)

    def analyse(self, job, stop_event, record, source, started):
        config = self.config
        run_name = f"vision_{job['role']}_{job['job_id']}_r{job['attempt']}"
        output = config.root / 'outputs' / run_name
        work = config.root / 'outputs' / 'vision_service' / job['job_id'] / f"attempt_{job['attempt']}"
        work.mkdir(parents=True, exist_ok=False)
        atomic_json(work / 'recording.json', record)
        runtime = work / 'runtime_parameters.json'
        runtime.write_bytes(config.path('runtime_config').read_bytes())
        args = [sys.executable, '-X', 'utf8', '-m']
        gallery_info = None
        if job['role'] == 'A':
            args += ['station_vision.reid.enroll_video', '--archive-dir', str(config.archive.parent)]
            flag = job.get('options', {}).get('left2right_flag')
            if type(flag) is not bool:
                raise ServiceError('a_direction_flag_missing', 'A task requires left2right_flag; legacy task was not guessed')
            args += ['--left2right-flag', str(flag).lower()]
        else:
            calibration = self.calibration()
            frozen_calibration = work / 'shelf_calibration.json'
            frozen_calibration.write_bytes(calibration.read_bytes())
            frozen_gallery = work / 'person_archive_input.json'
            # Load only when B STARTS, after accepted A dependencies completed.
            # Freeze once: ongoing B never switches appearance identities.
            with archive_lock(config.archive.parent):
                if not config.archive.is_file():
                    raise ServiceError('archive_missing', 'Complete A enrollment before processing B')
                data = read_archive(config.archive)
                source_hash = digest(config.archive)
                atomic_json(frozen_gallery, data)
            gallery_info = {'source': str(config.archive), 'source_sha256': source_hash,
                'snapshot': str(frozen_gallery), 'snapshot_sha256': digest(frozen_gallery),
                'profile_count': len(data['profiles']), 'policy': 'latest_completed_archive_at_B_start'}
            atomic_json(work / 'gallery_version.json', gallery_info)
            atomic_json(work / 'calibration_version.json', {'source': str(calibration), 'sha256': digest(frozen_calibration)})
            args += ['station_vision.shelf_session.pipeline', '--config', str(frozen_calibration), '--gallery', str(frozen_gallery)]
            if config.values['review_with_qwen']:
                args += ['--review']
        args += ['--video', str(source), '--device', config.values['device'],
                 '--runtime-config', str(runtime), '--run-name', run_name]
        self.execute(args, work / 'process.log', stop_event)
        summary = json.loads((output / 'summary.json').read_text(encoding='utf-8'))
        if summary.get('status') not in ('completed', 'partial'):
            raise ServiceError('analysis_incomplete', 'Analysis did not complete; inspect its summary')
        result = self.package(job, record, output, work, summary, gallery_info,
                              config.values.get('default_zone_id', 'E3'))
        result['elapsed_s'] = round(time.perf_counter() - started, 3)
        atomic_json(work / 'result_package.json', result)
        return {'analysis_status': summary['status'], 'writeback_status': 'pending_backend_contract',
                'output_dir': str(output), 'result_file': str(work / 'result_package.json'),
                'gallery': gallery_info, 'elapsed_s': result['elapsed_s']}

    def calibration(self):
        setting = self.config.values['b_calibration']
        if setting['path']:
            result = (self.config.root / setting['path']).resolve()
        else:
            files = list(self.config.root.glob(setting['latest_glob']))
            files = [p for p in files if p.is_file()]
            if not files:
                raise ServiceError('calibration_missing', 'Save B shelf calibration or configure b_calibration.path')
            result = max(files, key=lambda p: (p.stat().st_mtime_ns, p.name))
        if not result.is_file():
            raise ServiceError('calibration_missing', 'Configured B calibration file is missing')
        return result

    def execute(self, args, log_path, stop_event):
        try:
            self._execute(args, log_path, stop_event)
        finally:
            # Redact failure logs as well as successful runs.
            if log_path.is_file():
                text = log_path.read_text(encoding='utf-8', errors='replace')
                for name in ('STATION_VISION_SERVICE_TOKEN', self.config.values['backend']['token_env'], 'STATION_VLM_API_KEY'):
                    value = os.environ.get(name)
                    if value:
                        text = text.replace(value, '[REDACTED]')
                log_path.write_text(text, encoding='utf-8')

    def _execute(self, args, log_path, stop_event):
        env = dict(os.environ, PYTHONUNBUFFERED='1', PYTHONUTF8='1')
        started = time.monotonic()
        with log_path.open('x', encoding='utf-8') as log:
            flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
            process = subprocess.Popen(args, cwd=self.config.root, env=env, stdout=log,
                                       stderr=subprocess.STDOUT, creationflags=flags)
            try:
                while process.poll() is None:
                    if stop_event.wait(.2):
                        raise ServiceError('service_interrupted', 'Service stopped during analysis; explicit retry required')
                    if time.monotonic() - started > self.config.values['job_timeout_s']:
                        raise ServiceError('analysis_timeout', 'Analysis exceeded job_timeout_s')
                if process.returncode:
                    raise ServiceError('analysis_process_failed', 'Analysis failed; inspect process.log and summary.json')
            finally:
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=5)

    @staticmethod
    def package(job, record, output, work, summary, gallery, default_zone_id='E3'):
        """Local transfer package. NOT the existing immutable POST /recordings schema."""
        if job['role'] == 'A':
            payload = {'profiles': json.loads((output / 'profiles_result.json').read_text(encoding='utf-8')),
                       'direction_events': json.loads((output / 'direction_events.json').read_text(encoding='utf-8'))}
            evidence = []
            for track in payload['profiles']:
                for frame in track.get('keyframes', []):
                    current_raw = output / 'evidence' / (frame['frame_id'] + '.png')
                    current_crop = output / 'crops' / track['track_id'] / (frame['frame_id'] + '.png')
                    evidence.append({'person_id': track.get('person_id'), 'track_id': track['track_id'],
                                     'frame_id': frame['frame_id'], 'offset_ms': frame['time_ms'],
                                     'crop_path': str(current_crop) if current_crop.is_file() else frame.get('archive_crop'),
                                     'raw_path': str(current_raw) if current_raw.is_file() else frame.get('raw_frame_path')})
        else:
            payload = {'events': json.loads((output / 'events.json').read_text(encoding='utf-8')),
                       'details': json.loads((output / 'event_details.json').read_text(encoding='utf-8'))}
            evidence = []
            for detail in payload['details']:
                for f in detail['keyframes']:
                    evidence.append({'interaction_id': detail['session']['id'], 'frame_id': f['frame_id'],
                        'offset_ms': f['actual_ms'], 'role': f['role'], 'sha256': f['raw_sha256'],
                        'raw_path': str(output / f['raw_path'])})
        from .artifacts import records
        event_records, evidence_records = records(job, output, work, payload, default_zone_id)
        return {'format': 'station_vision_result_v2', 'job_id': job['job_id'], 'role': job['role'],
                'recording_id': job['recording_id'], 'camera_id': job['camera_id'],
                'config_version': record.get('config_version'), 'analysis_status': summary['status'],
                'writeback_status': 'pending_backend_contract', 'gallery': gallery,
                'payload': payload, 'evidence': evidence, 'summary': summary,
                'event_records': event_records, 'evidence_records': evidence_records,
                'media_storage_scope': 'vision_local_paths_not_yet_published_by_backend',
                'review_details_path': str(output/'event_details.json') if job['role']=='B' else None,
                'output_dir': str(output), 'result_file': str(work / 'result_package.json')}
