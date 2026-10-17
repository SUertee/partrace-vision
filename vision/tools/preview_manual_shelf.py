"""Preview saved manual shelf zones on source frames, without model inference/API."""
import argparse
from datetime import datetime
import html
import json
from pathlib import Path

import cv2
import numpy as np

from station_vision.shelf_calibration.manual import verify_scene_calibration,save_json,digest
from station_vision.shelf_calibration.render import overlay,save_image,export_preview


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--config',required=True)
    parser.add_argument('--observations-run',default='')
    parser.add_argument('--run-name',default='manual_shelf_preview_'+datetime.now().strftime('%Y%m%d_%H%M%S'))
    args=parser.parse_args()
    if not args.run_name.replace('_','').replace('-','').isalnum(): raise ValueError('Unsafe run name')
    root=Path(__file__).resolve().parents[1]
    config_path=(root/args.config).resolve()
    config=json.loads(config_path.read_text(encoding='utf-8-sig'))
    source=(root/config['video']).resolve()
    cap=cv2.VideoCapture(str(source))
    try:
        ok,raw=cap.read()
        if not ok: raise ValueError('Cannot decode reference frame')
        height,width=raw.shape[:2]
        verify_scene_calibration(config,source,width,height)
        if not config.get('shelf_calibration'): raise ValueError('Need a saved manual calibration')
        metadata={'source_path':str(source),'source_sha256':digest(source),'width':width,'height':height,
            'fps':cap.get(cv2.CAP_PROP_FPS),'source_frames':int(cap.get(cv2.CAP_PROP_FRAME_COUNT)),
            'calibration_source':'manual','observations_run':args.observations_run,
            'config_path':str(config_path),'config_sha256':digest(config_path)}
    finally:
        cap.release()
    output=root/'outputs'/args.run_name
    output.mkdir(parents=True,exist_ok=False)
    save_json(output/'inputs.json',metadata)
    (output/'config_snapshot.json').write_bytes(config_path.read_bytes())
    save_image(output/'reference.png',raw)
    image=overlay(raw,config['shelf_zones'])
    save_image(output/'reference_overlay.png',image)
    save_image(output/'reference_compare.jpg',np.hstack([raw,image]))
    preview=export_preview(root,output,metadata,{'zones':config['shelf_zones']},raw)
    video=cv2.VideoCapture(str(output/'shelf_preview.mp4'));decoded=0
    while video.read()[0]:decoded+=1
    video.release()
    if decoded!=preview['preview_frames']: raise ValueError('Incomplete preview export')
    summary={'status':'completed','manual_regions':len(config['shelf_zones']),
        'configured_levels':[z['level'] for z in config['shelf_zones']],
        'source_and_config_checked':True,'api_calls':0,'detector_rerun':False,
        'observations_reused_from':args.observations_run or None,'exported_frames_decoded':decoded,
        'pickup_origin_inference_implemented':False,**preview}
    save_json(output/'summary.json',summary)
    cards=''.join('<section><h2>源帧 '+html.escape(p.stem.removeprefix('preview_f'))+'</h2><img src="'+p.name+'"></section>' for p in sorted(output.glob('preview_f*.jpg')))
    document=f'''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>人工货架标定动态检查</title><style>body{{font:16px/1.65 system-ui,"Microsoft YaHei",sans-serif;max-width:1150px;margin:auto;padding:24px;background:#f3f5f9;color:#182335}}section{{background:white;border-radius:12px;padding:16px;margin:18px 0}}img,video{{max-width:100%;height:auto}}video{{max-height:750px;background:#111}}a{{color:#245eab}}.note{{background:#fff4d6;padding:12px}}</style><h1>人工货架标定动态检查</h1><p>已读取 {len(config['shelf_zones'])} 个手工区域，层号 {html.escape(str(summary['configured_levels']))}。视频哈希、尺寸、首帧坐标和区域结构检查通过。</p><p class="note">只验证区域叠加和随镜头配准；未重新运行人物/包裹模型，未调用Qwen，也未确认任何拿取来源层。人物与包裹框如存在，复用此前同视频的观测。</p><section><h2>首帧：左原图，右人工区域</h2><img src="reference_compare.jpg"></section><section><h2>动态预览</h2><video controls preload="metadata" src="shelf_preview.mp4"></video><p><a href="shelf_preview.mp4">本地播放器打开视频</a> · <a href="summary.json">检查记录</a></p><p>约5帧/秒采样预览，不是实时性能测试。配准质量检查通过 {preview['registration_valid_frames']} 帧，失败 {preview['registration_invalid_frames']} 帧；通过不等于区域完全对齐，仍需观察边界。</p></section>{cards}</html>'''
    (output/'comparison.html').write_text(document,encoding='utf-8')
    print(json.dumps(summary,ensure_ascii=False),flush=True)
    print('PREVIEW '+str(output/'comparison.html'),flush=True)


if __name__=='__main__':main()
