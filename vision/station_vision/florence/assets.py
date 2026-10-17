"""Small shared asset helpers; no YOLO parcel detector dependency."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess


def digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def download_official(url, target, minimum_bytes=10000):
    target = Path(target).resolve()
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists() and target.stat().st_size >= minimum_bytes:
        return
    temporary = target.with_name(target.name + '.download')
    curl = shutil.which('curl.exe') or shutil.which('curl')
    if not curl:
        raise RuntimeError('Official asset download requires curl')
    print('Download ' + target.name, flush=True)
    subprocess.run([curl, '-4', '--fail', '--location', '--silent', '--show-error',
        '--retry', '2', '--retry-all-errors', '--continue-at', '-',
        '--connect-timeout', '15', '--max-time', '600', '--output', str(temporary), url], check=True)
    if temporary.stat().st_size < minimum_bytes:
        raise ValueError('Incomplete asset: ' + target.name)
    os.replace(temporary, target)
