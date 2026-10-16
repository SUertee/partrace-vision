"""A-video enrollment: quality/diversity frames -> append-only anonymous archive."""
import argparse
from collections import Counter
from datetime import datetime
import hashlib
import html
import json
from pathlib import Path
import time

import cv2
import numpy as np
from PIL import Image,ImageDraw,ImageFont

from ..models import PoseTracker
from ..video import VideoSource
from .encoder import OSNetEncoder
from .keyframes import enrollment_candidate,select_keyframes
from .profile_store import ProfileStore,atomic_json,digest,read_archive
from ..runtime_parameters import DEFAULT_CONFIG,load_parameters


def _find_font():
    candidates=['C:/Windows/Fonts/msyh.ttc',str(Path(__file__).resolve().parents[2]/'assets'/'fonts'/'NotoSansSC-Regular.otf'),
        '/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc','/usr/share/fonts/opentype/noto/NotoSansCJKsc-Regular.otf']
    for path in candidates:
        if Path(path).exists():return path
    return candidates[0]
FONT=_find_font()


def bounded_candidates(items,candidate,maximum=48,bin_ms=200):
    same=[i for i,c in enumerate(items) if int(c['time_ms']//bin_ms)==int(candidate['time_ms']//bin_ms)]
    if same:
        i=same[0]
        if candidate['quality']['selection_quality']>items[i]['quality']['selection_quality']:items[i]=candidate
    else:items.append(candidate)
    if len(items)>maximum:
        order=sorted(range(len(items)),key=lambda i:items[i]['time_ms'])
        pairs=list(zip(order[1:-2],order[2:-1]))
        a,b=min(pairs,key=lambda ij:abs(items[ij[0]]['time_ms']-items[ij[1]]['time_ms']))
        remove=min((a,b),key=lambda i:items[i]['quality']['selection_quality'])
        items.pop(remove)


def export_evidence(source,rows,selected,output,fps,write_evidence=True,render_video=True):
    """Second decode preserves selected full frames and verifies original crop pixels."""
    targets={}
    for track,samples in selected.items():
        for s in samples:targets.setdefault(s['source_frame_index'],[]).append(s)
    indexed={r['source_frame_index']:r for r in rows}
    folder=output/'evidence';folder.mkdir(exist_ok=True);cap=cv2.VideoCapture(str(source));writer=None;index=-1;checked=0
    try:
        while True:
            ok,raw=cap.read()
            if not ok:break
            index+=1
            if write_evidence and index in targets:
                framepath=folder/f'f{index:06d}.png'
                ok,data=cv2.imencode('.png',raw)
                if not ok:raise RuntimeError('Cannot encode source evidence')
                framepath.write_bytes(data.tobytes())
                for s in targets[index]:
                    if abs(cap.get(cv2.CAP_PROP_POS_MSEC)-s['time_ms'])>1:raise ValueError('Enrollment source PTS changed')
                    a,b,c,d=s['box'];h,w=raw.shape[:2]
                    expected=raw[max(0,int(np.floor(b))):min(h,int(np.ceil(d))),max(0,int(np.floor(a))):min(w,int(np.ceil(c)))]
                    decoded=cv2.imdecode(np.frombuffer(s['png_bytes'],np.uint8),cv2.IMREAD_COLOR)
                    if not np.array_equal(expected,decoded):raise ValueError('Enrollment crop source pixels differ')
                    s['raw_frame_path']=str(framepath);s['raw_frame_sha256']=digest(framepath);checked+=1
            if not render_video or index not in indexed:continue
            row=indexed[index];scale=min(1.,960/raw.shape[0]);view=cv2.resize(raw,(round(raw.shape[1]*scale),round(raw.shape[0]*scale)))
            canvas=Image.fromarray(cv2.cvtColor(view,cv2.COLOR_BGR2RGB));draw=ImageDraw.Draw(canvas);font=ImageFont.truetype(FONT,18)
            draw.text((8,8),f'A录像 {row["time_ms"]/1000:.3f}s / f{index:06d}（采样预览）',font=font,fill='white',stroke_width=2,stroke_fill='black')
            for p in row['people']:
                box=[x*scale for x in p['box']];pid=p.get('profile_id');color='#62da93' if pid else '#f4bb55'
                draw.rectangle(box,outline=color,width=2)
                draw.text((box[0],max(35,box[1]-23)),p['track_id']+' '+(pid or p.get('archive_status','质量不足')),font=font,fill=color,stroke_width=1,stroke_fill='black')
            frame=cv2.cvtColor(np.asarray(canvas),cv2.COLOR_RGB2BGR)
            if writer is None:
                height,width=frame.shape[:2];writer=cv2.VideoWriter(str(output/'A_profiles_review.mp4'),cv2.VideoWriter_fourcc(*'mp4v'),fps,(width+width%2,height+height%2))
                if not writer.isOpened():raise RuntimeError('Cannot export A profile video')
            writer.write(cv2.copyMakeBorder(frame,0,frame.shape[0]%2,0,frame.shape[1]%2,cv2.BORDER_CONSTANT))
    finally:
        cap.release()
        if writer:writer.release()
    if write_evidence and checked!=sum(len(v) for v in selected.values()):raise ValueError('Missing source keyframes')
    return checked


def report(output,results,summary):
    sections=[];font=ImageFont.truetype(FONT,16)
    archive=output.parents[1]/'data'/'person_enrollment'
    index={p.name:p for p in archive.rglob('*.png')} if archive.is_dir() else {}
    def resolve_crop(path):
        p=Path(str(path).replace('\\','/'))
        return p if p.is_file() else index.get(p.name,p)
    for result in results:
        samples=result.get('keyframes',[])
        if not samples:continue
        sheet=Image.new('RGB',(200*len(samples),340),'#142034');draw=ImageDraw.Draw(sheet)
        for i,s in enumerate(samples):
            pic=Image.open(resolve_crop(s['crop_path'])).convert('RGB');pic.thumbnail((195,280));sheet.paste(pic,(i*200+(200-pic.width)//2,48))
            draw.text((i*200+4,4),f'{s["time_ms"]/1000:.3f}s\nQ={s["quality"]["selection_quality"]:.2f}',font=font,fill='white')
        name=result['track_id']+'_keyframes.jpg';sheet.save(output/name,quality=94)
        sections.append(f'<section><h2>{html.escape(result["track_id"])} → {html.escape(result.get("person_id") or "待确认")}</h2><p>{html.escape(result["action"])}</p><img src="{name}"><p>关键帧按源时间排列，前/侧/背角度不作未经验证的保证。</p></section>')
    page='<!doctype html><meta charset="utf-8"><title>A视频自动人物建档</title><style>body{font:16px/1.7 system-ui,"Microsoft YaHei";max-width:1300px;margin:auto;padding:24px;background:#f2f5fa}section{background:white;padding:18px;margin:16px 0}img,video{max-width:100%;height:auto}pre{white-space:pre-wrap;overflow-wrap:anywhere}</style><h1>A视频自动人物建档</h1><p>清晰度、尺寸、遮挡、时间间隔和外观差异联合选帧。编号沿用既有注册表；外观关联不是自然人身份确认。</p><video controls src="A_profiles_review.mp4"></video><p><a href="profiles_result.json">本次档案JSON</a> · <a href="gallery_snapshot.json">供B使用的档案快照</a></p>'+''.join(sections)+'<pre>'+html.escape(json.dumps(summary,ensure_ascii=False,indent=2))+'</pre>'
    (output/'comparison.html').write_text(page,encoding='utf-8')


def run(root,source,output,device='cuda',archive_dir='data/person_enrollment',max_seconds=0,runtime_config=DEFAULT_CONFIG):
    started=time.perf_counter();source=Path(source).resolve()
    if not source.is_file():raise FileNotFoundError(source)
    if output.exists():raise FileExistsError('Choose a new A RunName')
    source_info={'path':str(source),'sha256':digest(source)}
    parameters=load_parameters(root,runtime_config)
    a=parameters['a_video'];kf=parameters['a_keyframes']
    recipe={k:parameters[k] for k in ('a_video','a_tracker','a_quality','a_keyframes','archive_matching','osnet')}
    recipe['keyframe_policy']='clear_representatives_v3'
    if max_seconds>0:recipe['end_s']=max_seconds
    source_info['enrollment_recipe_sha256']=hashlib.sha256(json.dumps(recipe,sort_keys=True).encode()).hexdigest()
    video=VideoSource(source,a['sampling_fps'],end_s=max_seconds if max_seconds>0 else None)
    output.mkdir(parents=True);atomic_json(output/'runtime_parameters.json',parameters)
    encoder=OSNetEncoder(root,device,weights=parameters['osnet']['weights'])
    store=ProfileStore(root,encoder.sha256,archive_dir,settings=parameters['archive_matching'])
    tracker=PoseTracker(root,{'device':device,'tracker_type':'botsort',**parameters['a_tracker']},a['sampling_fps'])
    tracks={};rows=[];feature_times={};counts=Counter()
    try:
        for index,pts,basis,raw in video:
            people=[p for p in tracker.process(raw,'A') if p.track_id]
            crops=[];ready=[];row={'source_frame_index':index,'time_ms':round(pts,3),'timestamp_basis':basis,'people':[]}
            for p in people:
                state=tracks.setdefault(p.track_id,{'track_id':p.track_id,'start_ms':pts,'end_ms':pts,'observations':0,'candidates':[]})
                state['end_ms']=pts;state['observations']+=1
                record={'track_id':p.track_id,'box':p.bbox};row['people'].append(record)
                if pts-feature_times.get(p.track_id,-1e9)<1000/a['feature_fps']-1e-5:continue
                feature_times[p.track_id]=pts
                crop,quality=enrollment_candidate(raw,p,people,settings=parameters['a_quality'])
                if crop is None:counts['rejected_'+str(quality['reason'])]+=1;continue
                ok,data=cv2.imencode('.png',crop)
                if not ok:raise RuntimeError('Cannot encode person crop')
                sample={'frame_id':f'f{index:06d}','source_frame_index':index,'time_ms':round(pts,3),
                    'timestamp_basis':basis,'box':p.bbox,'quality':quality,'png_bytes':data.tobytes(),
                    'crop_sha256':hashlib.sha256(data.tobytes()).hexdigest()}
                crops.append(crop);ready.append((state,sample))
            vectors=encoder.encode(crops)
            for (state,sample),vector in zip(ready,vectors):
                sample['feature']=vector.tolist();bounded_candidates(state['candidates'],sample,kf['candidate_limit'],kf['candidate_bin_ms']);counts['quality_candidates']+=1
            rows.append(row)
            if len(rows)%30==0:print(f'A_enroll t={pts/1000:.1f}s tracks={len(tracks)} quality_candidates={counts["quality_candidates"]}',flush=True)
    finally:video.cap.release()
    selected={};results=[]
    for track in sorted(tracks.values(),key=lambda t:t['start_ms']):
        samples,reason=select_keyframes(track['candidates'],settings=kf) if track['end_ms']-track['start_ms']>=a['minimum_track_ms'] else ([], 'track_too_brief')
        if not samples:
            results.append({'track_id':track['track_id'],'person_id':None,'action':reason,'keyframes':[]});continue
        folder=output/'crops'/track['track_id'];folder.mkdir(parents=True)
        for sample in samples:
            path=folder/(sample['frame_id']+'.png');path.write_bytes(sample['png_bytes']);sample['crop_path']=str(path)
        selected[track['track_id']]=samples
    # Export source evidence BEFORE committing new archive entries.
    checked=export_evidence(source,rows,selected,output,a['sampling_fps'],render_video=False)
    if digest(source)!=source_info['sha256']:raise ValueError('A source changed; archive enrollment stopped')
    for track_id,samples in selected.items():
        results.append(store.register(source_info,tracks[track_id],samples,output.name))
    mapping={r['track_id']:r for r in results}
    for row in rows:
        for p in row['people']:
            r=mapping[p['track_id']];p.update(profile_id=r['person_id'],archive_status=r['action'])
    # Re-export only visualization with resolved IDs; no second detection/model call.
    export_evidence(source,rows,selected,output,a['sampling_fps'],write_evidence=False)
    atomic_json(output/'profiles_result.json',results);atomic_json(output/'gallery_snapshot.json',read_archive(store.path,encoder.sha256))
    with (output/'observations.jsonl').open('w',encoding='utf-8') as stream:
        for row in rows:stream.write(json.dumps(row,ensure_ascii=False)+'\n')
    summary={'status':'completed','source':source_info,'archive_path':str(store.path),'weights_sha256':encoder.sha256,
        'source_duration_ms':video.frame_count/video.fps*1000,'local_tracks':len(tracks),'actions':dict(Counter(r['action'] for r in results)),
        'selected_raw_keyframes_checked':checked,'sampling_fps':a['sampling_fps'],'feature_fps_at_most':a['feature_fps'],
        'enrollment_recipe':recipe,'quality_counters':dict(counts),
        'numbering':'max existing ID + 1; legacy IDs reserved; existing photos not replaced',
        'elapsed_s':round(time.perf_counter()-started,3),'api_calls':0,'identity_human_verified':False}
    atomic_json(output/'summary.json',summary);report(output,results,summary)
    print(json.dumps(summary,ensure_ascii=False),flush=True);print('A_OUTPUT '+str(output),flush=True)
    return summary


def main():
    p=argparse.ArgumentParser();p.add_argument('--video',required=True);p.add_argument('--run-name',required=True)
    p.add_argument('--device',choices=['cuda','cpu'],default='cuda');p.add_argument('--archive-dir',default='data/person_enrollment')
    p.add_argument('--runtime-config',default=DEFAULT_CONFIG)
    p.add_argument('--max-seconds',type=float,default=0);args=p.parse_args()
    if not args.run_name.replace('_','').replace('-','').isalnum():raise ValueError('Unsafe run name')
    root=Path(__file__).resolve().parents[2]
    run(root,(root/args.video).resolve(),root/'outputs'/args.run_name,args.device,args.archive_dir,args.max_seconds,args.runtime_config)


if __name__=='__main__':main()
