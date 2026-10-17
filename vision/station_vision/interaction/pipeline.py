"""Offline candidate-first experiment. Full-rate pose IDs, dense local evidence."""
import argparse
from collections import Counter
from dataclasses import asdict
from datetime import datetime
import hashlib
import json
from pathlib import Path
import time

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont

from ..models import PoseTracker
from ..registration import FrameRegistration
from ..video import VideoSource
from ..hand_object.tracking import ParcelFlow, background_motion
from ..hand_object.motion import MotionJudge, edge_distance
from ..multiframe_review.client import CompatibleClient, parse_response
from .candidate import CandidateMachine, Settings
from .cache import load_observations, write_checkpoint
from .review import PROMPT, make_messages, validate, normalize, basic_event


def save_json(path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding='utf-8')


def digest(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as f:
        for b in iter(lambda: f.read(4*1024*1024), b''):
            h.update(b)
    return h.hexdigest()


def color(pid):
    palette = [(255,180,60),(80,230,140),(210,100,255),(80,220,255),(255,130,150)]
    return palette[(int(pid[1:])-1) % len(palette)]


def annotate(raw, row):
    img = raw.copy()
    for p in row['people']:
        a,b,c,d = np.rint(p['box']).astype(int)
        col = color(p['id'])
        cv2.rectangle(img,(a,b),(c,d),col,2)
        # Put labels above the body to keep hand/object interaction visible.
        cv2.putText(img,p['id'],(max(0,a),max(24,b-8)),0,.8,col,2,cv2.LINE_AA)
        for i,j in [(5,7),(7,9),(6,8),(8,10)]:
            x,y = np.asarray(p['keypoints'])[[i,j]]
            if min(x[2],y[2])>=.35:
                cv2.line(img,tuple(np.rint(x[:2]).astype(int)),tuple(np.rint(y[:2]).astype(int)),col,2)
        for k in (9,10):
            w = p['keypoints'][k]
            if w[2]>=.35:
                cv2.circle(img,tuple(np.rint(w[:2]).astype(int)),4,col,-1)
    for b in row['parcels']:
        if b['box'] is not None:
            a,c = np.rint(np.asarray(b['box']).reshape(2,2)).astype(int)
            col = (80,230,140) if b.get('source')=='florence' else (60,200,255)
            if not b.get('valid',True) or b.get('ambiguous_actor'):
                col = (155,155,155)
            cv2.rectangle(img,tuple(a),tuple(c),col,2)
            label = b['id'] + (' '+b['source'].upper() if b.get('source') else '')
            if b.get('ambiguous_actor'):
                label += ' ACTOR UNKNOWN'
            elif b.get('near_person_ids'):
                label += ' NEAR '+','.join(b['near_person_ids'])
            if not b.get('valid',True):
                label += ' PENDING/LOST'
            tx=max(0,min(int(a[0]),img.shape[1]-210));ty=max(18,min(int(a[1])-5,img.shape[0]-3))
            cv2.putText(img,label,(tx,ty),0,.43,col,1,cv2.LINE_AA)
    if row['region_polygon'] is not None:
        cv2.polylines(img,[np.rint(row['region_polygon']).astype(np.int32)],True,(160,160,160),1)
    for zone in row.get('shelf_polygons',[]):
        state=next((s for s in row.get('shelf_state',[]) if s['region_id']==zone['id']),{})
        col=(255,180,80) if state.get('status')=='appearance_changed' else (160,160,160)
        polygon=np.rint(zone['polygon']).astype(np.int32)
        cv2.polylines(img,[polygon],True,col,2)
        name=f'shelf {zone["level"]}' if zone.get('level') is not None else 'shelf region (level unknown)'
        point=np.clip(polygon.min(axis=0),[0,20],[max(0,img.shape[1]-250),img.shape[0]-1])
        cv2.putText(img,name,tuple(point),0,.45,col,1,cv2.LINE_AA)
    for r in row['relations']:
        if r['target_type']=='parcel' and r['distance']<=.4:
            cv2.line(img,tuple(np.rint(r['wrist']).astype(int)),tuple(np.rint(r['nearest']).astype(int)),color(r['person_id']),2)
    return img


def observe(root, config, output, device):
    if config.get('florence_klt',{}).get('enabled'):
        from ..florence.observer import observe_florence
        return observe_florence(root,config,output,device)
    source = (root/config['video']).resolve()
    src = VideoSource(source, sample_fps=120)
    if src.fps>120:
        src.cap.release()
        raise ValueError('This offline exporter supports source FPS <= 120')
    # Run on actual decoded frames so evidence annotations never borrow a pose
    # from a different timestamp. This offline prototype makes no realtime claim.
    tracker = PoseTracker(root, dict(weights='models/yolo26n-pose.pt', device=device,
                        imgsz=int(config.get('imgsz',960)),detection_conf=.1),src.fps)
    machine = CandidateMachine(Settings(**config.get('trigger',{})))
    registration = FrameRegistration(enabled=bool(config.get('region_polygon')))
    rows = []
    labels = {}
    flows = []
    judges = {}
    gray_prev = None
    prior_people = []
    basis_counts = Counter()
    for index, pts, basis, raw in src:
        gray = cv2.cvtColor(raw,cv2.COLOR_BGR2GRAY)
        if gray_prev is None:
            if int(config.get('seed_frame_index',0))!=0:
                raise ValueError('Parcel seeds must refer to source frame zero')
            for seed in config.get('parcel_seeds',[]):
                x1,y1,x2,y2 = seed['bbox_xyxy_source']
                if not (0<=x1<x2<=src.width and 0<=y1<y2<=src.height):
                    raise ValueError('Seed outside source')
                flows.append(ParcelFlow(gray,seed['bbox_xyxy_source'],'affine_corners'))
        else:
            for flow in flows:
                flow.update(gray_prev,gray)
        bg = None if gray_prev is None else background_motion(gray_prev,gray,prior_people,flows)
        gray_prev = gray
        people = []
        for p in tracker.process(raw,config.get('camera_id','cam01')):
            if not p.track_id or len(p.keypoints)<11:
                continue
            labels.setdefault(p.track_id,f'P{len(labels)+1:03d}')
            people.append({'id':labels[p.track_id],'tracklet_id':p.track_id,
                           'box':p.bbox,'keypoints':p.keypoints})
        _, matrix, quality = registration.process(raw)
        polygon = None
        if config.get('region_polygon') and quality['valid']:
            polygon = cv2.perspectiveTransform(np.asarray(config['region_polygon'],np.float32).reshape(-1,1,2),matrix).reshape(-1,2)
        parcels = []
        for seed,flow in zip(config.get('parcel_seeds',[]),flows):
            parcels.append({'id':seed['id'],'box':flow.box.round(2).tolist() if flow.ok else None,
                            'valid':flow.ok,'loss_reason':flow.quality.get('loss_reason')})
        relations = []
        measurements = []
        for person in people:
            for ki,hand in [(9,'left'),(10,'right')]:
                wrist = person['keypoints'][ki]
                if wrist[2]<.35:
                    continue
                for seed,flow in zip(config.get('parcel_seeds',[]),flows):
                    if not flow.ok:
                        continue
                    box = flow.box
                    ratio = edge_distance(wrist[:2],box)/max(np.linalg.norm(box[2:]-box[:2]),1)
                    rel = {'person_id':person['id'],'target_id':seed['id'],'target_type':'parcel',
                           'hand':hand,'distance':float(ratio),'wrist':wrist[:2],
                           'nearest':np.clip(wrist[:2],box[:2],box[2:]).tolist()}
                    relations.append(rel)
                    key = (person['id'],seed['id'],hand)
                    judge = judges.setdefault(key,MotionJudge())
                    result = judge.update(pts/1000,box,wrist[:2],owner=person['id']+':'+hand,
                              tracking_ok=bg is not None,background_displacement=bg if bg is not None else (0,0),
                              support_observed=seed.get('initial_support_observed',False))
                    measurements.append({'person_id':person['id'],'target_id':seed['id'],'hand':hand,**result})
                if polygon is not None:
                    signed = cv2.pointPolygonTest(polygon.astype(np.float32),tuple(map(float,wrist[:2])),True)
                    diag = max(np.linalg.norm(polygon.max(axis=0)-polygon.min(axis=0)),1)
                    # Only within/very close to a registered shelf region. It is
                    # explicitly a region candidate, never a recognized parcel.
                    relations.append({'person_id':person['id'],'target_id':'shelf-region',
                            'target_type':'region','hand':hand,'distance':max(0,-signed)/diag,
                            'wrist':wrist[:2],'nearest':wrist[:2]})
        # Map region proximity to the parcel trigger's scale; outside the actual
        # shelf polygon is not a region trigger merely because its diagonal is big.
        for rel in relations:
            if rel['target_type']=='region':
                rel['distance'] = 0. if rel['distance']==0 else 1.
        machine.update(pts,relations)
        row = {'frame_id':f'f{index:06d}','index':index,'time_ms':round(pts,3),
               'timestamp_basis':basis,'people':people,'parcels':parcels,
               'relations':relations,'measurements':measurements,
               'region_polygon':polygon.round(2).tolist() if polygon is not None else None,
               'registration_valid':bool(quality['valid'])}
        rows.append(row)
        basis_counts[basis]+=1
        prior_people = people
        if index % max(1,round(src.fps)*3)==0:
            print(f'observe t={pts/1000:.1f}s people={len(people)} candidates={len(machine.candidates)}',flush=True)
    if not rows:
        raise ValueError('Empty video')
    candidates = machine.finish(rows[-1]['time_ms'])
    # Duplicate region fallback is unnecessary where an already-triggered exact
    # parcel candidate covers the *whole* same actor interval. Partial overlap
    # remains visible and is flagged during result aggregation.
    exact = [c for c in candidates if c['target_type']=='parcel']
    kept = []
    for c in candidates:
        if c['target_type']=='region' and any(p['person_id']==c['person_id'] and
                p['active_start_ms']<=c['active_start_ms'] and p['active_end_ms']>=c['active_end_ms'] for p in exact):
            c['suppressed_reason']='covered_by_exact_parcel_candidate'
        kept.append(c)
    with (output/'observations.jsonl').open('w',encoding='utf-8') as f:
        for row in rows:
            f.write(json.dumps(row,ensure_ascii=False)+'\n')
    save_json(output/'people.json',labels)
    save_json(output/'candidates.json',kept)
    return source,rows,kept,{'width':src.width,'height':src.height,'fps':src.fps,
                            'duration_ms':rows[-1]['time_ms']+1000/src.fps,
                            'timestamp_basis_counts':dict(basis_counts)}


def sample_indices(rows, start, end, fps):
    times = np.asarray([r['time_ms'] for r in rows])
    if end<start:
        return []
    wanted = np.r_[np.arange(start,end+.001,1000/fps),end]
    result = set()
    for t in wanted:
        i = min(len(rows)-1,int(np.searchsorted(times,t)))
        if i and abs(times[i-1]-t)<abs(times[i]-t):
            i-=1
        if start-.01<=times[i]<=end+.01:
            result.add(i)
    return sorted(result)


def evidence_plan(rows,candidates):
    requests = []
    for c in candidates:
        if c.get('suppressed_reason'):
            continue
        start,end = c['active_start_ms'],c['active_end_ms']
        # At most 2.5 s dense core + low-rate before/after + 3 raw counterparts:
        # less than 32 images per request. Never discard a candidate's tail.
        slices = max(1,int(np.ceil(max(0,end-start)/2500)))
        for n in range(slices):
            lo = start+n*(end-start)/slices
            hi = start+(n+1)*(end-start)/slices
            before = sample_indices(rows,max(c['clip_start_ms'],lo-1500),lo,2)
            active = sample_indices(rows,lo,hi,8)
            after = sample_indices(rows,hi,min(c['clip_end_ms'],hi+1500),2)
            selected = sorted(set(before+active+after))
            if len(selected)+min(3,len(selected))>32:
                raise ValueError('Evidence budget exceeded: shorten core slices, never silently drop frames')
            if selected:
                requests.append({'id':c['id']+f'-r{n+1:02d}','candidate':c,
                    'indices':selected,'before_ids':[rows[i]['frame_id'] for i in before if rows[i]['time_ms']<lo],
                    'during_ids':[rows[i]['frame_id'] for i in active],
                    'after_ids':[rows[i]['frame_id'] for i in after if rows[i]['time_ms']>hi],
                    'core_start_ms':lo,'core_end_ms':hi})
    return requests


def extract(source,rows,requests,output):
    needed = {i for req in requests for i in req['indices']}
    folder = output/'evidence'
    (folder/'raw').mkdir(parents=True)
    (folder/'annotated').mkdir()
    cap = cv2.VideoCapture(str(source))
    index = -1
    manifest = []
    while True:
        ok,frame = cap.read()
        if not ok:
            break
        index+=1
        if index not in needed:
            continue
        row = rows[index]
        if abs(cap.get(cv2.CAP_PROP_POS_MSEC)-row['time_ms'])>1:
            raise ValueError('Evidence decoder time mismatch')
        for name,img in [('raw',frame),('annotated',annotate(frame,row))]:
            path = folder/name/(row['frame_id']+'.jpg')
            path.write_bytes(cv2.imencode('.jpg',img,[cv2.IMWRITE_JPEG_QUALITY,92])[1].tobytes())
            row[name+'_path'] = str(path)
        manifest.append({'frame_id':row['frame_id'],'source_frame_index':index,
                    'source_pts_ms':row['time_ms'],'width':frame.shape[1],'height':frame.shape[0],
                    'coordinate_space':'original_source_pixels','raw_path':str(Path(row['raw_path']).relative_to(output)),
                    'raw_sha256':digest(row['raw_path']),
                    'annotated_path':str(Path(row['annotated_path']).relative_to(output))})
    cap.release()
    if len(manifest)!=len(needed):
        raise ValueError('Missing source evidence frames')
    save_json(folder/'manifest.json',manifest)


def algorithm_for(candidate,rows):
    relevant = [m for row in rows if candidate['active_start_ms']<=row['time_ms']<=candidate['active_end_ms']
                for m in row['measurements'] if m['person_id']==candidate['person_id'] and m['target_id']==candidate['target_id']]
    pickup = [m for m in relevant if m['label']=='pickup_candidate']
    co = [m for m in relevant if m['label']=='co_motion_candidate']
    best = pickup or co
    features = dict(best[-1]['features']) if best else {}
    changes = candidate.get('shelf_change_frame_ids',[])
    if candidate.get('locator'):
        features.update(shelf_appearance_change_frames=len(changes),
                        shelf_change_frame_ids=changes[:8],localization_is_not_action=True)
    return {'hypothesis': 'pickup_candidate' if pickup else ('co_motion_candidate' if co else 'near_only'),
            'features': features,
            'limitations':['二维接近不证明接触','区域兜底没有可靠包裹轨迹'] if candidate['target_type']=='region'
                          else (['Florence只提供位置；标签和框均可能错误','KLT可能跟错；定位校正不计作运动',
                                 '货架外观差异不证明移除；放回仍需多帧复核'] if candidate.get('locator')=='florence2'
                                else ['人工初始化包裹，KLT可能丢失或跟错','放回尚无几何判定器'])}


def run_reviews(requests,rows,output,client):
    folder = output/'calls'
    folder.mkdir()
    reviews = []
    details = []
    for req in requests:
        frames = [rows[i] for i in req['indices']]
        alg = algorithm_for(req['candidate'],rows)
        meta = {'request_id':req['id'],'candidate_id':req['candidate']['id'],
                'trigger_person_id':req['candidate']['person_id'],'target_id':req['candidate']['target_id'],
                'target_type':req['candidate']['target_type'],'algorithm':alg,
                'start_ms':frames[0]['time_ms'],'end_ms':frames[-1]['time_ms'],
                'core_start_ms':req['core_start_ms'],'core_end_ms':req['core_end_ms'],
                'frame_ids':[f['frame_id'] for f in frames],'image_count':len(frames)+min(3,len(frames)),
                'before_ids':req['before_ids'],'during_ids':req['during_ids'],'after_ids':req['after_ids'],
                'shelf_numbering':'bottom_up_floor_excluded','verified':False}
        save_json(folder/(req['id']+'.request.json'),meta)
        if client is None:
            meta.update(status='skipped',reason='model_review_not_enabled')
            reviews.append(meta)
            continue
        print(f'review {req["id"]}: {len(frames)} times / {meta["image_count"]} images',flush=True)
        try:
            response,elapsed = client.complete(make_messages(req['candidate'],frames,alg),max_tokens=4500)
            save_json(folder/(req['id']+'.response.json'),response)
            meta.update(api_completed=True,elapsed_s=elapsed,usage=response.get('usage'),
                        api_model=response.get('model'))
            data = validate(normalize(parse_response(response),frames,alg),frames,alg)
            meta.update(status='completed',opinion=data)
            for s in data['segments']:
                warnings = []
                if s['end_ms']<req['core_start_ms'] or s['start_ms']>req['core_end_ms']:
                    warnings.append('action_in_context_only')
                if s['person_id']!=req['candidate']['person_id']:
                    warnings.append('actor_disagrees_with_trigger' if s['person_id'] else 'actor_unknown')
                details.append({'candidate_id':req['candidate']['id'],'request_id':req['id'],
                    'target_id':req['candidate']['target_id'],'trigger_person_id':req['candidate']['person_id'],
                    'algorithm':alg,'verdict':data['verdict'],'segment':s,
                    'warnings':warnings,'verified':False})
            print(f'review {req["id"]}: completed {elapsed:.1f}s',flush=True)
        except Exception as exc:
            meta.update(status='failed',error=str(exc))
            print(f'review {req["id"]}: failed {type(exc).__name__}',flush=True)
        reviews.append(meta)
        save_json(output/'reviews.json',reviews)
        save_json(output/'event_details.json',details)
    save_json(output/'reviews.json',reviews)
    save_json(output/'event_details.json',details)
    return reviews,details


def aggregate(details):
    """Only merge overlap in the same candidate and action; preserve cross-target uncertainty."""
    merged = []
    for d in sorted(details,key=lambda d:d['segment']['start_ms']):
        s = dict(d['segment'])
        prior = next((p for p in merged if p['candidate_id']==d['candidate_id'] and
            p['segment']['person_id']==s['person_id'] and p['segment']['action']==s['action'] and
            p['segment']['shelf_level']==s['shelf_level'] and
            min(p['segment']['end_ms'],s['end_ms'])>max(p['segment']['start_ms'],s['start_ms'])),None)
        if prior:
            p = prior['segment']
            p['start_ms'] = min(p['start_ms'],s['start_ms'])
            p['end_ms'] = max(p['end_ms'],s['end_ms'])
            p['evidence_frame_ids'] = sorted(set(p['evidence_frame_ids']+s['evidence_frame_ids']))
            prior['request_ids'].append(d['request_id'])
            continue
        merged.append({**d,'segment':s,'request_ids':[d['request_id']]})
    for i,d in enumerate(merged):
        d['id'] = f'e{i+1:04d}'
        s = d['segment']
        if 'actor_disagrees_with_trigger' in d['warnings']:
            d['model_person_id'] = s['person_id']
            s['person_id'] = None
        for other in merged[:i]:
            t = other['segment']
            if min(s['end_ms'],t['end_ms'])>max(s['start_ms'],t['start_ms']) and s['person_id']==t['person_id']:
                d['warnings'].append('overlapping_opinion_check_evidence')
                other['warnings'].append('overlapping_opinion_check_evidence')
    # Contradictory levels for overlapping opinions about the same actor/action
    # remain unknown. Preserve model levels separately; do not choose a majority.
    conflicts = set()
    for i,d in enumerate(merged):
        s=d['segment']
        for j,other in enumerate(merged[:i]):
            t=other['segment']
            if (s['person_id'] is not None and s['person_id']==t['person_id'] and
                    s['action']==t['action'] and s['shelf_level'] is not None and t['shelf_level'] is not None and
                    s['shelf_level']!=t['shelf_level'] and
                    min(s['end_ms'],t['end_ms'])>max(s['start_ms'],t['start_ms'])):
                conflicts.update((i,j))
    for i in conflicts:
        d=merged[i]
        d['model_shelf_level']=d['segment']['shelf_level']
        d['segment']['shelf_level']=None
        d['warnings'].append('shelf_level_conflict')
    return [basic_event(d['id'],d['segment']) for d in merged],merged


def wrapped(draw,text,x,y,width,font,fill):
    line = ''
    for ch in text:
        if draw.textlength(line+ch,font=font)>width:
            draw.text((x,y),line,font=font,fill=fill)
            y+=font.size+7
            line=''
        line+=ch
    if line:
        draw.text((x,y),line,font=font,fill=fill)
        y+=font.size+7
    return y


def export_video(source,rows,candidates,events,details,reviews,output,nominal_fps):
    cap = cv2.VideoCapture(str(source))
    fontpath = 'C:/Windows/Fonts/msyh.ttc'
    font = ImageFont.truetype(fontpath,19)
    small = ImageFont.truetype(fontpath,17)
    width,height = rows[0].get('width',0),0
    writer = None
    index = -1
    algorithms = {c['id']: algorithm_for(c, rows) for c in candidates}
    labels = {'take':'拿起','put':'放下','touch':'触碰','carrying':'已持有',
              'no_interaction':'未发生交互','uncertain':'无法判断'}
    try:
        while True:
            ok,frame = cap.read()
            if not ok:
                break
            index+=1
            if index>=len(rows):
                break
            row = rows[index]
            display = annotate(frame,row)
            scale = min(1,960/frame.shape[0])
            display = cv2.resize(display,(round(frame.shape[1]*scale),round(frame.shape[0]*scale)))
            h,w = display.shape[:2]
            canvas = Image.new('RGB',(w+580,h),(22,26,33))
            canvas.paste(Image.fromarray(cv2.cvtColor(display,cv2.COLOR_BGR2RGB)),(0,0))
            draw = ImageDraw.Draw(canvas)
            x = w+15
            y = wrapped(draw,'候选触发与模型复核｜离线分析',x,12,550,font,'white')
            y = wrapped(draw,'模型意见，均待人工确认；货架从下往上数，地面不计层。',x,y+5,550,small,'#ffcc66')
            y = wrapped(draw,f'源时间 {row["time_ms"]/1000:.2f}s  帧 {index}',x,y+8,550,font,'white')
            if row.get('parcel_mode')=='florence_klt':
                y = wrapped(draw,'Florence定位/校正 → KLT短时跟踪 → 手腕/货架候选 → 多帧复核',x,y+5,550,small,'#aaaaaa')
                y = wrapped(draw,'绿色：本帧定位；黄色：KLT；灰色：待稳定/失效/人物不确定。NEAR仅表示靠近。',x,y+3,550,small,'#aaaaaa')
                changed=[s['region_id'] for s in row.get('shelf_state',[]) if s['status']=='appearance_changed']
                y = wrapped(draw,'货架可见变化：'+(', '.join(changed) if changed else '无明确变化/不可观测'),x,y+3,550,small,'#aaaaaa')
            visible = [c for c in candidates if not c.get('suppressed_reason') and c['clip_start_ms']<=row['time_ms']<=c['clip_end_ms']]
            for c in visible[:4]:
                phase = '动作前' if row['time_ms']<c['active_start_ms'] else ('动作中' if row['time_ms']<=c['active_end_ms'] else '动作后')
                mode = '手物接近' if c['target_type']=='parcel' else '货架区域兜底'
                y = wrapped(draw,f'{c["id"]} {c["person_id"]}｜{mode}｜{phase}',x,y+8,550,small,'#8dcaff')
                rev = [r for r in reviews if r['candidate_id']==c['id']]
                status = '复核失败' if any(r['status']=='failed' for r in rev) else ('已复核' if rev and all(r['status']=='completed' for r in rev) else '尚未复核')
                y = wrapped(draw,'算法：'+algorithms[c['id']]['hypothesis']+'；'+status,x,y,550,small,'#dddddd')
            for e in [e for e in events if e['start_ms']<=row['time_ms']<=e['end_ms']][:5]:
                if y>h-170:
                    wrapped(draw,'更多同时段意见及完整依据见 report.md',x,y+8,550,small,'#ffb060')
                    break
                name = e['person_id'] or '人物未确定'
                shelf = f'第 {e["shelf_level"]} 层' if e['shelf_level'] is not None else '层号未确定/不适用'
                y = wrapped(draw,f'{e["id"]} {name}：{labels[e["action"]]}｜{shelf}',x,y+12,550,font,'#8ff0ad')
                y = wrapped(draw,f'{e["start_ms"]/1000:.2f}–{e["end_ms"]/1000:.2f}s',x,y,550,small,'white')
                d = next(d for d in details if d['id']==e['id'])
                reason=d['segment']['shelf_reason']
                y = wrapped(draw,'层号依据：'+(reason[:155]+'…' if len(reason)>155 else reason),x,y,550,small,'#dddddd')
                if d['warnings']:
                    warning = '层号冲突：模型原判不同，当前层号未知' if 'shelf_level_conflict' in d['warnings'] else '归属/重叠意见需检查原始证据'
                    y = wrapped(draw,warning,x,y,550,small,'#ffb060')
            arr = cv2.cvtColor(np.asarray(canvas),cv2.COLOR_RGB2BGR)
            if writer is None:
                # Encoder requires even dimensions; padding changes no source coordinate.
                height,width = arr.shape[:2]
                writer = cv2.VideoWriter(str(output/'joint_review.mp4'),cv2.VideoWriter_fourcc(*'mp4v'),nominal_fps,(width+width%2,height+height%2))
                if not writer.isOpened():
                    raise RuntimeError('Cannot create joint video')
            arr = cv2.copyMakeBorder(arr,0,height%2,0,width%2,cv2.BORDER_CONSTANT)
            writer.write(arr)
    finally:
        cap.release()
        if writer:
            writer.release()


def report(output,summary,events,details,candidates):
    lines = ['# 单视频候选触发与多帧复核', '',
        '本次结果均为待人工确认的模型意见。货架从下往上编号，地面不算层；层号解释见各事件。', '',
        f'候选 {len(candidates)}；实际模型请求 {summary["api_calls"]}；成功 {summary["reviews_completed"]}；失败 {summary["reviews_failed"]}。',
        '未触发的时间段没有发送给模型；无候选不等于无交互。区域兜底与手物接近分别记录。', '',
        '[联合视频](joint_review.mp4) · [基础事件](events.json) · [候选记录](candidates.json) · [详细复核](event_details.json)', '',
        '| ID | 人物 | 时间（秒） | 动作 | 货架层 |', '|---|---|---|---|---|']
    if summary.get('responses_reused'):
        lines.insert(5,f'本目录离线复用了 {summary["responses_reused"]} 个真实模型回答，本次重新导出没有调用模型；历史调用数为 {summary["historical_api_calls"]}。')
    for e in events:
        lines.append(f'| {e["id"]} | {e["person_id"] or "人物未确定"} | {e["start_ms"]/1000:.2f}–{e["end_ms"]/1000:.2f} | {e["action"]} | {e["shelf_level"] if e["shelf_level"] is not None else "未知/不适用"} |')
    for d in details:
        s = d['segment']
        lines += ['',f'## {d["id"]}：{s["action"]}', '', '算法初判：'+d['algorithm']['hypothesis'],
            '模型复核：'+d['verdict'], '人物与动作依据：'+s['reason'], '层号依据：'+s['shelf_reason'],
            '质量提示：'+(', '.join(d['warnings']) or '尚未人工确认'), '',
            '原始证据：'+' · '.join(f'[{fid}](evidence/raw/{fid}.jpg)' for fid in s['evidence_frame_ids'])]
    locator_note = 'Florence只做定位，KLT只做短时位移；周期校正不是运动，货架差异不是取走证明。' if summary.get('florence_klt') else '人工种子框覆盖有限；区域兜底依赖场景配准；手腕二维接近不等于接触。'
    lines += ['', '## 边界', '', locator_note,
              '多人ID引用和程序隔离已实现；识别准确率、人物身份交换率和层号准确率尚无真值评估。',
              '输出为静音离线视频。此版本尚未接入实时视频流。']
    (output/'report.md').write_text('\n'.join(lines),encoding='utf-8')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--config',default='configs/interaction/florence_klt_0fc72122.json')
    parser.add_argument('--florence-variant',choices=['base-ft','large-ft'])
    parser.add_argument('--max-seconds',type=float)
    parser.add_argument('--run-name',default='interaction_'+datetime.now().strftime('%Y%m%d_%H%M%S'))
    parser.add_argument('--device',choices=['cuda','cpu'],default='cuda')
    parser.add_argument('--review',action='store_true')
    parser.add_argument('--max-requests',type=int)
    parser.add_argument('--review-candidates',nargs='+',help='Review only these candidate IDs; result is partial')
    parser.add_argument('--observations-run',help='Reuse source-hash-checked observations; model reviews are not reused')
    parser.add_argument('--model',default='hackathon/v_model/qwen-max')
    parser.add_argument('--base-url',default='https://ai-router-cn-pub.anker-in.com')
    args = parser.parse_args()
    if not args.run_name.replace('_','').replace('-','').isalnum():
        raise ValueError('Unsafe run name')
    if args.max_requests is not None and args.max_requests<1:
        raise ValueError('Max requests must be positive')
    root = Path(__file__).resolve().parents[2]
    config = json.loads((root/args.config).read_text(encoding='utf-8-sig'))
    if args.florence_variant or args.max_seconds is not None:
        if not config.get('florence_klt',{}).get('enabled'):
            raise ValueError('Florence overrides require a Florence configuration')
        if args.florence_variant:
            config['florence_klt']['variant']=args.florence_variant
        if args.max_seconds is not None:
            if args.max_seconds<0:
                raise ValueError('Max seconds must be nonnegative')
            config['florence_klt']['max_seconds']=args.max_seconds
    if config.get('shelf_numbering')!='bottom_up':
        raise ValueError('This version requires bottom_up shelf numbering')
    output = root/'outputs'/args.run_name
    if output.exists():
        raise FileExistsError('RunName already exists: '+args.run_name+
                              '. Choose a new RunName; use -ObservationsRun to reuse completed observations.')
    # Load only; this sends no request. Fail before expensive inference or a new
    # output directory if review was requested but its credential is unavailable.
    client = CompatibleClient(args.base_url,args.model) if args.review else None
    output.mkdir(parents=True,exist_ok=False)
    save_json(output/'config_snapshot.json',config)
    save_json(output/'summary.json',{'status':'running'})
    (output/'prompt.txt').write_text(PROMPT,encoding='utf-8')
    start = time.perf_counter()
    progress = {'status':'running','stage':'observing','api_calls':0}
    try:
        if args.observations_run:
            if not args.observations_run.replace('_','').replace('-','').isalnum():
                raise ValueError('Unsafe observation run name')
            prior = root/'outputs'/args.observations_run
            source,rows,candidates,info = load_observations(root,prior,config)
            (output/'observations.jsonl').write_bytes((prior/'observations.jsonl').read_bytes())
            (output/'people.json').write_bytes((prior/'people.json').read_bytes())
            save_json(output/'candidates.json',candidates)
            print('REUSE OBSERVATIONS '+str(prior),flush=True)
        else:
            source,rows,candidates,info = observe(root,config,output,args.device)
        checkpoint = write_checkpoint(output,source,rows,info)
        progress.update(stage='extracting_evidence',observations_completed=True,
                        source_path=str(source),source_sha256=checkpoint['source_sha256'],
                        source_frames=len(rows),candidate_count=len(candidates),**info)
        if args.observations_run:
            progress['observations_reused_from']=args.observations_run
        save_json(output/'summary.json',progress)
        requests = evidence_plan(rows,candidates)
        extract(source,rows,requests,output)
        if args.review_candidates:
            missing = set(args.review_candidates) - {c['id'] for c in candidates if not c.get('suppressed_reason')}
            if missing:
                raise ValueError('Unknown or suppressed review candidates: '+', '.join(sorted(missing)))
        filtered = [req for req in requests if not args.review_candidates or req['candidate']['id'] in args.review_candidates]
        selected = filtered if args.max_requests is None else filtered[:args.max_requests]
        # If interrupted mid-request, do not report a false zero API-call count.
        progress.update(stage='reviewing',api_calls=None if client else 0)
        save_json(output/'summary.json',progress)
        reviews,details = run_reviews(selected,rows,output,client)
        progress.update(stage='exporting',api_calls=len(selected) if client else 0)
        events,details = aggregate(details)
        save_json(output/'events.json',events)
        save_json(output/'event_details.json',details)
        export_video(source,rows,candidates,events,details,reviews,output,info['fps'])
        observation_incomplete = info.get('florence_klt',{}).get('localization_failures',0) or info.get('florence_klt',{}).get('localization_limit_reached',False)
        summary = dict(status='partial' if any(r['status']=='failed' for r in reviews) or len(selected)<len(requests) or observation_incomplete else 'completed',
                       source_path=str(source),source_sha256=digest(source),**info,
                       source_frames=len(rows),person_tracklets=len({p['id'] for r in rows for p in r['people']}),
                       candidate_count=len(candidates),candidate_types=dict(Counter(c['target_type'] for c in candidates)),
                       planned_requests=len(requests),api_calls=len(selected) if client else 0,
                       reviews_completed=sum(r['status']=='completed' for r in reviews),
                       reviews_failed=sum(r['status']=='failed' for r in reviews),
                       requests_not_reviewed=len(requests)-sum(r['status']=='completed' for r in reviews),
                       events=len(events),all_events_unverified=True,shelf_numbering='bottom_up',
                       ground_truth_evaluated=False,live_stream_implemented=False,
                       elapsed_s=round(time.perf_counter()-start,2))
        if args.observations_run:
            summary['observations_reused_from'] = args.observations_run
        if args.review_candidates:
            summary['review_candidate_filter'] = args.review_candidates
        save_json(output/'summary.json',summary)
        report(output,summary,events,details,candidates)
        if summary.get('florence_klt'):
            from ..florence.visualization import build_overview
            build_overview(output,source,rows,candidates,events,summary)
        print(json.dumps(summary,ensure_ascii=False),flush=True)
        print('OUTPUT '+str(output),flush=True)
    except BaseException as exc:
        progress.update(status='failed',error=str(exc))
        save_json(output/'summary.json',progress)
        raise


if __name__=='__main__':
    main()
