"""Fresh full-source-rate pose and shelf registration; no parcel model by default."""
from collections import Counter
import json
import time
import sys

import cv2
import numpy as np

from ..models import PoseTracker
from ..shelf_calibration.image_reference import prepare_registration
from ..video import VideoSource
from ..shelf_calibration.manual import verify_scene_calibration
from ..runtime_parameters import defaults


def save_json(path,value):
    path.write_text(json.dumps(value,ensure_ascii=False,indent=2),encoding='utf-8')


def percentiles(values):
    return {'mean_ms':round(float(np.mean(values)),3),'p95_ms':round(float(np.percentile(values,95)),3),
            'total_s':round(sum(values)/1000,3),'count':len(values)} if values else {}


def observe(root,config,output,device):
    started=time.perf_counter()
    source=(root/config['video']).resolve()
    src=VideoSource(source,120)
    if not 0<src.fps<=120:
        src.cap.release();raise ValueError('Source FPS must be in (0,120]')
    try:
        verify_scene_calibration(config,source,src.width,src.height,allow_image=True)
        if not config.get('shelf_zones'):raise ValueError('Shelf sessions require calibrated shelf_zones')
        registration,zones,reference_info=prepare_registration(config,src.width,src.height)
    except BaseException:
        src.cap.release();raise
    reference_png=reference_info.pop('reference_png',None)
    if reference_png is not None:(output/'shelf_reference.png').write_bytes(reference_png)
    parameters=config.get('runtime_parameters') or defaults()
    tracker_config=parameters['b_tracker'] if config.get('runtime_parameters') else {'imgsz':config.get('imgsz',640),'cpu_threads':4,'detection_conf':.1}
    pose=PoseTracker(root,{'weights':'models/yolo26n-pose.pt','device':device,**tracker_config},src.fps)
    identity=None
    if config.get('person_archive_path'):
        from ..reid.session_identity import BIdentity
        identity=BIdentity(root,root/config['person_archive_path'],device,parameters)
        if config.get('person_archive_sha256') and identity.sha256!=config['person_archive_sha256']:
            src.cap.release();raise ValueError('人物档案在B启动前已变化，请重新运行以读取同一快照。')
        save_json(output/'person_archive_snapshot.json',identity.data)
    import torch
    hardware={'python_executable':sys.executable,'run_device':device,'torch':torch.__version__,
              'opencv':cv2.__version__,'gpu_name':torch.cuda.get_device_name(0) if device=='cuda' and torch.cuda.is_available() else None}
    save_json(output/'hardware.json',hardware)
    load_s=time.perf_counter()-started
    rows=[];people_map={};timings={'pose':[],'registration':[],'identity':[]};bases=Counter();valid_registration=0
    for index,pts,basis,raw in src:
        tick=time.perf_counter();people=[]
        for p in pose.process(raw,config.get('camera_id','cam01')):
            if p.track_id and len(p.keypoints)>=11:
                people_map.setdefault(p.track_id,f'P{len(people_map)+1:03d}')
                people.append({'id':people_map[p.track_id],'tracklet_id':p.track_id,
                               'box':p.bbox,'keypoints':p.keypoints})
        timings['pose'].append((time.perf_counter()-tick)*1000)
        tick=time.perf_counter()
        if identity:identity.observe(raw,people,pts)
        timings['identity'].append((time.perf_counter()-tick)*1000)
        tick=time.perf_counter()
        _,matrix,quality=registration.process(raw)
        quality={key:value.item() if isinstance(value,np.generic) else value for key,value in quality.items()}
        polygons=[]
        if quality['valid']:
            valid_registration+=1
            for zone in zones:
                poly=cv2.perspectiveTransform(np.asarray(zone['polygon'],np.float32).reshape(-1,1,2),matrix).reshape(-1,2)
                polygons.append({'id':zone['id'],'level':zone.get('level'),'polygon':poly.round(2).tolist()})
        timings['registration'].append((time.perf_counter()-tick)*1000)
        rows.append({'index':index,'frame_id':f'f{index:06d}','time_ms':round(pts,3),'timestamp_basis':basis,
            'width':src.width,'height':src.height,'people':people,'shelf_polygons':polygons,
            # Retain human annotations for display even when automatic registration
            # fails. Detectors still consume only the verified shelf_polygons.
            'reference_shelf_polygons':zones,
            'reference_to_source':matrix.tolist(),'registration_valid':bool(quality['valid']),
            'registration_quality':quality,'parcels':[],'hand_geometry':parameters['hand_geometry']})
        bases[basis]+=1
        if index%max(1,round(src.fps)*3)==0:
            print(f'shelf_session t={pts/1000:.1f}s people={len(people)} parcel_model=disabled',flush=True)
    if not rows: raise ValueError('No decoded frames')
    with (output/'observations.jsonl').open('w',encoding='utf-8') as stream:
        for row in rows:stream.write(json.dumps(row,ensure_ascii=False)+'\n')
    save_json(output/'people.json',people_map)
    info={'width':src.width,'height':src.height,'fps':src.fps,'source_frames':len(rows),
          'duration_ms':rows[-1]['time_ms']+1000/src.fps,'timestamp_basis_counts':dict(bases),
          'observation_wall_s':round(time.perf_counter()-started,3),'model_and_source_setup_s':round(load_s,3),
          'parcel_model_enabled':False,'run_device':device,'hardware':hardware,
          'timings':{key:percentiles(value) for key,value in timings.items()},
          'registration_valid_frames':valid_registration,'calibration_reference':reference_info}
    if identity:
        info['person_archive']={'path':identity.path,'sha256':identity.sha256,'profiles':len(identity.data['profiles']),
            'model':'osnet_x0_5','weights_sha256':identity.encoder.sha256,'read_only_in_B':True}
        save_json(output/'identity_links.json',identity.matcher.transitions)
    save_json(output/'observer_metrics.json',info)
    if reference_info['reference_kind']=='external_image' and not valid_registration:
        raise ValueError('自动配准检查未通过，交互分析未完成；人工标定区域已保留用于可视化核对。此错误不代表人工标定有误。')
    return source,rows,info
