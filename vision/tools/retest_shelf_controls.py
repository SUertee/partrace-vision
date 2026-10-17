"""Retest only invalidated controls with saved five frames and label-free inputs."""
import argparse
import json
from pathlib import Path
import shutil
import time

from station_vision.interaction.cache import digest
from station_vision.multiframe_review.client import CompatibleClient
from station_vision.shelf_session.pipeline import review_once
from station_vision.shelf_session.observer import save_json


def main():
    parser=argparse.ArgumentParser();parser.add_argument('run_name');args=parser.parse_args()
    if not args.run_name.replace('_','').replace('-','').isalnum():raise ValueError('Unsafe run name')
    root=Path(__file__).resolve().parents[1];output=root/'outputs'/args.run_name
    summary=json.loads((output/'summary.json').read_text(encoding='utf-8'))
    if digest(Path(summary['source_path']))!=summary['source_sha256']:raise ValueError('Source changed')
    archive=output/'controls_invalidated'
    if archive.exists():raise FileExistsError('Control retest already exists; preserve results')
    controls=json.loads((output/'controls.json').read_text(encoding='utf-8'))
    rows=[json.loads(x) for x in (output/'observations.jsonl').read_text(encoding='utf-8').splitlines()]
    shutil.copytree(output/'controls',archive)
    shutil.copy2(output/'controls.json',output/'controls_before_input_sanitization.json')
    save_json(archive/'INVALIDATED.json',{'reason':'Expected label and human reference reason were present in old control session payload',
                                        'accuracy_evaluation_valid':False,'primary_and_parcel_ablation_affected':False})
    client=CompatibleClient('https://maas.qianwenaiapi.com/compatible-mode/v1','qwen3.8-max',max_attempts=1)
    started=time.perf_counter()
    for c in controls:
        c['review']=review_once(client,c['session'],c['keyframes'],rows,output/'controls')
        c['evaluation_input_sanitized']=True
    save_json(output/'controls.json',controls)
    elapsed=round(time.perf_counter()-started,3)
    summary['invalidated_control_api_calls']=len(controls)
    summary['clean_control_retest_api_calls']=len(controls)
    summary['clean_control_retest_elapsed_s']=elapsed
    summary['api_calls_total_including_invalidated_controls']=summary['api_calls_total']+len(controls)
    summary['api_calls_total']=summary['api_calls_total_including_invalidated_controls']
    summary['valid_test_api_calls']=summary['api_calls_primary']+summary['api_calls_ablation']+len(controls)
    summary['experiment_plus_control_retest_s']=summary['experiment_elapsed_s']+elapsed
    save_json(output/'summary.json',summary)
    print(json.dumps({'status':'completed','new_api_calls':len(controls),'elapsed_s':elapsed},ensure_ascii=False))


if __name__=='__main__':main()
