"""Check exact localization crops and correction/motion separation, without inference."""
import argparse
import json
from pathlib import Path

import cv2
import numpy as np

from station_vision.interaction.pipeline import save_json, digest
from tools.validate_interaction_run import check


def validate_localization(root, name):
    check(root, name)
    output = root / 'outputs' / name
    summary = json.loads((output / 'summary.json').read_text(encoding='utf-8'))
    origin_name = summary.get('observations_reused_from', name)
    if not origin_name.replace('_','').replace('-','').isalnum():
        raise ValueError('Unsafe observation origin')
    origin = root / 'outputs' / origin_name
    original = json.loads((origin / 'summary.json').read_text(encoding='utf-8'))
    assert original['source_sha256'] == summary['source_sha256']
    assert digest(output / 'observations.jsonl') == digest(origin / 'observations.jsonl')
    calls = [json.loads(line) for line in (origin / 'localization/calls.jsonl').read_text(encoding='utf-8').splitlines()]
    rows = [json.loads(line) for line in (output / 'observations.jsonl').read_text(encoding='utf-8').splitlines()]
    call_frames = {}
    for call in calls:
        call_frames.setdefault(call['frame_id'], []).append(call)
    cap = cv2.VideoCapture(summary['source_path'])
    crops, corrections = 0, 0
    try:
        for row in rows:
            ok, raw = cap.read()
            assert ok, 'Missing source frame'
            assert len({p['id'] for p in row['parcels']}) == len(row['parcels'])
            for p in row['parcels']:
                assert p['score'] is None, 'Florence has no calibrated object score'
                if p['corrected_now']:
                    corrections += 1
                    assert not p['motion_observed'], 'Correction became motion evidence'
                if p['valid']:
                    assert not p['ambiguous'] and p['box'] is not None
            for call in call_frames.get(row['frame_id'], []):
                a, b, c, d = call['crop_xyxy_source']
                saved = cv2.imdecode(np.frombuffer((origin / call['input_path']).read_bytes(), np.uint8), cv2.IMREAD_COLOR)
                assert np.array_equal(saved, raw[b:d, a:c]), 'Localization crop differs from source'
                assert abs(call['time_ms']-row['time_ms']) < .01
                if call['status'] == 'completed':
                    assert call['raw_text'] and isinstance(call['parsed_boxes'], list)
                crops += 1
    finally:
        cap.release()
    assert crops == len(calls)
    result = {'localization_origin': origin_name, 'exact_source_crops_checked': crops,
              'initialization_or_correction_observations_checked': corrections,
              'correction_used_as_motion': False, 'fabricated_object_confidence': False,
              'object_identity_or_action_accuracy_evaluated': False}
    save_json(output / 'florence_validation.json', result)
    print(json.dumps(result, ensure_ascii=False))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('run_name')
    args = parser.parse_args()
    if not args.run_name.replace('_','').replace('-','').isalnum():
        raise ValueError('Unsafe run name')
    validate_localization(Path(__file__).resolve().parents[1], args.run_name)
