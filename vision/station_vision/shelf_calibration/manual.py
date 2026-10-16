"""Validate manual shelf calibration and its source image/video."""
import copy
import hashlib
import json
from pathlib import Path
import re
import cv2
import numpy as np
from .geometry import simple_polygon

def save_json(path,value):
    path.write_text(json.dumps(value,ensure_ascii=False,indent=2),encoding='utf-8')

def digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream,'sha256').hexdigest()

def validate_zones(value,width,height):
    if not isinstance(value,list) or not 1<=len(value)<=40:
        raise ValueError('请先框选至少一个货架层区域，最多40个区域。')
    zones=[]; ids=set()
    for item in value:
        if not isinstance(item,dict): raise ValueError('区域格式错误。')
        identifier=item.get('id')
        if not isinstance(identifier,str) or not re.fullmatch(r'manual_[0-9]{2,4}',identifier) or identifier in ids:
            raise ValueError('区域ID重复或格式错误。')
        level=item.get('level')
        if level is not None and (isinstance(level,bool) or not isinstance(level,int) or not 1<=level<=20):
            raise ValueError('层号只能是1至20的整数；不确定时留空。')
        polygon=item.get('polygon')
        if not isinstance(polygon,list) or not 4<=len(polygon)<=8:
            raise ValueError('每个区域需要4至8个顶点。')
        for point in polygon:
            if not isinstance(point,list) or len(point)!=2 or any(isinstance(v,bool) or not isinstance(v,(int,float)) or not np.isfinite(v) for v in point):
                raise ValueError('区域顶点坐标无效。')
            if not 0<=point[0]<=width-1 or not 0<=point[1]<=height-1:
                raise ValueError('区域顶点超出原画面。')
        array=np.asarray(polygon,np.float32)
        if not simple_polygon(array) or abs(cv2.contourArea(array))<100:
            raise ValueError('区域太小或四角交叉，请重新调整。')
        ids.add(identifier)
        zones.append({'id':identifier,'level':level,'polygon':np.asarray(polygon,float).round(2).tolist()})
    return zones

def calibrated_config(base,metadata,zones):
    validated=validate_zones(zones,metadata['width'],metadata['height'])
    if metadata['reference_frame_index']!=0:
        raise ValueError('当前主流程以首帧配准；标定参考必须是首帧。')
    config=copy.deepcopy(base)
    config['shelf_numbering']='bottom_up'
    config['shelf_zones']=validated
    config['shelf_calibration']={'source':'manual','reference_frame_index':0,
        'reference_frame_id':'f000000','source_sha256':metadata['source_sha256'],
        'width':metadata['width'],'height':metadata['height']}
    return config

def verify_scene_calibration(config,source,width,height,allow_image=False):
    calibration=config.get('shelf_calibration')
    if calibration is None: return
    if calibration.get('source')=='manual_image':
        if not allow_image:
            raise ValueError('图片标定请使用run_shelf_session_test.ps1并通过-Video指定视频。')
        from .image_reference import decode_reference
        decode_reference(config)
        return
    if calibration.get('source')!='manual' or calibration.get('reference_frame_index')!=0:
        raise ValueError('Unsupported shelf calibration reference')
    if calibration.get('width')!=width or calibration.get('height')!=height or calibration.get('source_sha256')!=digest(source):
        raise ValueError('人工货架标定与当前视频不匹配，请重新标定。')
    validate_zones(config.get('shelf_zones'),width,height)
