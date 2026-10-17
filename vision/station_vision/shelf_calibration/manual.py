"""Local manual shelf calibration. No model calls; saves a new scene configuration."""
import argparse
import copy
from datetime import datetime
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import re
import sys
import threading
from urllib.parse import urlsplit

import cv2
import numpy as np

from .proposal import simple_polygon


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


class CalibrationSession:
    def __init__(self,root,config_path,run_name,video=None):
        self.root=Path(root).resolve()
        if not re.fullmatch(r'[A-Za-z0-9_-]+',run_name): raise ValueError('Unsafe run name')
        self.output=self.root/'outputs'/run_name
        self.output.mkdir(parents=True,exist_ok=False)
        self.base=json.loads((self.root/config_path).read_text(encoding='utf-8-sig'))
        if not self.base.get('florence_klt',{}).get('enabled'):
            raise ValueError('人工分层标定入口需要 Florence/KLT 场景配置。')
        source=(self.root/(video or self.base['video'])).resolve()
        if video: self.base['video']=str(video)
        cap=cv2.VideoCapture(str(source))
        try:
            ok,raw=cap.read()
            if not ok: raise ValueError('Cannot decode first video frame')
            ok,encoded=cv2.imencode('.png',raw)
            if not ok: raise RuntimeError('Cannot preserve reference frame')
            (self.output/'reference.png').write_bytes(encoded.tobytes())
            self.metadata={'source_path':str(source),'source_sha256':digest(source),
                'width':raw.shape[1],'height':raw.shape[0],'reference_frame_index':0,
                'reference_time_ms':cap.get(cv2.CAP_PROP_POS_MSEC),'run_name':run_name,
                'base_config_path':str(config_path),'api_calls':0}
        finally:
            cap.release()
        existing=self.base.get('shelf_calibration')
        initial=[]
        if existing:
            if existing.get('source')!='manual' or existing.get('source_sha256')!=self.metadata['source_sha256'] or existing.get('reference_frame_index')!=0:
                raise ValueError('已有标定与视频不匹配，请使用未标定的基础配置。')
            initial=validate_zones(self.base.get('shelf_zones'),raw.shape[1],raw.shape[0])
        self.initial=initial
        self.lock=threading.Lock()
        self.save_count=0
        save_json(self.output/'inputs.json',self.metadata)
        self.write_html()

    def write_html(self):
        data={'metadata':self.metadata,'base_config':self.base,'initial_zones':self.initial}
        template=Path(__file__).with_name('manual.html').read_text(encoding='utf-8')
        document=template.replace('__DATA__',json.dumps(data,ensure_ascii=False).replace('<','\\u003c'))
        (self.output/'index.html').write_text(document,encoding='utf-8')

    def save(self,zones):
        if digest(Path(self.metadata['source_path']))!=self.metadata['source_sha256']:
            raise ValueError('源视频已改变，请重新打开标定工具。')
        config=calibrated_config(self.base,self.metadata,zones)
        folder=(self.root/'configs/interaction').resolve()
        folder.mkdir(parents=True,exist_ok=True)
        with self.lock:
            self.save_count+=1
            path=folder/f'{self.metadata["run_name"]}_{self.save_count:02d}.json'
            # Never overwrite a previous user save or an unrelated scene config.
            while path.exists():
                self.save_count+=1
                path=folder/f'{self.metadata["run_name"]}_{self.save_count:02d}.json'
            if not path.is_relative_to(folder): raise ValueError('Invalid configuration target')
            with path.open('x',encoding='utf-8') as stream:
                json.dump(config,stream,ensure_ascii=False,indent=2)
            save_json(self.output/'annotations.json',config['shelf_zones'])
            self.initial=config['shelf_zones']; self.write_html()
            relative=path.relative_to(self.root).as_posix()
            result={'status':'saved','config_path':str(path),'relative_config_path':relative,
                'region_count':len(config['shelf_zones']),'api_calls':0,
                'command':f'.\\run_interaction_video.ps1 -Config {relative} -Device cuda -RunName manual_shelf_test_{datetime.now().strftime("%Y%m%d_%H%M%S")}_{self.save_count:02d}'}
            save_json(self.output/'saved.json',result)
            return result


def handler_for(session):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self,*args): pass

        def reply(self,status,body,mime='application/json; charset=utf-8'):
            if not isinstance(body,bytes): body=json.dumps(body,ensure_ascii=False).encode('utf-8')
            self.send_response(status); self.send_header('Content-Type',mime)
            self.send_header('Content-Length',str(len(body))); self.send_header('Cache-Control','no-store')
            self.end_headers(); self.wfile.write(body)

        def do_GET(self):
            route=urlsplit(self.path).path
            routes={'/':('index.html','text/html; charset=utf-8'),'/index.html':('index.html','text/html; charset=utf-8'),
                    '/reference.png':('reference.png','image/png')}
            if route not in routes: self.reply(404,{'error':'Not found'}); return
            name,mime=routes[route]
            self.reply(200,(session.output/name).read_bytes(),mime)

        def do_POST(self):
            expected=f'http://127.0.0.1:{self.server.server_port}'
            if self.headers.get('Origin')!=expected or self.headers.get('Host')!=f'127.0.0.1:{self.server.server_port}':
                self.reply(403,{'error':'Only this local calibration page can save'}); return
            if urlsplit(self.path).path!='/save': self.reply(404,{'error':'Not found'}); return
            try:
                size=int(self.headers.get('Content-Length','0'))
                if not 0<size<=262144 or not self.headers.get('Content-Type','').startswith('application/json'):
                    raise ValueError('Invalid request')
                body=json.loads(self.rfile.read(size))
                result=session.save(body.get('zones'))
                self.reply(200,result)
            except (ValueError,TypeError,AttributeError) as error:
                self.reply(400,{'error':str(error)})
    return Handler


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--config',default='configs/interaction/florence_klt_0fc72122.json')
    parser.add_argument('--video')
    parser.add_argument('--run-name',default='manual_shelf_'+datetime.now().strftime('%Y%m%d_%H%M%S'))
    parser.add_argument('--port',type=int,default=0)
    args=parser.parse_args()
    root=Path(__file__).resolve().parents[2]
    if Path(sys.prefix).resolve()!=(root/'.venv-track').resolve(): raise RuntimeError('Use project .venv-track')
    session=CalibrationSession(root,args.config,args.run_name,args.video)
    server=ThreadingHTTPServer(('127.0.0.1',args.port),handler_for(session))
    url=f'http://127.0.0.1:{server.server_port}/'
    save_json(session.output/'ready.json',{'url':url,'api_calls':0,'status':'awaiting_manual_annotations'})
    print('MANUAL SHELF CALIBRATION '+url,flush=True)
    print('Save creates a new config in configs/interaction. Ctrl+C stops this local server.',flush=True)
    try: server.serve_forever()
    except KeyboardInterrupt: pass
    finally: server.server_close()


if __name__=='__main__': main()
