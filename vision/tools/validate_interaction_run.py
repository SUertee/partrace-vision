"""Validate source evidence, candidate requests, minimal events and output decoding."""
import argparse
import json
from pathlib import Path
import cv2
from station_vision.interaction.pipeline import digest,save_json
from station_vision.interaction.review import validate,normalize
from station_vision.multiframe_review.client import parse_response


def check(root,name):
    run=root/'outputs'/name
    summary=json.loads((run/'summary.json').read_text(encoding='utf-8'))
    source=Path(summary['source_path'])
    assert digest(source)==summary['source_sha256'],'Source hash mismatch'
    rows=[json.loads(s) for s in (run/'observations.jsonl').read_text(encoding='utf-8').splitlines()]
    frames={r['frame_id']:r for r in rows}
    candidates={c['id']:c for c in json.loads((run/'candidates.json').read_text(encoding='utf-8'))}
    manifests=json.loads((run/'evidence/manifest.json').read_text(encoding='utf-8'))
    byindex={m['source_frame_index']:m for m in manifests}
    cap=cv2.VideoCapture(str(source));index=-1;checked=0
    while True:
        ok,img=cap.read()
        if not ok:break
        index+=1
        if index not in byindex:continue
        m=byindex[index]
        p=run/m['raw_path']
        assert digest(p)==m['raw_sha256'],'Evidence hash mismatch'
        assert p.read_bytes()==cv2.imencode('.jpg',img,[cv2.IMWRITE_JPEG_QUALITY,92])[1].tobytes(),'Raw evidence altered'
        assert abs(cap.get(cv2.CAP_PROP_POS_MSEC)-m['source_pts_ms'])<1,'Evidence PTS mismatch'
        checked+=1
    cap.release()
    assert checked==len(manifests)
    reviews=json.loads((run/'reviews.json').read_text(encoding='utf-8'))
    completed=0
    for r in reviews:
        c=candidates[r['candidate_id']]
        assert c['active_start_ms']<=r['core_start_ms']<=r['core_end_ms']<=c['active_end_ms']+.01
        assert r['image_count']<=32
        assert set(r['frame_ids'])<=set(frames)
        if r['status']=='completed':
            response=json.loads((run/'calls'/(r['request_id']+'.response.json')).read_text(encoding='utf-8'))
            selected=[frames[i] for i in r['frame_ids']]
            validate(normalize(parse_response(response),selected,r['algorithm']),selected,r['algorithm'])
            completed+=1
    events=json.loads((run/'events.json').read_text(encoding='utf-8'))
    for e in events:
        assert set(e)=={'id','person_id','start_ms','end_ms','action','shelf_level'}
        assert 0<=e['start_ms']<=e['end_ms']<=summary['duration_ms']
    cap=cv2.VideoCapture(str(run/'joint_review.mp4'))
    fps=cap.get(cv2.CAP_PROP_FPS);decoded=0
    while cap.read()[0]:decoded+=1
    cap.release()
    assert decoded==summary['source_frames'],'Exported frame count mismatch'
    assert abs(fps-summary['fps'])<.01
    result={'source_hash_checked':True,'source_evidence_frames_checked':checked,
            'model_responses_validated':completed,'public_event_fields':6,
            'exported_frames_decoded':decoded,'export_fps':fps,
            'semantic_accuracy_evaluated':False}
    save_json(run/'validation.json',result)
    print(json.dumps(result,ensure_ascii=False))


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('run_name');args=parser.parse_args()
    if not args.run_name.replace('_','').replace('-','').isalnum():raise ValueError('Unsafe run name')
    check(Path(__file__).resolve().parents[1],args.run_name)
