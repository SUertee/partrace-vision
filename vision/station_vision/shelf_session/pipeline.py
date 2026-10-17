"""Offline shelf sessions, preserved five-frame evidence and controlled parcel ablation."""
import argparse
from dataclasses import asdict
from datetime import datetime
import json
from pathlib import Path
import time

import cv2
import numpy as np
from PIL import Image,ImageDraw,ImageFont

from ..interaction.cache import digest
from ..multiframe_review.client import CompatibleClient,parse_response
from .detector import SessionSettings,detect_sessions,five_frames
from .observer import observe,save_json
from .review import PROMPT,messages,validate,model_session
from .visualization import draw_frame,save_image,export_video,export_registration_debug,report,model_sheet,reach_diagnostic,FONT,export_reviewed_keyframes
from .evaluation import evaluate
from ..reid.session_identity import resolve_session_identities,event_person_id
from ..runtime_parameters import DEFAULT_CONFIG,defaults,load_parameters,validate_parameters


def extract(source,rows,sessions,output,parcel_evidence=False):
    plans={s['id']:five_frames(rows,s) for s in sessions}
    byindex={}
    for s in sessions:
        folder=output/'keyframes'/s['id'];folder.mkdir(parents=True,exist_ok=False)
        for i,f in enumerate(plans[s['id']]):byindex.setdefault(f['source_frame_index'],[]).append((s,i,f))
    cap=cv2.VideoCapture(str(source));index=-1
    try:
        while True:
            ok,raw=cap.read()
            if not ok:break
            index+=1
            if index not in byindex:continue
            if abs(cap.get(cv2.CAP_PROP_POS_MSEC)-rows[index]['time_ms'])>1:
                raise ValueError('Keyframe source PTS differs from observation')
            for s,i,f in byindex[index]:
                stem=f'{i+1:02d}_{f["role"]}_{f["frame_id"]}'
                folder=output/'keyframes'/s['id'];rawpath=folder/(stem+'.png');modelpath=folder/(stem+'.jpg')
                save_image(rawpath,raw)
                save_image(modelpath,draw_frame(raw,rows[index],s['person_id'],s.get('region_id'),parcel_evidence))
                f.update(raw_path=str(rawpath.relative_to(output)).replace('\\','/'),
                         model_image_path=str(modelpath.relative_to(output)).replace('\\','/'),
                         raw_sha256=digest(rawpath),model_image_sha256=digest(modelpath))
    finally:cap.release()
    for s in sessions:
        frames=plans[s['id']]
        if any('raw_path' not in f for f in frames):raise ValueError('Missing selected keyframe')
        unique=len({f['frame_id'] for f in frames})
        quality={'unique_source_frames':unique,'exactly_five_distinct_frames':unique==5,
                 'context_truncated':any(f['truncated'] for f in frames)}
        folder=output/'keyframes'/s['id']
        save_json(folder/'manifest.json',{'session':s,'sampling_quality':quality,'frames':frames})
        sheet=Image.new('RGB',(5*240,460),'#151c28');draw=ImageDraw.Draw(sheet);font=ImageFont.truetype(FONT,17)
        for i,f in enumerate(frames):
            img=Image.open(output/f['raw_path']).convert('RGB');img.thumbnail((240,420))
            sheet.paste(img,(i*240+(240-img.width)//2,40))
            draw.text((i*240+4,3),f'{f["role"]}\n{f["actual_ms"]/1000:.3f}s',font=font,fill='white')
        sheet.save(folder/'five_frames.jpg',quality=95)
        model_sheet(output,s['id'],frames)
        reach_diagnostic(rows,s,frames,output)
    return plans


def review_once(client,session,frames,rows,output,parcel_evidence=False,settings=None):
    vlm=settings or defaults()['vlm']
    folder=output/'calls';folder.mkdir(exist_ok=True)
    metadata={'session_id':session['id'],'status':'not_requested','image_count':5,
        'frame_ids':[f['frame_id'] for f in frames],'model_request_limit':1,'parcel_evidence':parcel_evidence,
        'unique_frame_count':len({f['frame_id'] for f in frames}),'score_calibrated':False}
    save_json(folder/(session['id']+'.request.json'),{'model_session':model_session(session),'frames':frames,'metadata':metadata,
                                                   'reference_labels_sent':False})
    if not client:return metadata
    tick=time.perf_counter()
    print(f'five_frame_review {session["id"]} images=5 parcel_evidence={parcel_evidence}',flush=True)
    try:
        content=messages(session,frames,rows,output,parcel_evidence)
        if sum(x.get('type')=='image_url' for x in content[1]['content'])!=5:raise ValueError('Expected exactly five images')
        response,elapsed=client.complete(content,max_tokens=vlm['max_tokens'])
        save_json(folder/(session['id']+'.response.json'),response)
        opinion=validate(parse_response(response),session,frames,rows)
        metadata.update(status='completed',elapsed_s=elapsed,opinion=opinion,api_model=response.get('model'),usage=response.get('usage'))
    except Exception as exc:
        metadata.update(status='failed',error=str(exc))
    metadata['review_wall_s']=round(time.perf_counter()-tick,3)
    save_json(folder/(session['id']+'.review.json'),metadata)
    print(f'five_frame_review {session["id"]} {metadata["status"]} wall={metadata["review_wall_s"]:.2f}s',flush=True)
    return metadata


def basic_and_details(sessions,plans,results):
    events=[];details=[]
    for s in sessions:
        r=results[s['id']];op=r.get('opinion') or {}
        if op.get('interaction_detected') is not False:
            events.append({'id':s['id'],'person_id':event_person_id(s,op),
                'start_ms':round(s['start_ms']),'end_ms':round(s['end_ms']),
                'action':'shelf_interaction','shelf_level':op.get('shelf_level',s['shelf_level'])})
        details.append({'session':s,'pickup_detected':op.get('pickup_detected'),
            'pickup_score':op.get('pickup_score'),'score_calibrated':False,'review':r,'keyframes':plans[s['id']]})
    return events,details


def result_document(source,summary,events,details,evaluation,ablation):
    interactions=[]
    for d in details:
        sid=d['session']['id'];op=d['review'].get('opinion') or {}
        item={'event':next((e for e in events if e['id']==sid),None),
            'algorithm_shelf_level':d['session']['shelf_level'],
            'model_shelf_level':op.get('model_shelf_level',op.get('shelf_level')),
            'shelf_level_status':'candidate_only' if not op else ('conflict' if 'shelf_level_conflict' in op.get('warnings',[]) else 'model_opinion'),
            'interaction_detected':op.get('interaction_detected'),'pickup_detected':d['pickup_detected'],
            'pickup_score':d['pickup_score'],'score_calibrated':False,
            'review_elapsed_s':d['review'].get('elapsed_s'),'review_status':d['review']['status'],
            'reason':op.get('reason'),'shelf_reason':op.get('shelf_reason'),'warnings':op.get('warnings',[]),
            'boundary':d['session'],'keyframes':d['keyframes']}
        if ablation:
            pair=next((p for p in ablation['pairs'] if p['session_id']==sid),None)
            if pair:
                b=pair['with_parcels'];bop=b.get('opinion') or {}
                item['with_florence_klt']={'review_status':b['status'],
                    'interaction_detected':bop.get('interaction_detected'),'pickup_detected':bop.get('pickup_detected'),
                    'pickup_score':bop.get('pickup_score'),'score_calibrated':False,
                    'model_shelf_level':bop.get('model_shelf_level',bop.get('shelf_level')),
                    'final_shelf_level':bop.get('shelf_level'),'warnings':bop.get('warnings',[]),
                    'review_elapsed_s':b.get('elapsed_s'),'reason':bop.get('reason'),
                    'same_raw_frames':pair['raw_pixels_same'],
                    'keyframe_manifest':f'with_parcels/keyframes/{sid}/manifest.json'}
        interactions.append(item)
    return {'source':{'path':str(source),'sha256':summary['source_sha256'],'duration_ms':summary['duration_ms']},
            'definition':summary['session_definition'],'sampling':summary['sampling'],'timing':summary,
            'interactions':interactions,'evaluation':evaluation,'ablation_file':'ablation.json' if ablation else None}


def compact_document(result):
    s=result['timing'];items=[]
    for item in result['interactions']:
        boundary=item['boundary'];event=item['event']
        value={'id':boundary['id'],'person_id':event['person_id'] if event else event_person_id(boundary,{}),
            'start_ms':round(boundary['start_ms']),'end_ms':round(boundary['end_ms']),
            'action':'shelf_interaction','shelf_level':event['shelf_level'] if event else None,
            'algorithm_shelf_level':item['algorithm_shelf_level'],'vlm_shelf_level':item['model_shelf_level'],
            'shelf_level_status':item['shelf_level_status'],'interaction_detected':item['interaction_detected'],
            'pickup_detected':item['pickup_detected'],'pickup_score':item['pickup_score'],
            'review_elapsed_s':item['review_elapsed_s'],
            'keyframes':[{'role':f['role'],'frame_id':f['frame_id'],'time_ms':f['actual_ms'],'raw_path':f['raw_path']} for f in item['keyframes']]}
        if 'identity' in boundary:
            value['local_person_id']=boundary['person_id'];value['identity_status']=boundary['identity']['status']
        if item.get('with_florence_klt'):
            b=item['with_florence_klt'];value['with_florence_klt']={k:b[k] for k in
                ('pickup_detected','pickup_score','model_shelf_level','final_shelf_level','review_elapsed_s')}
        items.append(value)
    e=result['evaluation'] or {}
    return {'source_video':Path(result['source']['path']).name,'source_sha256':result['source']['sha256'],
        'score_calibrated':False,'interactions':items,
        'timing_s':{'visual_without_parcels':s['observation_wall_s'],'primary_pipeline':s['primary_pipeline_elapsed_s'],
                    'experiment_including_retests':s.get('experiment_plus_control_retest_s',s['experiment_elapsed_s']),
                    'offline_video_reexport':s.get('offline_visual_reexport_s',0)},
        'evaluation':{'correct_decidable_cases':e.get('correct_decidable_cases'),
            'unknown_cases':e.get('unknown_or_failed_cases'),'case_count':len(e.get('review_cases',[])),
            'independent_team_ground_truth':e.get('independent_team_ground_truth',False),
            'reference_note':e.get('reference_provenance')}}


def validate_evidence(source,rows,sessions,plans,output,info,video=True):
    wanted={f['source_frame_index'] for frames in plans.values() for f in frames}
    cap=cv2.VideoCapture(str(source));raw_checked=0;index=-1
    try:
        while True:
            ok,raw=cap.read()
            if not ok:break
            index+=1
            if index not in wanted:continue
            for frames in plans.values():
                for f in frames:
                    if f['source_frame_index']!=index:continue
                    data=np.frombuffer((output/f['raw_path']).read_bytes(),np.uint8)
                    saved=cv2.imdecode(data,cv2.IMREAD_COLOR)
                    if not np.array_equal(raw,saved):raise ValueError('Raw keyframe pixels changed')
                    if digest(output/f['raw_path'])!=f['raw_sha256']:raise ValueError('Raw keyframe hash changed')
                    if abs(rows[index]['time_ms']-f['actual_ms'])>.01:raise ValueError('Wrong keyframe time')
                    raw_checked+=1
    finally:cap.release()
    decoded=None
    if video:
        cap=cv2.VideoCapture(str(output/'interaction_review.mp4'));decoded=0
        while cap.read()[0]:decoded+=1
        cap.release()
        if decoded!=len(rows):raise ValueError('Incomplete session video')
    value={'raw_keyframes_pixel_and_hash_checked':raw_checked,'exported_frames_decoded':decoded,
           'five_per_session':all(len(v)==5 for v in plans.values()),'semantic_accuracy_checked_here':False}
    save_json(output/'validation.json',value)
    return value


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--config',default='configs/interaction/manual_shelf_20261014_173804_01.json')
    parser.add_argument('--video',default='',help='Video path; required for portable image-only calibration JSON')
    parser.add_argument('--gallery',default='',help='Read-only A person archive.json for B identity association')
    parser.add_argument('--runtime-config',default='',help='Unified JSON tuning; omitted reuse preserves saved parameters')
    parser.add_argument('--device',choices=['cpu','cuda'],default='cuda')
    parser.add_argument('--run-name',default='shelf_session_'+datetime.now().strftime('%Y%m%d_%H%M%S'))
    parser.add_argument('--review',action='store_true')
    parser.add_argument('--parcel-ablation',action='store_true')
    parser.add_argument('--reference',default='')
    parser.add_argument('--observations-run',default='',help='Reuse exact-config/source checked completed pose observations')
    parser.add_argument('--model',default=None)
    parser.add_argument('--base-url',default=None)
    args=parser.parse_args();started=time.perf_counter()
    if not args.run_name.replace('_','').replace('-','').isalnum():raise ValueError('Unsafe run name')
    root=Path(__file__).resolve().parents[2];output=root/'outputs'/args.run_name
    if output.exists():raise FileExistsError('Choose a new RunName; existing results are preserved')
    config=json.loads((root/args.config).read_text(encoding='utf-8-sig'))
    if args.runtime_config:
        config['runtime_parameters']=load_parameters(root,args.runtime_config)
    elif config.get('runtime_parameters'):
        config['runtime_parameters']=validate_parameters(config['runtime_parameters'])
    elif not args.observations_run:
        config['runtime_parameters']=load_parameters(root,DEFAULT_CONFIG)
    parameters=config.get('runtime_parameters') or defaults()
    if config.get('runtime_parameters'):
        config['shelf_session']={**config.get('shelf_session',{}),**parameters['interaction']}
    if args.video:config['video']=str((root/args.video).resolve())
    if args.gallery:
        from ..reid.profile_store import read_archive
        archive_path=(root/args.gallery).resolve();archive=read_archive(archive_path)
        config['person_archive_path']=str(archive_path);config['person_archive_sha256']=digest(archive_path)
    if not config.get('video'):raise ValueError('图片标定JSON需同时提供-Video/--video视频路径。')
    source=(root/config['video']).resolve();source_hash=digest(source)
    reference=json.loads((root/args.reference).read_text(encoding='utf-8-sig')) if args.reference else None
    if reference and reference['source_sha256']!=source_hash:raise ValueError('Reference source mismatch')
    client=CompatibleClient(args.base_url or parameters['vlm']['base_url'],args.model or parameters['vlm']['model'],
        timeout=parameters['vlm']['timeout_s'],max_attempts=1) if args.review else None
    output.mkdir(parents=True,exist_ok=False)
    settings=SessionSettings(**config.get('shelf_session',{}))
    save_json(output/'config_snapshot.json',config)
    save_json(output/'runtime_parameters.json',parameters)
    (output/'prompt.txt').write_text(PROMPT,encoding='utf-8')
    summary={'status':'running','source_path':str(source),'source_sha256':source_hash,
        'session_definition':'same person continuous shelf-operation/inspection visit; origin layer reviewed separately',
        'settings':asdict(settings),'sampling':[f'start-{parameters["evidence"]["before_ms"]}ms','first reach onset','first reach peak','withdrawal',f'end+{parameters["evidence"]["after_ms"]}ms'],
        'sampling_fallback':'25%/50%/75% only when no reliable approach-retreat; explicitly labelled',
        'layer_assignment':'first reach estimated hand region vote; wrist trigger never sets layer; VLM verifies visible origin',
        'api_calls_primary':0,'api_calls_controls':0,'api_calls_ablation':0,
        'score_calibrated':False,'live_stream_implemented':False,'parcel_model_primary':False}
    save_json(output/'summary.json',summary)
    try:
        if args.observations_run:
            import shutil
            from ..interaction.cache import validate_rows
            if not args.observations_run.replace('_','').replace('-','').isalnum():raise ValueError('Unsafe observation run')
            prior=root/'outputs'/args.observations_run
            old=json.loads((prior/'summary.json').read_text(encoding='utf-8'))
            prior_config=json.loads((prior/'config_snapshot.json').read_text(encoding='utf-8-sig'))
            if old['source_sha256']!=source_hash or prior_config!=config:raise ValueError('Reuse needs identical source, config and archive hash')
            info=json.loads((prior/'observer_metrics.json').read_text(encoding='utf-8'))
            rows=[json.loads(x) for x in (prior/'observations.jsonl').read_text(encoding='utf-8').splitlines()]
            validate_rows(rows,info['source_frames'])
            for name in ('observations.jsonl','people.json','observer_metrics.json'):shutil.copy2(prior/name,output/name)
            for name in ('shelf_reference.png','hardware.json','person_archive_snapshot.json','identity_links.json'):
                if (prior/name).exists():shutil.copy2(prior/name,output/name)
            summary['observations_reused_from']=args.observations_run
            print('REUSE SESSION OBSERVATIONS '+args.observations_run,flush=True)
        else:
            source,rows,info=observe(root,config,output,args.device)
        summary.update(info)
        save_json(output/'summary.json',summary)
        tick=time.perf_counter();sessions=detect_sessions(rows,settings,parameters)
        if config.get('person_archive_path'):resolve_session_identities(rows,sessions,parameters['b_session'])
        summary['session_detection_s']=round(time.perf_counter()-tick,3)
        save_json(output/'sessions.json',sessions)
        tick=time.perf_counter();plans=extract(source,rows,sessions,output)
        summary['keyframe_extraction_s']=round(time.perf_counter()-tick,3)
        results={s['id']:review_once(client,s,plans[s['id']],rows,output,settings=parameters['vlm']) for s in sessions}
        events,details=basic_and_details(sessions,plans,results)
        export_reviewed_keyframes(rows,details,output)
        save_json(output/'events.json',events);save_json(output/'event_details.json',details)
        tick=time.perf_counter();export_video(source,rows,sessions,results,output,info['fps'])
        summary['video_export_s']=round(time.perf_counter()-tick,3)
        primary_validation=validate_evidence(source,rows,sessions,plans,output,info)
        summary.update(primary_pipeline_elapsed_s=round(time.perf_counter()-started,3),
            api_calls_primary=len(sessions) if client else 0,session_count=len(sessions),event_count=len(events),
            reviews_completed=sum(r['status']=='completed' for r in results.values()),
            reviews_failed=sum(r['status']=='failed' for r in results.values()),
            primary_review_api_s=sum(r.get('elapsed_s',0) for r in results.values()),
            primary_review_wall_s=sum(r.get('review_wall_s',0) for r in results.values()))
        controls=[];ablation=None;evaluation=None
        if reference:
            tick=time.perf_counter();control_output=output/'controls';control_output.mkdir()
            negative=[{**s,'region_id':None,'shelf_level':None} for s in reference.get('negative_controls',[])]
            control_plans=extract(source,rows,negative,control_output)
            for s in negative:
                r=review_once(client,s,control_plans[s['id']],rows,control_output,settings=parameters['vlm'])
                controls.append({'session':s,'review':r,'keyframes':control_plans[s['id']],
                                 'evaluation_input_sanitized':True})
            validate_evidence(source,rows,negative,control_plans,control_output,info,video=False)
            summary['api_calls_controls']=len(negative) if client else 0
            summary['control_testing_s']=round(time.perf_counter()-tick,3)
            evaluation=evaluate(reference,sessions,results,controls)
            save_json(output/'reference_snapshot.json',reference);save_json(output/'controls.json',controls)
            save_json(output/'evaluation.json',evaluation)
        if args.parcel_ablation:
            from .parcel_evidence import enrich
            ab_output=output/'with_parcels';ab_output.mkdir()
            aug_rows,parcel_metrics=enrich(root,config,source,rows,ab_output,args.device)
            tick=time.perf_counter();aug_plans=extract(source,aug_rows,sessions,ab_output,parcel_evidence=True)
            aug_extract_s=time.perf_counter()-tick
            aug_results={s['id']:review_once(client,s,aug_plans[s['id']],aug_rows,ab_output,True,parameters['vlm']) for s in sessions}
            aug_events,aug_details=basic_and_details(sessions,aug_plans,aug_results)
            save_json(ab_output/'events.json',aug_events);save_json(ab_output/'event_details.json',aug_details)
            tick=time.perf_counter();export_video(source,aug_rows,sessions,aug_results,ab_output,info['fps'],True)
            aug_export_s=time.perf_counter()-tick
            validate_evidence(source,aug_rows,sessions,aug_plans,ab_output,info,video=True)
            pairs=[]
            for s in sessions:
                sid=s['id'];a=results[sid].get('opinion') or {};b=aug_results[sid].get('opinion') or {}
                pairs.append({'session_id':sid,'same_frame_ids':
                    [f['frame_id'] for f in plans[sid]]==[f['frame_id'] for f in aug_plans[sid]],
                    'raw_pixels_same':all(f['raw_sha256']==g['raw_sha256'] for f,g in zip(plans[sid],aug_plans[sid])),
                    'without_parcels':results[sid],'with_parcels':aug_results[sid],
                    'pickup_decision_changed':a.get('pickup_detected')!=b.get('pickup_detected'),
                    'layer_decision_changed':a.get('shelf_level')!=b.get('shelf_level')})
            ablation={'controlled_input':'identical sessions, source frames, actors, layer polygons, model and prompt; only parcel evidence differs',
                'parcel_metrics':parcel_metrics,'pairs':pairs,
                'base_visual_wall_s':info['observation_wall_s'],'additional_parcel_visual_wall_s':parcel_metrics['added_visual_wall_s'],
                'with_parcel_visual_component_sum_s':info['observation_wall_s']+parcel_metrics['added_visual_wall_s'],
                'ablation_keyframe_extraction_s':round(aug_extract_s,3),
                'ablation_video_export_s':round(aug_export_s,3),
                'base_primary_review_api_s':summary['primary_review_api_s'],
                'augmented_review_api_s':sum(r.get('elapsed_s',0) for r in aug_results.values()),
                'not_a_second_fresh_pose_run':True,'test_video_count':1,'generalization_established':False}
            if reference:ablation['augmented_reference_evaluation']=evaluate(reference,sessions,aug_results,[])
            save_json(output/'ablation.json',ablation)
            summary['api_calls_ablation']=len(sessions) if client else 0
        summary.update(status='completed' if all(r['status']!='failed' for r in results.values()) else 'partial',
            experiment_elapsed_s=round(time.perf_counter()-started,3),all_events_human_verified=False,
            api_calls_total=sum(summary[k] for k in ('api_calls_primary','api_calls_controls','api_calls_ablation')))
        save_json(output/'summary.json',summary)
        result=result_document(source,summary,events,details,evaluation,ablation)
        save_json(output/'result.json',result);save_json(output/'interaction_summary.json',compact_document(result))
        report(output,summary,sessions,details,ablation,evaluation)
        print(json.dumps(summary,ensure_ascii=False),flush=True);print('OUTPUT '+str(output),flush=True)
    except BaseException as exc:
        summary.update(status='failed',error=str(exc),experiment_elapsed_s=round(time.perf_counter()-started,3))
        # Failed automatic registration must not silently erase human annotations.
        # Reuse already computed pose; no new inference or model requests here.
        if isinstance(exc,Exception) and (output/'observations.jsonl').exists() and (output/'observer_metrics.json').exists():
            try:
                metrics=json.loads((output/'observer_metrics.json').read_text(encoding='utf-8'))
                if not metrics.get('registration_valid_frames'):
                    debug_rows=[json.loads(x) for x in (output/'observations.jsonl').read_text(encoding='utf-8').splitlines()]
                    summary['calibration_debug']=export_registration_debug(source,debug_rows,output,metrics['fps'])
            except Exception as debug_error:
                summary['calibration_debug_error']=type(debug_error).__name__
        save_json(output/'summary.json',summary);raise


if __name__=='__main__':main()
