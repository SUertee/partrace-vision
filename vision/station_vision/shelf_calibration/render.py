"""Overlay unverified shelf proposals and preview their registration on source frames."""
import html
import json
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont

from ..interaction.pipeline import digest, save_json, annotate
from ..registration import FrameRegistration


COLORS = [(60,170,245),(220,100,245),(80,210,130),(245,180,70),(100,130,250),(230,170,190)]


def read_image(path):
    image=cv2.imdecode(np.frombuffer(Path(path).read_bytes(),np.uint8),cv2.IMREAD_COLOR)
    if image is None: raise ValueError('Cannot decode image')
    return image


def save_image(path,image):
    ok,data=cv2.imencode(Path(path).suffix,image)
    if not ok: raise RuntimeError('Cannot encode preview')
    Path(path).write_bytes(data.tobytes())


def grid_image(raw):
    canvas=Image.fromarray(cv2.cvtColor(raw,cv2.COLOR_BGR2RGB)).convert('RGBA')
    layer=Image.new('RGBA',canvas.size,(0,0,0,0)); draw=ImageDraw.Draw(layer)
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',17)
    width,height=canvas.size
    for n in range(0,1001,100):
        x=(width-1)*n/1000; y=(height-1)*n/1000
        draw.line([(x,0),(x,height-1)],fill=(255,240,40,85),width=1)
        draw.line([(0,y),(width-1,y)],fill=(255,240,40,85),width=1)
        draw.text((min(x+2,width-45),4),str(n),font=font,fill=(255,240,40,255),stroke_width=1,stroke_fill='black')
        draw.text((3,min(y+2,height-25)),str(n),font=font,fill=(255,240,40,255),stroke_width=1,stroke_fill='black')
    return cv2.cvtColor(np.asarray(Image.alpha_composite(canvas,layer).convert('RGB')),cv2.COLOR_RGB2BGR)


def overlay(raw,zones,matrix=None):
    matrix=np.eye(3) if matrix is None else matrix
    image=raw.copy()
    layer=raw.copy()
    mapped=[]
    for i,zone in enumerate(zones):
        poly=cv2.perspectiveTransform(np.asarray(zone['polygon'],np.float32).reshape(-1,1,2),matrix).reshape(-1,2)
        edge=None
        if zone.get('support_line'):
            edge=cv2.perspectiveTransform(np.asarray(zone['support_line'],np.float32).reshape(-1,1,2),matrix).reshape(-1,2)
        if not np.isfinite(poly).all() or (edge is not None and not np.isfinite(edge).all()): continue
        points=np.rint(poly).astype(np.int32)
        col=COLORS[i%len(COLORS)]
        cv2.fillPoly(layer,[points],col)
        mapped.append((zone,points,np.rint(edge).astype(np.int32) if edge is not None else None,col))
    image=cv2.addWeighted(layer,.17,image,.83,0)
    canvas=Image.fromarray(cv2.cvtColor(image,cv2.COLOR_BGR2RGB)); draw=ImageDraw.Draw(canvas)
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',19)
    for zone,points,edge,col in mapped:
        rgb=tuple(reversed(col))
        draw.line([tuple(p) for p in points]+[tuple(points[0])],fill=rgb,width=3)
        if edge is not None: draw.line([tuple(p) for p in edge],fill=rgb,width=5)
        for p in points:
            x,y=map(int,p); draw.ellipse((x-4,y-4,x+4,y+4),fill=rgb)
        x,y=np.clip(points.mean(axis=0),[5,25],[raw.shape[1]-170,raw.shape[0]-28])
        text=zone['id']+(' 第'+str(zone['level'])+'层' if zone['level'] is not None else ' 层号未知')
        draw.rectangle((x-3,y-2,x+170,y+26),fill='#111827')
        draw.text((x,y),text,font=font,fill=rgb)
    return cv2.cvtColor(np.asarray(canvas),cv2.COLOR_RGB2BGR)


def panel(frame,texts):
    scale=min(1,960/frame.shape[0])
    display=cv2.resize(frame,(round(frame.shape[1]*scale),round(frame.shape[0]*scale)))
    h,w=display.shape[:2]
    canvas=Image.new('RGB',(w+500,h),'#151c2b')
    canvas.paste(Image.fromarray(cv2.cvtColor(display,cv2.COLOR_BGR2RGB)),(0,0))
    draw=ImageDraw.Draw(canvas); font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',19)
    y=15
    for text in texts:
        line=''
        for ch in text:
            if draw.textlength(line+ch,font=font)>470:
                draw.text((w+15,y),line,font=font,fill='white'); y+=30; line=''
            line+=ch
        draw.text((w+15,y),line,font=font,fill='white'); y+=40
    return cv2.cvtColor(np.asarray(canvas),cv2.COLOR_RGB2BGR)


