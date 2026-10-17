"""Download a pinned Microsoft Florence-2 experiment without changing core models."""
import argparse
from concurrent.futures import ThreadPoolExecutor, TimeoutError
import hashlib
import json
from pathlib import Path
import sys

from station_vision.florence.assets import download_official, digest


REPO = 'microsoft/Florence-2-large-ft'
REVISION = '4a12a2b54b7016a48a22037fbd62da90cd566f2a'
SOURCES = {
    'large-ft': (REPO, REVISION),
    'base-ft': ('microsoft/Florence-2-base-ft', 'f6c1a25888ffc1d945ee8a1a77ac833c7303d46e'),
}
FILES = ('config.json', 'configuration_florence2.py', 'generation_config.json',
         'modeling_florence2.py', 'preprocessor_config.json', 'processing_florence2.py',
         'tokenizer.json', 'tokenizer_config.json', 'vocab.json', 'LICENSE',
         'model.safetensors')


def download_hub(repo, revision, name, folder):
    """Use the Hub's resumable transfer; show buffered Windows file progress."""
    from huggingface_hub import hf_hub_download
    with ThreadPoolExecutor(max_workers=1) as pool:
        future = pool.submit(hf_hub_download, repo, name, revision=revision, local_dir=folder)
        while True:
            try:
                return future.result(timeout=10)
            except TimeoutError:
                # Directory metadata can report zero until Rust closes a file.
                # Opening the temporary file exposes its current readable span.
                for temporary in (folder / '.cache/huggingface/download').glob('*.incomplete'):
                    try:
                        with temporary.open('rb') as stream:
                            span = stream.seek(0, 2)
                        print(f'Temporary weights: {span / 1048576:.1f} MiB; final SHA256 pending', flush=True)
                    except OSError:
                        pass


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--metadata', help='Optional official HF API JSON; otherwise fetch the pinned revision')
    parser.add_argument('--variant', choices=list(SOURCES), default='large-ft')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    if Path(sys.prefix).resolve() != (root / '.venv-track').resolve():
        raise RuntimeError('Use .venv-track')
    repo, revision = SOURCES[args.variant]
    metadata_path = root / args.metadata if args.metadata else root / '.runtime/florence2' / f'official_{args.variant}_{revision}.json'
    if not args.metadata and not metadata_path.exists():
        download_official(f'https://huggingface.co/api/models/{repo}/revision/{revision}?blobs=true',
                          metadata_path, minimum_bytes=64)
    metadata = json.loads(metadata_path.read_text(encoding='utf-8-sig'))
    if metadata.get('sha') != revision or metadata.get('id') != repo:
        raise ValueError('Metadata does not match the pinned official Microsoft repository')
    expected = {item['rfilename']: item for item in metadata['siblings']}
    folder = root / 'models/florence2' / args.variant
    folder.mkdir(parents=True, exist_ok=True)
    manifest_path = folder / 'manifest.json'
    manifest = {'repository': repo, 'revision': revision, 'status': 'preparing',
                'training_performed': False, 'files': {}}
    manifest_path.write_text(json.dumps(manifest, indent=2), encoding='utf-8')
    for name in FILES:
        if name == 'generation_config.json' and name not in expected:
            continue  # Base-ft uses generation defaults in config.json.
        item = expected[name]
        target = folder / name
        size = item['size']
        url = f'https://huggingface.co/{repo}/resolve/{revision}/{name}?download=true'
        if name == 'model.safetensors':
            if target.exists() and target.stat().st_size == size and digest(target) == item['lfs']['sha256']:
                pass
            else:
                download_hub(repo, revision, name, folder)
        else:
            download_official(url, target, minimum_bytes=size)
        if target.stat().st_size != size:
            raise ValueError(f'Unexpected downloaded size: {name}')
        sha = digest(target)
        lfs_sha = item.get('lfs', {}).get('sha256')
        if lfs_sha and lfs_sha != sha:
            raise ValueError(f'Official weight SHA256 mismatch: {name}')
        if not lfs_sha and item.get('blobId'):
            git_blob = hashlib.sha1(f'blob {size}\0'.encode() + target.read_bytes()).hexdigest()
            if git_blob != item['blobId']:
                raise ValueError(f'Official source Git blob mismatch: {name}')
        manifest['files'][name] = {'bytes': size, 'sha256': sha}
        manifest_path.write_text(json.dumps(manifest, indent=2), encoding='utf-8')
        print(f'Verified {name}: {size} bytes', flush=True)
    manifest['status'] = 'ready'
    manifest_path.write_text(json.dumps(manifest, indent=2), encoding='utf-8')
    print('READY ' + str(folder), flush=True)


if __name__ == '__main__':
    main()
