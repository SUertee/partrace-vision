"""Local, fenced A-gallery staging. Only Backend-confirmed versions become B's default.

All workers sharing a gallery must share this filesystem and its advisory locks.
Published staging roots are immutable and retained; CURRENT is the sole reader pointer.
"""
from contextlib import contextmanager
import errno
import json
import os
from pathlib import Path
import re
import shutil
import time
from uuid import uuid4

from .worker_protocol import WorkerFailure, private_json, read_json

VERSION = re.compile(r'[0-9a-f]{32}\Z')


def atomic_json(path, value):
    temporary = path.with_name(path.name + '.' + uuid4().hex + '.tmp')
    try:
        private_json(temporary, value)
        with temporary.open('rb') as stream:
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        if os.name == 'posix':
            descriptor = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
            try:
                os.fsync(descriptor)
            finally:
                os.close(descriptor)
    finally:
        temporary.unlink(missing_ok=True)


def current_version(root):
    root = Path(root).resolve()
    pointer = root / 'CURRENT.json'
    if not pointer.exists():
        return None
    value = read_json(pointer)
    version = value.get('version')
    if not isinstance(version, str) or not VERSION.fullmatch(version):
        raise WorkerFailure('gallery_pointer_invalid')
    metadata = read_json(root / 'versions' / (version + '.json'))
    profile_root = Path(metadata.get('profile_root', ''))
    if (metadata.get('version') != version or metadata.get('backend_completed') is not True
            or not profile_root.is_absolute() or profile_root.is_symlink() or not profile_root.is_dir()):
        raise WorkerFailure('gallery_pointer_invalid')
    return metadata


def completed_archive(root):
    version = current_version(root)
    if not version:
        raise WorkerFailure('gallery_missing')
    archive = Path(version['profile_root']) / 'data/person_enrollment/archive.json'
    if not archive.is_file() or archive.is_symlink():
        raise WorkerFailure('gallery_missing')
    return archive


@contextmanager
def exclusive(root, cancelled):
    """Wait without blocking heartbeats; a lost lease abandons the lock wait."""
    root = Path(root).resolve()
    root.mkdir(parents=True, exist_ok=True, mode=0o700)
    path = root / 'publish.lock'
    if path.is_symlink():
        raise WorkerFailure('gallery_pointer_invalid')
    with path.open('a+b') as stream:
        os.chmod(path, 0o600)
        if stream.tell() == 0:
            stream.write(b'0'); stream.flush()
        while True:
            if cancelled():
                raise WorkerFailure('lease_lost')
            try:
                if os.name == 'nt':
                    import msvcrt
                    stream.seek(0)
                    msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
                else:
                    import fcntl
                    fcntl.flock(stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except OSError as error:
                if error.errno not in (errno.EACCES, errno.EAGAIN, errno.EWOULDBLOCK):
                    raise WorkerFailure('gallery_lock_failed') from None
                time.sleep(.05)
        try:
            if cancelled():
                raise WorkerFailure('lease_lost')
            yield
        finally:
            if os.name == 'nt':
                stream.seek(0)
                msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(stream.fileno(), fcntl.LOCK_UN)


def _rewrite_paths(value, before, after):
    if isinstance(value, dict):
        return {key: _rewrite_paths(item, before, after) for key, item in value.items()}
    if isinstance(value, list):
        return [_rewrite_paths(item, before, after) for item in value]
    if isinstance(value, str) and value.startswith(str(before) + os.sep):
        return str(after) + value[len(str(before)):]
    return value


class GalleryPublication:
    """Caller holds exclusive(root) through prepare, inference, complete and promote."""
    def __init__(self, root, job, output):
        self.root, self.job, self.output = Path(root).resolve(), job, Path(output).resolve()
        self.stage = self.output / 'enrollment-staging'
        self.base = None
        self.version = uuid4().hex

    def prepare(self):
        self.base = current_version(self.root)
        if self.base is None and (self.root / 'data/person_enrollment').exists():
            raise WorkerFailure('gallery_requires_migration')
        if self.stage.exists():
            raise WorkerFailure('gallery_staging_conflict')
        if self.base:
            previous = Path(self.base['profile_root'])
            shutil.copytree(previous, self.stage, symlinks=False)
            # Copies must refer to their own images: ProfileStore may move old representatives.
            for path in self.stage.rglob('*.json'):
                value = read_json(path, 64 * 1024 * 1024)
                atomic_json(path, _rewrite_paths(value, previous, self.stage))
        else:
            self.stage.mkdir(mode=0o700)
        private_json(self.output / 'enrollment_stage.json', {
            'job_id': self.job['job_id'], 'camera_id': self.job['camera_id'],
            'config_version': self.job['config_version'], 'attempts': self.job.get('attempts'), 'version': self.version,
            'base_version': self.base['version'] if self.base else None,
            'profile_root': str(self.stage), 'publication_root': str(self.root)})
        return self.stage

    def _already_applied(self, current):
        seen = set()
        while current:
            if current.get('job_id') == self.job['job_id']:
                return True
            parent = current.get('parent_version')
            if parent is None:
                return False
            if not isinstance(parent, str) or not VERSION.fullmatch(parent) or parent in seen:
                raise WorkerFailure('gallery_pointer_invalid')
            seen.add(parent)
            current = read_json(self.root / 'versions' / (parent + '.json'))
        return False

    def promote(self, receipt):
        job = receipt.get('job', {})
        if (job.get('job_id') != self.job['job_id'] or job.get('status') != 'completed'
                or job.get('attempts') != self.job.get('attempts')):
            raise WorkerFailure('gallery_completion_unconfirmed')
        current = current_version(self.root)
        if self._already_applied(current):
            return False  # Repeated old-job promotion must not roll CURRENT back past newer jobs.
        if (current['version'] if current else None) != (self.base['version'] if self.base else None):
            raise WorkerFailure('gallery_base_changed')
        archive = self.stage / 'data/person_enrollment/archive.json'
        if not archive.is_file() or archive.is_symlink():
            raise WorkerFailure('gallery_staging_invalid')
        metadata = {'version': self.version, 'parent_version': self.base['version'] if self.base else None,
            'job_id': self.job['job_id'], 'attempts': job['attempts'], 'backend_completed': True,
            'profile_root': str(self.stage)}
        versions = self.root / 'versions'
        versions.mkdir(exist_ok=True, mode=0o700)
        atomic_json(versions / (self.version + '.json'), metadata)
        atomic_json(self.root / 'CURRENT.json', {'version': self.version})
        private_json(self.output / 'enrollment_published.json', metadata)
        return True
