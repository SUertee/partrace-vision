"""Ask the configured VLM for shelf points, preserving source images and responses."""
import argparse
from datetime import datetime
import json
from pathlib import Path
import shutil
import sys

import cv2

from ..interaction.pipeline import save_json, digest
from ..interaction.review import image_part
from ..multiframe_review.client import CompatibleClient, parse_response
from .proposal import PROMPT, GUIDED_ADDITION, normalize


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--video',default='test_video_new/0fc72122c2a6a082e2d4f3fdcdd2a8f1.mp4')
    parser.add_argument('--reference-frame',type=int,default=0)
    parser.add_argument('--context-frames',default='120,540')
    parser.add_argument('--run-name',default='shelf_vlm_'+datetime.now().strftime('%Y%m%d_%H%M%S'))
    parser.add_argument('--use-vlm',action='store_true')
    parser.add_argument('--render-only',action='store_true',help='Use saved proposal; zero API calls')
    parser.add_argument('--no-video',action='store_true')
    parser.add_argument('--observations-run',default='',help='Optional source-hash-checked existing person/parcel observations')
    parser.add_argument('--guide-from-run',default='',help='Refine a saved proposal using raw pixels and a coordinate grid')
    parser.add_argument('--model',default='qwen3.8-max')
    parser.add_argument('--base-url',default='https://maas.qianwenaiapi.com/compatible-mode/v1')
    args=parser.parse_args()
    root=Path(__file__).resolve().parents[2]
    if Path(sys.prefix).resolve() != (root/'.venv-track').resolve():
        raise RuntimeError('Use project .venv-track')
    if not args.run_name.replace('_','').replace('-','').isalnum():
        raise ValueError('Unsafe run name')
    output=root/'outputs'/args.run_name
    if args.render_only:
        from .render import render_saved
        render_saved(root,output,not args.no_video)
        return
    output.mkdir(parents=True,exist_ok=False)
    save_json(output/'summary.json',{'status':'running'})
    source=(root/args.video).resolve()
    cap=cv2.VideoCapture(str(source))
    images=[]
    try:
        if not cap.isOpened(): raise ValueError('Cannot open video')
        width,height=int(cap.get(cv2.CAP_PROP_FRAME_WIDTH)),int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
        fps,count=cap.get(cv2.CAP_PROP_FPS),int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
        indices=[args.reference_frame]+[int(x.strip()) for x in args.context_frames.split(',') if x.strip()]
        if any(i<0 or i>=count for i in indices): raise ValueError('Frame outside video')
        for index in dict.fromkeys(indices):
            cap.set(cv2.CAP_PROP_POS_FRAMES,index)
            ok,raw=cap.read()
            if not ok: raise ValueError('Cannot decode selected frame')
            path=output/f'f{index:06d}.png'
            ok,encoded=cv2.imencode('.png',raw)
            if not ok: raise RuntimeError('Cannot preserve source frame')
            path.write_bytes(encoded.tobytes())
            images.append({'frame_id':f'f{index:06d}','index':index,'time_ms':cap.get(cv2.CAP_PROP_POS_MSEC),
                           'path':path.name,'sha256':digest(path),'view':'reference' if index==args.reference_frame else 'context'})
        metadata={'source_path':str(source),'source_sha256':digest(source),'width':width,'height':height,
                  'fps':fps,'source_frames':count,'images':images,'reference_frame_id':images[0]['frame_id'],
                  'model':args.model,'base_url':args.base_url,'geometry_only':True,'observations_run':args.observations_run}
        save_json(output/'inputs.json',metadata)
        prompt=PROMPT+(GUIDED_ADDITION if args.guide_from_run else '')
        (output/'prompt.txt').write_text(prompt,encoding='utf-8')
        if not args.use_vlm:
            save_json(output/'summary.json',{'status':'inputs_prepared','api_calls':0})
            print('INPUTS '+str(output),flush=True)
            return
        content=[]
        for record in images:
            content.append({'type':'text','text':json.dumps({'frame_id':record['frame_id'],'view':record['view'],
                            'source_time_ms':record['time_ms'],'width':width,'height':height},ensure_ascii=False)})
            content.append(image_part(output/record['path']))
        if args.guide_from_run:
            if not args.guide_from_run.replace('_','').replace('-','').isalnum(): raise ValueError('Unsafe guidance run')
            prior=root/'outputs'/args.guide_from_run
            before=json.loads((prior/'inputs.json').read_text(encoding='utf-8'))
            if before['source_sha256']!=metadata['source_sha256'] or before['reference_frame_id']!=metadata['reference_frame_id']:
                raise ValueError('Guidance must refer to exactly the same source and reference frame')
            from .render import grid_image, read_image, save_image
            save_image(output/'reference_grid.png',grid_image(read_image(output/images[0]['path'])))
            shutil.copy2(prior/'proposal_overlay.png',output/'previous_overlay.png')
            previous=parse_response(json.loads((prior/'response.json').read_text(encoding='utf-8')))
            save_json(output/'guidance.json',{'origin_run':args.guide_from_run,'previous_proposal':previous,
                'grid_sha256':digest(output/'reference_grid.png'),'previous_overlay_sha256':digest(output/'previous_overlay.png')})
            content += [{'type':'text','text':'coordinate_aid：同一参考帧的归一化网格，仅用于坐标定位。'},image_part(output/'reference_grid.png'),
                {'type':'text','text':'previous_overlay：上一轮模型叠加，可能错误，必须重新检查原图。'},image_part(output/'previous_overlay.png'),
                {'type':'text','text':json.dumps({'previous_model_proposal':previous},ensure_ascii=False)}]
        image_count=sum(item['type']=='image_url' for item in content)
        print('VLM shelf point proposal: '+str(image_count)+' images',flush=True)
        response,elapsed=CompatibleClient(args.base_url,args.model).complete(
            [{'role':'system','content':prompt},{'role':'user','content':content}],max_tokens=6000)
        save_json(output/'response.json',response)
        proposal=normalize(parse_response(response),images[0]['frame_id'],width,height)
        save_json(output/'proposal.json',proposal)
        save_json(output/'summary.json',{'status':'proposed_unverified' if proposal['zones'] else 'no_valid_zones',
                  'api_calls':1,'elapsed_model_s':elapsed,'api_model':response.get('model'),
                  'usage':response.get('usage'),'proposed_zones':len(proposal['zones']),
                  'image_inputs':image_count,'guidance_run':args.guide_from_run or None,
                  'known_level_zones':sum(z['level'] is not None for z in proposal['zones']),
                  'rejected_zones':len(proposal['rejected']),'human_verified':False,
                  'pickup_accuracy_evaluated':False,'shelf_level_accuracy_evaluated':False})
        print('PROPOSAL '+str(output),flush=True)
    except BaseException as exc:
        save_json(output/'summary.json',{'status':'failed','error':str(exc)})
        raise
    finally:
        cap.release()
    from .render import render_saved
    render_saved(root,output,not args.no_video)


if __name__=='__main__':
    main()
