"""Optional controlled ablation: enrich identical pose/region observations only."""
import copy
import json
import time

import cv2

from ..florence.probe import FlorenceProbe
from ..florence.tracking import FlorenceKLTBank
from ..florence.observer import wrist_points,source_boxes,actor_crop
from ..hand_object.motion import edge_distance
from .observer import save_json,percentiles
import numpy as np


def enrich(root,config,source,rows,output,device):
    started=time.perf_counter()
    cfg=config.get('florence_klt',{})
    localizer=FlorenceProbe(root,device,4,cfg.get('variant','base-ft'))
    bank=FlorenceKLTBank(max_tracks=cfg.get('max_tracks',8),max_age_ms=cfg.get('max_localization_age_ms',4000))
    augmented=copy.deepcopy(rows)
    folder=output/'localization';(folder/'inputs').mkdir(parents=True)
    calls=[];klt_ms=[];next_scene=0.;due={};previous=None;last_time=None;last_retry=-1e9
    cap=cv2.VideoCapture(str(source))

    def locate(raw,row,crop,task,text,hint=None):
        if len(calls)>=cfg.get('max_localization_calls',80):return []
        cid=f'l{len(calls)+1:04d}';a,b,c,d=crop;image=raw[b:d,a:c]
        path=folder/'inputs'/(cid+'.png')
        if not cv2.imwrite(str(path),image):raise RuntimeError('Localization evidence write failed')
        tick=time.perf_counter()
        record={'id':cid,'frame_id':row['frame_id'],'time_ms':row['time_ms'],'crop_xyxy_source':crop,
                'input_path':str(path.relative_to(output)).replace('\\','/'),'person_hint':hint}
        try:
            result=localizer.run(image,task,text_input=text)
            detections=source_boxes(result,crop,raw.shape[1],raw.shape[0],wrist_points(row['people']),hint)
            record.update(status=result['status'],raw_text=result['raw_text'],parsed_boxes=result['boxes'],source_boxes=detections)
            if result['status']!='completed':detections=[]
        except Exception as exc:
            record.update(status='failed',error=str(exc));detections=[]
        record['elapsed_ms']=round((time.perf_counter()-tick)*1000,3)
        calls.append(record)
        with (folder/'calls.jsonl').open('a',encoding='utf-8') as f:f.write(json.dumps(record,ensure_ascii=False)+'\n')
        return detections

    try:
        for row in augmented:
            ok,raw=cap.read()
            if not ok:raise ValueError('Source shorter than pose observations')
            pts=row['time_ms'];gray=cv2.cvtColor(raw,cv2.COLOR_BGR2GRAY);tick=time.perf_counter()
            bank.advance(previous,gray,pts,'source_time_gap' if last_time is not None and pts-last_time>250 else None)
            klt_ms.append((time.perf_counter()-tick)*1000)
            wrists=wrist_points(row['people']);detections=[]
            if pts>=next_scene-1e-5:
                detections+=locate(raw,row,[0,0,raw.shape[1],raw.shape[0]],'boxes',cfg.get('scene_text','cardboard boxes and parcels'))
                next_scene=pts+cfg.get('scene_refresh_ms',3000)
            retry=any(not t['flow'].ok for t in bank.tracks.values()) and pts-last_retry>=cfg.get('loss_retry_ms',750)
            if retry:last_retry=pts
            for p in row['people']:
                pw=[point for pid,hand,point in wrists if pid==p['id']]
                near=any(cv2.pointPolygonTest(np.asarray(z['polygon'],np.float32),tuple(map(float,point)),True)>=-30
                         for z in row['shelf_polygons'] for point in pw)
                near=near or any(edge_distance(point,t['flow'].box)/max(np.linalg.norm(t['flow'].box[2:]-t['flow'].box[:2]),1)<.6
                                 for point in pw for t in bank.tracks.values() if t['flow'].ok)
                if pw and near and (pts>=due.get(p['id'],0) or retry):
                    detections+=locate(raw,row,actor_crop(p,raw.shape[1],raw.shape[0]),'held',
                                       cfg.get('actor_text',"the box held in the person's hands"),p['id'])
                    due[p['id']]=pts+cfg.get('actor_refresh_ms',1500)
            if detections:bank.correct(gray,detections,pts)
            parcels=bank.observations(pts)
            for parcel in parcels:
                box=parcel['box']
                ids={pid for pid,hand,point in wrists if box is not None and
                     edge_distance(point,box)/max(np.linalg.norm(np.asarray(box[2:])-box[:2]),1)<=.4}
                parcel['near_person_ids']=sorted(ids);parcel['ambiguous_actor']=len(ids)>1
                if ids:bank.tracks[parcel['id']]['last_near_ms']=pts
            row['parcels']=parcels
            previous=gray;last_time=pts
            if row['index']%90==0:print(f'parcel_ablation t={pts/1000:.1f}s calls={len(calls)} tracks={len(parcels)}',flush=True)
    finally:cap.release()
    info={'added_visual_wall_s':round(time.perf_counter()-started,3),'model_load_s':localizer.load_s,
        'localization_calls':len(calls),'localization_failures':sum(c['status']!='completed' for c in calls),
        'florence':percentiles([c['elapsed_ms'] for c in calls]),'klt':percentiles(klt_ms),
        'klt_losses':sum(x['event']=='klt_lost' for x in bank.log),
        'same_pose_and_layer_rows':True,'session_boundaries_unchanged':True,
        'source_frames':len(rows),'object_accuracy_evaluated':False}
    save_json(output/'parcel_tracking_log.json',bank.log)
    save_json(output/'parcel_metrics.json',info)
    with (output/'observations_with_parcels.jsonl').open('w',encoding='utf-8') as stream:
        for row in augmented:stream.write(json.dumps(row,ensure_ascii=False)+'\n')
    return augmented,info
