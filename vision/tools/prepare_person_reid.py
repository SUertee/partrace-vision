"""Prepare pinned MIT OSNet source and official MSMT17 ReID weights; no pip changes."""
from pathlib import Path
import hashlib
import json
import requests

ROOT = Path(__file__).resolve().parents[1]
REVISION = 'f8cd150fdf77e8d9e1ed143b7f308c2c609ded50'
WEIGHT_ID = '1DHgmb6XV4fwG3n-CnCM0zdL9nMsZ9_RF'
WEIGHT_SHA256 = 'e96cbd20ee9cc3c6dcc0e8f4fbba8c8069d47647a5a96a59ce381fb785c54f68'


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def download(url, target, minimum=100):
    if target.exists():
        if target.stat().st_size < minimum:
            raise RuntimeError(f'Incomplete existing file: {target}')
        return
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_suffix(target.suffix + '.partial')
    try:
        with requests.get(url, stream=True, timeout=(15, 60)) as response:
            response.raise_for_status()
            if 'text/html' in response.headers.get('Content-Type', ''):
                raise RuntimeError('Download returned HTML, not model data. Obtain the official file manually.')
            with temporary.open('wb') as stream:
                for block in response.iter_content(1024 * 1024):
                    stream.write(block)
        if temporary.stat().st_size < minimum:
            raise RuntimeError(f'Download too small: {target.name}')
        temporary.replace(target)
    finally:
        temporary.unlink(missing_ok=True)


def main():
    vendor = ROOT / 'station_vision/reid/_vendor'
    raw = f'https://raw.githubusercontent.com/KaiyangZhou/deep-person-reid/{REVISION}'
    source_url = raw + '/torchreid/models/osnet.py'
    license_url = raw + '/LICENSE'
    download(source_url, vendor / 'osnet.py', 10000)
    download(license_url, vendor / 'LICENSE', 500)
    (vendor / '__init__.py').touch(exist_ok=True)
    (vendor / 'SOURCE.json').write_text(json.dumps(dict(
        repository='https://github.com/KaiyangZhou/deep-person-reid', revision=REVISION,
        license='MIT', source_url=source_url, sha256=sha256(vendor / 'osnet.py'),
    ), indent=2), encoding='utf-8')
    weight = ROOT / 'models/reid/osnet_x0_5_msmt17.pth'
    url = f'https://drive.usercontent.google.com/download?id={WEIGHT_ID}&export=download&confirm=t'
    download(url, weight, 1000000)
    if sha256(weight) != WEIGHT_SHA256:
        raise RuntimeError('OSNet weights differ from the verified official file; do not silently reuse them.')
    metadata = dict(architecture='osnet_x0_5', training='MSMT17 combineall',
                    source_page='https://kaiyangzhou.github.io/deep-person-reid/MODEL_ZOO',
                    source_file=f'https://drive.google.com/file/d/{WEIGHT_ID}/view',
                    sha256=sha256(weight), bytes=weight.stat().st_size)
    weight.with_suffix('.json').write_text(json.dumps(metadata, indent=2), encoding='utf-8')
    print(json.dumps(metadata, ensure_ascii=False))


if __name__ == '__main__':
    main()
