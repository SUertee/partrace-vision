"""Regenerate saved session visuals/JSON; never calls pose, parcel or VLM models."""
import argparse
import json
from pathlib import Path
import time

from station_vision.shelf_session.cache import digest
from station_vision.shelf_session.pipeline import basic_and_details,result_document,compact_document,validate_evidence
from station_vision.shelf_session.observer import save_json
from station_vision.shelf_session.visualization import model_sheet,export_video,report


def main():
    parser=argparse.ArgumentParser();parser.add_argument('run_name')
    parser.add_argument('--report-only',action='store_true')
    parser.add_argument('--reference',default='')
    args=parser.parse_args()
    if not args.run_name.replace('_','').replace('-','').isalnum():raise ValueError('Unsafe run name')
    root=Path(__file__).resolve().parents[1];output=root/'outputs'/args.run_name
    read=lambda name:json.loads((output/name).read_text(encoding='utf-8'))
    summary=read('summary.json');source=Path(summary['source_path'])
    if digest(source)!=summary['source_sha256']:raise ValueError('Source changed')
    started=time.perf_counter();rows=[json.loads(x) for x in (output/'observations.jsonl').read_text(encoding='utf-8').splitlines()]
    sessions=read('sessions.json');details=read('event_details.json');events=read('events.json')
    plans={d['session']['id']:d['keyframes'] for d in details};results={d['session']['id']:d['review'] for d in details}
    for s in sessions:model_sheet(output,s['id'],plans[s['id']])
    if not args.report_only:
        export_video(source,rows,sessions,results,output,summary['fps'])
        validate_evidence(source,rows,sessions,plans,output,summary)
    ablation=read('ablation.json') if (output/'ablation.json').exists() else None
    evaluation=read('evaluation.json') if (output/'evaluation.json').exists() else None
    if args.reference:
        import shutil
        from station_vision.shelf_session.evaluation import evaluate
        reference=json.loads((root/args.reference).read_text(encoding='utf-8-sig'))
        if reference['source_sha256']!=summary['source_sha256']:raise ValueError('Reference source changed')
        if (output/'evaluation.json').exists() and not (output/'evaluation_before_layer_audit.json').exists():
            shutil.copy2(output/'evaluation.json',output/'evaluation_before_layer_audit.json')
        save_json(output/'reference_adjudicated.json',reference)
        evaluation=evaluate(reference,sessions,results,read('controls.json') if (output/'controls.json').exists() else [])
        save_json(output/'evaluation.json',evaluation)
    elapsed=round(time.perf_counter()-started,3)
    summary['offline_report_refresh_s' if args.report_only else 'offline_visual_reexport_s']=elapsed
    summary['offline_reexport_api_calls']=0
    save_json(output/'summary.json',summary)
    result=result_document(source,summary,events,details,evaluation,ablation)
    save_json(output/'result.json',result);save_json(output/'interaction_summary.json',compact_document(result))
    report(output,summary,sessions,details,ablation,evaluation)
    print(json.dumps({'status':'completed','api_calls':0,'elapsed_s':elapsed,'report_only':args.report_only},ensure_ascii=False))


if __name__=='__main__':main()
