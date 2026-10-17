"""Review saved B composite evidence using current official model settings."""
import argparse
from datetime import datetime
import json
from pathlib import Path
import shutil
import time

from station_vision.multiframe_review.client import CompatibleClient
from station_vision.runtime_parameters import load_parameters
from station_vision.reid.profile_store import atomic_json, digest
from station_vision.shelf_session.pipeline import review_once, basic_and_details, result_document, compact_document, validate_evidence
from station_vision.shelf_session.visualization import export_video, report,export_reviewed_keyframes


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('prior_run')
    parser.add_argument('--run-name',default='official_qwen_B_'+datetime.now().strftime('%Y%m%d_%H%M%S_%f'))
    args=parser.parse_args()
    if not all(x.replace('_','').replace('-','').isalnum() for x in (args.prior_run,args.run_name)):
        raise ValueError('Unsafe run name')
    root=Path(__file__).resolve().parents[1];prior=root/'outputs'/args.prior_run;output=root/'outputs'/args.run_name
    started=time.perf_counter();parameters=load_parameters(root)
    old=json.loads((prior/'summary.json').read_text(encoding='utf-8'))
    source=Path(old['source_path'])
    if digest(source)!=old['source_sha256']:raise ValueError('B source hash changed')
    rows=[json.loads(line) for line in (prior/'observations.jsonl').read_text(encoding='utf-8').splitlines()]
    sessions=json.loads((prior/'sessions.json').read_text(encoding='utf-8'))
    client=CompatibleClient(parameters['vlm']['base_url'],parameters['vlm']['model'],timeout=parameters['vlm']['timeout_s'],max_attempts=1)
    output.mkdir(parents=True,exist_ok=False)
    shutil.copytree(prior/'keyframes',output/'keyframes')
    for name in ('observations.jsonl','sessions.json','observer_metrics.json','config_snapshot.json','person_archive_snapshot.json','identity_links.json','shelf_reference.png'):
        if (prior/name).is_file():shutil.copy2(prior/name,output/name)
    atomic_json(output/'runtime_parameters.json',parameters)
    plans={s['id']:json.loads((output/'keyframes'/s['id']/'manifest.json').read_text(encoding='utf-8'))['frames'] for s in sessions}
    # Validate the ORIGINAL source pixels before any billable request.
    validate_evidence(source,rows,sessions,plans,output,old,video=False)
    results={s['id']:review_once(client,s,plans[s['id']],rows,output,settings=parameters['vlm']) for s in sessions}
    events,details=basic_and_details(sessions,plans,results)
    export_reviewed_keyframes(rows,details,output)
    atomic_json(output/'events.json',events);atomic_json(output/'event_details.json',details)
    export_video(source,rows,sessions,results,output,old['fps'])
    validate_evidence(source,rows,sessions,plans,output,old,video=True)
    summary={**old,'status':'completed' if all(r['status']=='completed' for r in results.values()) else 'partial',
        'observations_reused_from':args.prior_run,'api_calls_total':len(sessions),'model':parameters['vlm']['model'],
        'base_url':parameters['vlm']['base_url'],'review_only_elapsed_s':round(time.perf_counter()-started,3),
        'primary_review_api_s':sum(r.get('elapsed_s',0) for r in results.values()),
        'reviews_completed':sum(r['status']=='completed' for r in results.values()),
        'reviews_failed':sum(r['status']=='failed' for r in results.values()),'semantic_accuracy_measured':False}
    atomic_json(output/'summary.json',summary)
    result=result_document(source,summary,events,details,None,None)
    atomic_json(output/'result.json',result);atomic_json(output/'interaction_summary.json',compact_document(result))
    report(output,summary,sessions,details,None,None)
    print(json.dumps({k:summary[k] for k in ('status','model','reviews_completed','reviews_failed','primary_review_api_s','review_only_elapsed_s')},ensure_ascii=False),flush=True)
    print('B_REVIEW_OUTPUT '+str(output),flush=True)


if __name__=='__main__':main()