def export_preview(root,output,metadata,proposal,reference):
    source=Path(metadata['source_path'])
    observations={}
    name=metadata.get('observations_run')
    if name:
        if not name.replace('_','').replace('-','').isalnum(): raise ValueError('Unsafe observation run')
        origin=root/'outputs'/name
        summary=json.loads((origin/'summary.json').read_text(encoding='utf-8'))
        if summary['source_sha256'] != metadata['source_sha256']: raise ValueError('Observation video differs')
        observations={r['index']:r for r in (json.loads(x) for x in (origin/'observations.jsonl').read_text(encoding='utf-8').splitlines())}
    registration=FrameRegistration(True)
    registration.process(reference)
    cap=cv2.VideoCapture(str(source)); writer=None
    stride=max(1,round(metadata['fps']/5)); fps=metadata['fps']/stride
    records=[]; index=-1
    try:
        while True:
            ok,raw=cap.read()
            if not ok: break
            index+=1
            if index%stride: continue
            pts=cap.get(cv2.CAP_PROP_POS_MSEC)
            _,matrix,quality=registration.process(raw)
            image=annotate(raw,observations[index]) if index in observations else raw
            if quality['valid']: image=overlay(image,proposal['zones'],matrix)
            manual=metadata.get('calibration_source')=='manual'
            texts=['人工货架标定｜动态检查' if manual else '多模态货架点选｜待核对',f'源时间 {pts/1000:.2f}s，源帧 {index}',
                '配准：'+('有效；区域随画面更新' if quality['valid'] else '失败；暂停显示层区域'),
                '彩色区域与层号来自你的人工标定。' if manual else '彩色区域与粗线均由模型提出，尚未人工确认。',
                '圆点：手工框选区域的顶点。只检查区域位置，不确定拿取来源层。' if manual else '粗线：储物板前沿候选；圆点：区域顶点。',
                '人物与包裹框来自已有观测；没有新增拿取或来源层结论。',
                '本视频是约5帧/秒采样预览，不是实时性能测试。']
            image=panel(image,texts)
            if writer is None:
                h,w=image.shape[:2]
                writer=cv2.VideoWriter(str(output/'shelf_preview.mp4'),cv2.VideoWriter_fourcc(*'mp4v'),fps,(w+w%2,h+h%2))
                if not writer.isOpened(): raise RuntimeError('Cannot create shelf preview')
            writer.write(cv2.copyMakeBorder(image,0,image.shape[0]%2,0,image.shape[1]%2,cv2.BORDER_CONSTANT))
            if index in (0,120,240,360,540): save_image(output/f'preview_f{index:06d}.jpg',image)
            quality={k:(v.item() if isinstance(v,np.generic) else v) for k,v in quality.items()}
            records.append({'source_index':index,'time_ms':pts,'registration':quality})
    finally:
        cap.release()
        if writer: writer.release()
    save_json(output/'registration.json',records)
    return {'preview_frames':len(records),'preview_fps':fps,'registration_valid_frames':sum(r['registration']['valid'] for r in records),
            'registration_invalid_frames':sum(not r['registration']['valid'] for r in records),'realtime_evaluated':False}


def render_saved(root,output,video=True):
    metadata=json.loads((output/'inputs.json').read_text(encoding='utf-8'))
    proposal=json.loads((output/'proposal.json').read_text(encoding='utf-8'))
    if digest(Path(metadata['source_path'])) != metadata['source_sha256']: raise ValueError('Source changed')
    ref=metadata['images'][0]
    if digest(output/ref['path']) != ref['sha256']: raise ValueError('Reference changed')
    reference=read_image(output/ref['path'])
    image=overlay(reference,proposal['zones'])
    save_image(output/'proposal_overlay.png',image)
    save_image(output/'source_compare.jpg',np.hstack([reference,image]))
    save_json(output/'draft_calibration.json',{'status':'vlm_proposed_unverified',
        'source_sha256':metadata['source_sha256'],'reference_frame_id':ref['frame_id'],'reference_frame_index':ref['index'],
        'shelf_numbering':'bottom_up','shelf_zones':[{k:z[k] for k in ('id','level','polygon','support_line')} for z in proposal['zones']],
        'approved_for_action_inference':False})
    if video:
        preview=export_preview(root,output,metadata,proposal,reference)
        summary=json.loads((output/'summary.json').read_text(encoding='utf-8'))
        summary.update(preview); save_json(output/'summary.json',summary)
    summary=json.loads((output/'summary.json').read_text(encoding='utf-8'))
    notes=[]
    if (output/'visual_inspection.json').exists():
        inspection=json.loads((output/'visual_inspection.json').read_text(encoding='utf-8'))
        if inspection['source_sha256']!=metadata['source_sha256']: raise ValueError('Inspection belongs to another video')
        notes=inspection['observations']
    data={'proposal':proposal,'summary':summary,'reference':ref['path'],'reference_index':ref['index'],
          'source_sha256':metadata['source_sha256'],'has_previous':(output/'previous_overlay.png').exists(),'inspection':notes}
    video_html=''
    if (output/'shelf_preview.mp4').exists():
        video_html='<section><h2>动态叠加预览</h2><p>约5帧/秒采样，区域通过配准随画面更新。不是实时性能测试。</p><video controls preload="metadata" src="shelf_preview.mp4"></video><p><a href="shelf_preview.mp4">本地播放器打开视频</a> · <a href="registration.json">配准记录</a></p>'
        for path in sorted(output.glob('preview_f*.jpg')):
            video_html+='<details><summary>'+html.escape(path.stem)+'</summary><img class="snapshot" src="'+html.escape(path.name)+'"></details>'
        video_html+='</section>'
    document=Path(__file__).with_name('editor.html').read_text(encoding='utf-8')
    document=document.replace('__VIDEO_SECTION__',video_html).replace('__DATA__',json.dumps(data,ensure_ascii=False).replace('<','\\u003c'))
    (output/'comparison.html').write_text(document,encoding='utf-8')
    print('VISUALIZATION '+str(output/'comparison.html'),flush=True)
