"""Persist completed observations separately from model review success."""
import hashlib
import json
import math
from pathlib import Path


CACHE_FILES = ('config_snapshot.json','observations.jsonl','people.json','candidates.json')


def digest(path):
    h=hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda:stream.read(4*1024*1024),b''):
            h.update(chunk)
    return h.hexdigest()


def validate_rows(rows, count):
    if not rows or len(rows)!=count:
        raise ValueError('Incomplete observation cache')
    previous=-1.
    for i,row in enumerate(rows):
        time_ms=row['time_ms']
        if (row['index']!=i or row['frame_id']!=f'f{i:06d}' or
                not math.isfinite(time_ms) or time_ms<0 or time_ms<=previous):
            raise ValueError('Observation frame IDs or timestamps are invalid')
        previous=time_ms


def write_checkpoint(output,source,rows,info):
    checkpoint={'version':1,'status':'observations_completed','source_path':str(source),
        'source_sha256':digest(source),'source_frames':len(rows),'info':info,
        'files':{name:digest(output/name) for name in CACHE_FILES}}
    (output/'observation_checkpoint.json').write_text(
        json.dumps(checkpoint,ensure_ascii=False,indent=2),encoding='utf-8')
    return checkpoint


def load_observations(root,prior,config):
    oldconfig=json.loads((prior/'config_snapshot.json').read_text(encoding='utf-8-sig'))
    if oldconfig!=config:
        raise ValueError('Observation reuse requires identical config and source hash')
    source=(root/config['video']).resolve()
    checkpoint_path=prior/'observation_checkpoint.json'
    if checkpoint_path.exists():
        checkpoint=json.loads(checkpoint_path.read_text(encoding='utf-8'))
        if checkpoint.get('version')!=1 or checkpoint.get('status')!='observations_completed':
            raise ValueError('Invalid observation checkpoint')
        for name in CACHE_FILES:
            if digest(prior/name)!=checkpoint['files'].get(name):
                raise ValueError('Observation cache file changed: '+name)
        source_hash=checkpoint['source_sha256']
        count=checkpoint['source_frames']
        info=checkpoint['info']
    else:
        old=json.loads((prior/'summary.json').read_text(encoding='utf-8'))
        needed=('source_sha256','source_frames','width','height','fps','duration_ms','timestamp_basis_counts')
        if any(key not in old for key in needed):
            raise ValueError('Run lacks an observation checkpoint. Recover the saved manual-calibration '
                             'run with: .\\.venv-track\\Scripts\\python.exe -m '
                             'tools.recover_interaction_observations '+prior.name+' <new-cache-name>')
        source_hash=old['source_sha256']
        count=old['source_frames']
        info={key:old[key] for key in needed if key not in ('source_sha256','source_frames')}
        if old.get('florence_klt'):
            info['florence_klt']=old['florence_klt']
    if source_hash!=digest(source):
        raise ValueError('Observation reuse requires identical config and source hash')
    rows=[json.loads(line) for line in (prior/'observations.jsonl').read_text(encoding='utf-8').splitlines()]
    validate_rows(rows,count)
    candidates=json.loads((prior/'candidates.json').read_text(encoding='utf-8'))
    return source,rows,candidates,info
