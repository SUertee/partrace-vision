"""Revalidate saved candidate responses and export, with zero model calls."""
import argparse
import json
from pathlib import Path
import shutil
from station_vision.interaction.pipeline import save_json,digest,aggregate,export_video,report
from station_vision.interaction.review import normalize,validate
from station_vision.multiframe_review.client import parse_response


def reprocess(root,source_run,out_run):
    for name in (source_run,out_run):
        if not name.replace('_','').replace('-','').isalnum():raise ValueError('Unsafe run name')
    prior=root/'outputs'/source_run;output=root/'outputs'/out_run
    old=json.loads((prior/'summary.json').read_text(encoding='utf-8'))
    source=Path(old['source_path'])
    if digest(source)!=old['source_sha256']:raise ValueError('Source changed')
    output.mkdir(parents=True,exist_ok=False)
    for name in ('config_snapshot.json','observations.jsonl','people.json','candidates.json','prompt.txt'):
        shutil.copy2(prior/name,output/name)
    for name in ('evidence','calls'):
        shutil.copytree(prior/name,output/name)
    rows=[json.loads(line) for line in (prior/'observations.jsonl').read_text(encoding='utf-8').splitlines()]
    frames={r['frame_id']:r for r in rows}
    candidates=json.loads((prior/'candidates.json').read_text(encoding='utf-8'))
    reviews=[];details=[];reused=0
    for path in sorted((prior/'calls').glob('*.request.json')):
        meta=json.loads(path.read_text(encoding='utf-8'))
        selected=[frames[f] for f in meta['frame_ids']]
        responsepath=path.with_name(path.name.replace('.request.json','.response.json'))
        if not responsepath.exists():
            meta.update(status='failed',error='Missing saved response')
            reviews.append(meta);continue
        response=json.loads(responsepath.read_text(encoding='utf-8'))
        reused+=1
        meta.update(api_completed=True,usage=response.get('usage'),api_model=response.get('model'))
        try:
            data=validate(normalize(parse_response(response),selected,meta['algorithm']),selected,meta['algorithm'])
            meta.update(status='completed',opinion=data)
            for s in data['segments']:
                warnings=[]
                if s['end_ms']<meta['core_start_ms'] or s['start_ms']>meta['core_end_ms']:
                    warnings.append('action_in_context_only')
                if s['person_id']!=meta['trigger_person_id']:
                    warnings.append('actor_disagrees_with_trigger' if s['person_id'] else 'actor_unknown')
                details.append({'candidate_id':meta['candidate_id'],'request_id':meta['request_id'],
                                'target_id':meta['target_id'],'trigger_person_id':meta['trigger_person_id'],
                                'algorithm':meta['algorithm'],'verdict':data['verdict'],
                                'segment':s,'warnings':warnings,'verified':False})
        except Exception as exc:
            meta.update(status='failed',error=str(exc))
        reviews.append(meta)
    events,details=aggregate(details)
    save_json(output/'reviews.json',reviews);save_json(output/'events.json',events)
    save_json(output/'event_details.json',details)
    export_video(source,rows,candidates,events,details,reviews,output,old['fps'])
    summary={**old,'status':'partial' if any(r['status']=='failed' for r in reviews) else 'completed',
             'api_calls':0,'historical_api_calls':old['api_calls'],'responses_reused':reused,
             'reprocessed_from':source_run,'reviews_completed':sum(r['status']=='completed' for r in reviews),
             'reviews_failed':sum(r['status']=='failed' for r in reviews),
             'requests_not_reviewed':old['planned_requests']-sum(r['status']=='completed' for r in reviews),
             'events':len(events)}
    summary.pop('elapsed_s',None)
    save_json(output/'summary.json',summary)
    report(output,summary,events,details,candidates)
    print(json.dumps(summary,ensure_ascii=False))


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('source_run');parser.add_argument('output_run')
    args=parser.parse_args();reprocess(Path(__file__).resolve().parents[1],args.source_run,args.output_run)
