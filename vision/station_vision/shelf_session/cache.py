"""Hashes and source observation integrity for shelf-session reuse."""
import hashlib
import math
from pathlib import Path

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
