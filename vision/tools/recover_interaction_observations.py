"""Recover a pre-checkpoint manual-calibration run without changing its files."""
import argparse
from collections import Counter
import json
from pathlib import Path
import shutil

from station_vision.interaction.cache import CACHE_FILES,digest,validate_rows,write_checkpoint
from station_vision.shelf_calibration.manual import verify_scene_calibration
from station_vision.video import VideoSource


def recover(root,run_name,cache_name):
    for name in (run_name,cache_name):
        if not name.replace('_','').replace('-','').isalnum():
            raise ValueError('Unsafe run name')
    prior=root/'outputs'/run_name
    output=root/'outputs'/cache_name
    if output.exists():
        raise FileExistsError('Choose a new cache name; existing results are preserved')
    read=lambda name:json.loads((prior/name).read_text(encoding='utf-8-sig'))
    config=read('config_snapshot.json')
    calibration=config.get('shelf_calibration',{})
    if calibration.get('source')!='manual' or not calibration.get('source_sha256'):
        raise ValueError('Legacy recovery requires a saved manual calibration with a video hash')
    source=(root/config['video']).resolve()
    if digest(source)!=calibration['source_sha256']:
        raise ValueError('Source video differs from the calibrated source')
    rows=[json.loads(line) for line in (prior/'observations.jsonl').read_text(encoding='utf-8').splitlines()]
    metrics=read('florence_klt_metrics.json')
    validate_rows(rows,metrics['source_frames'])
    src=VideoSource(source,sample_fps=120)
    verify_scene_calibration(config,source,src.width,src.height)
    if src.fps>120:
        src.cap.release()
        raise ValueError('Legacy recovery requires source FPS <= 120')
    decoded=0
    try:
        for index,time_ms,basis,raw in src:
            if index>=len(rows) or abs(rows[index]['time_ms']-time_ms)>.01:
                raise ValueError('Saved observations do not match full source frame times')
            if rows[index]['timestamp_basis']!=basis:
                raise ValueError('Saved observation timestamp basis differs from source')
            decoded+=1
    finally:
        src.cap.release()
    if decoded!=len(rows):
        raise ValueError('Saved observations do not cover the full source video')
    info={'width':src.width,'height':src.height,'fps':src.fps,
        'duration_ms':rows[-1]['time_ms']+1000/src.fps,
        'timestamp_basis_counts':dict(Counter(row['timestamp_basis'] for row in rows)),
        'florence_klt':metrics}
    output.mkdir(parents=True,exist_ok=False)
    for name in CACHE_FILES+('florence_klt_metrics.json','parcel_tracking_log.json'):
        shutil.copy2(prior/name,output/name)
    if (prior/'localization').exists():
        shutil.copytree(prior/'localization',output/'localization')
    checkpoint=write_checkpoint(output,source,rows,info)
    summary={'status':'observations_ready','source_path':str(source),
        'source_sha256':checkpoint['source_sha256'],'source_frames':len(rows),**info,
        'recovered_from':run_name,'legacy_recovery':True,'source_frames_redecoded':decoded,
        'api_calls':0,'detector_rerun':False,'action_review_completed':False}
    (output/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(summary,ensure_ascii=False),flush=True)
    return output


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('failed_run')
    parser.add_argument('cache_run')
    args=parser.parse_args()
    recover(Path(__file__).resolve().parents[1],args.failed_run,args.cache_run)


if __name__=='__main__':
    main()
