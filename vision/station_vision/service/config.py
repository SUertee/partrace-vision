"""JSON settings for the single-worker, shared-directory deployment."""
from dataclasses import dataclass
import json
from pathlib import Path
from urllib.parse import urlparse


@dataclass(frozen=True)
class ServiceConfig:
    root: Path
    values: dict

    def path(self, key):
        return (self.root / self.values[key]).resolve()

    @property
    def archive(self):
        return self.path('archive_dir') / 'archive.json'

    @property
    def state_dir(self):
        return self.path('state_dir')


def load_config(root, filename='configs/vision_service.json'):
    root = Path(root).resolve()
    data = json.loads((root / filename).read_text(encoding='utf-8-sig'))
    required = {'host', 'port', 'backend', 'camera_roles',
                'device', 'archive_dir', 'state_dir', 'runtime_config',
                'b_calibration', 'review_with_qwen', 'poll_interval_s', 'job_timeout_s'}
    if not required.issubset(data):
        raise ValueError('Incomplete vision service JSON')
    if data['device'] not in ('cpu', 'cuda'):
        raise ValueError('device must be cpu or cuda')
    if not data['camera_roles'] or set(data['camera_roles'].values()) - {'A', 'B'}:
        raise ValueError('camera_roles must map each camera ID to A or B')
    if not isinstance(data['review_with_qwen'], bool):
        raise ValueError('review_with_qwen must be a JSON boolean')
    if not 1 <= data['port'] <= 65535 or not .05 <= data['poll_interval_s'] <= 10:
        raise ValueError('Invalid port/poll_interval_s')
    if not 10 <= data['job_timeout_s'] <= 86400:
        raise ValueError('job_timeout_s must be 10..86400')
    if not 0 <= data.get('b_empty_result_min_registration_ratio',.8) <= 1:
        raise ValueError('B empty result registration ratio must be 0..1')
    backend = data['backend']
    if backend.get('mode', 'shared_media') not in ('shared_media', 'recording_jobs'):
        raise ValueError('Unsupported Backend mode')
    if backend.get('mode') == 'recording_jobs':
        if backend.get('publication_mode', 'direct') not in ('direct', 'leased'):
            raise ValueError('Backend publication_mode must be direct or leased')
        if backend.get('auth_mode', 'bearer') not in ('bearer', 'none'):
            raise ValueError('Backend auth_mode must be bearer or none')
        if not 1 <= backend.get('heartbeat_interval_s', 10) <= 20:
            raise ValueError('Heartbeat interval must be 1..20 seconds')
        if not 5 <= backend.get('wait_retry_s', 30) <= 300:
            raise ValueError('Backend wait_retry_s must be 5..300 seconds')
        if not isinstance(backend.get('token_files', {}), dict):
            raise ValueError('token_files must map roles to private token files')
        if not 1024 <= backend.get('max_download_bytes', 512*1024*1024) <= 1024*1024*1024:
            raise ValueError('Invalid original video download limit')
    if data.get('default_zone_id', 'E3') is not None and not isinstance(data.get('default_zone_id', 'E3'), str):
        raise ValueError('default_zone_id must be a zone ID or null')
    parsed = urlparse(backend['base_url'])
    if parsed.scheme not in ('http', 'https') or not parsed.netloc or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError('Invalid Backend base URL')
    if not 1 <= backend['timeout_s'] <= 120 or not backend.get('token_env'):
        raise ValueError('Invalid Backend timeout/token environment variable')
    if not isinstance(backend.get('media_root'), str) or not backend['media_root']:
        raise ValueError('backend.media_root must name the shared media directory')
    endpoint = backend.get('source_endpoint')
    if endpoint is not None and (not isinstance(endpoint,str) or not endpoint.startswith('/api/') or endpoint.count('{recording_id}')!=1 or '?' in endpoint or '#' in endpoint or '://' in endpoint):
        raise ValueError('backend.source_endpoint must be an API path with one {recording_id} placeholder')
    calibration = data['b_calibration']
    if not isinstance(calibration.get('path'), str) or not isinstance(calibration.get('latest_glob'), str) or not calibration['latest_glob']:
        raise ValueError('b_calibration requires path and latest_glob strings')
    for key in ('archive_dir', 'state_dir', 'runtime_config'):
        if not isinstance(data[key], str) or not data[key]:
            raise ValueError('Empty service path: ' + key)
    return ServiceConfig(root, data)
