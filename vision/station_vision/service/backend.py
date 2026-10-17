"""Read recording metadata through Backend; open its shared Ubuntu media root."""
import os
from pathlib import Path, PurePosixPath
from urllib.parse import quote

import httpx


class ServiceError(RuntimeError):
    def __init__(self, code, message):
        self.code, self.public_message = code, message
        super().__init__(message)


class BackendPending(ServiceError):
    """Retryable Backend prerequisite; callers retain any completed inference."""
    def __init__(self, code, message, retry_after_s=30):
        super().__init__(code, message)
        self.retry_after_s = retry_after_s


class WritebackPending(BackendPending):
    """Inference is already durable; future attempts may only publish it."""
    def __init__(self, cause, result):
        super().__init__(cause.code, cause.public_message, cause.retry_after_s)
        self.result = result


class BackendClient:
    def __init__(self, config):
        self.settings = config.values['backend']

    def get_recording(self, recording_id):
        token = os.environ.get(self.settings['token_env']) if self.settings.get('auth_mode', 'none') == 'bearer' else None
        if self.settings.get('auth_mode', 'none') == 'bearer' and not token:
            raise ServiceError('backend_credentials_missing', 'Set the configured Backend token environment variable')
        headers = {'Authorization': 'Bearer ' + token} if token else {}
        url = self.settings['base_url'].rstrip('/') + '/api/v1/recordings/' + quote(recording_id, safe='')
        try:
            with httpx.Client(timeout=self.settings['timeout_s'], follow_redirects=False) as client:
                response = client.get(url, headers=headers)
                if response.status_code != 200:
                    raise ServiceError('backend_http_' + str(response.status_code), 'Backend recording metadata request failed')
                record = response.json()['recording']
        except (httpx.HTTPError, ValueError, KeyError, TypeError):
            raise ServiceError('backend_read_failed', 'Backend recording metadata could not be read') from None
        if not isinstance(record, dict):
            raise ServiceError('backend_invalid_recording', 'Backend did not return a recording object')
        endpoint = self.settings.get('source_endpoint')
        if record.get('storage_key') is None and endpoint:
            url = self.settings['base_url'].rstrip('/') + endpoint.replace('{recording_id}', quote(recording_id, safe=''))
            try:
                with httpx.Client(timeout=self.settings['timeout_s'], follow_redirects=False) as client:
                    response = client.get(url, params={'camera_id': record.get('camera_id')}, headers=headers)
                    if response.status_code != 200:
                        raise ServiceError('backend_source_http_' + str(response.status_code),
                            'Backend internal recording-source endpoint unavailable; merge the read-only source route')
                    source = response.json()['source']
                if source['recording_id'] != record['recording_id'] or source['camera_id'] != record['camera_id']:
                    raise ServiceError('backend_source_mismatch', 'Backend source does not match the recording/camera')
                record = {**record, 'storage_key': source['storage_key']}
            except (httpx.HTTPError, ValueError, KeyError, TypeError):
                raise ServiceError('backend_source_read_failed', 'Backend internal source response could not be read') from None
        return record


def resolve_media(config, recording, job):
    if recording.get('recording_id') != job['recording_id'] or recording.get('camera_id') != job['camera_id']:
        raise ServiceError('recording_camera_mismatch', 'Notification does not match Backend recording/camera IDs')
    if recording.get('media_status') != 'available':
        raise ServiceError('media_not_available', 'Recording media is not available')
    if not isinstance(recording.get('duration_ms'), int) or recording['duration_ms'] <= 0:
        raise ServiceError('invalid_duration', 'Backend recording duration is invalid')
    key = recording.get('storage_key')
    if key is None:
        raise ServiceError('recording_source_not_exposed',
                           'Current Backend metadata hides storage_key; agree an internal media-source API before production use')
    # storage_key is a key relative to the configured mount, never a client path.
    if not isinstance(key, str) or not key or '\\' in key or ':' in key:
        raise ServiceError('invalid_storage_key', 'Backend storage key is not a relative media key')
    relative = PurePosixPath(key)
    if relative.is_absolute() or '..' in relative.parts:
        raise ServiceError('invalid_storage_key', 'Backend storage key leaves the media directory')
    folder = Path(config.values['backend']['media_root'])
    folder = (config.root / folder).resolve()
    path = (folder / Path(*relative.parts)).resolve()
    if not path.is_relative_to(folder):
        raise ServiceError('invalid_storage_key', 'Media symlink leaves the configured directory')
    try:
        with path.open('rb') as stream:
            if not stream.read(1):
                raise ServiceError('media_empty', 'Recording media is empty')
    except PermissionError:
        raise ServiceError('media_permission_denied', 'Vision process cannot read the shared media; check mount ownership/group') from None
    except (FileNotFoundError, IsADirectoryError):
        raise ServiceError('media_missing', 'Recording media is missing from the configured shared media directory') from None
    return path
