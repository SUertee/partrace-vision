"""Minimal actor/layer views, exact five-frame sheets, clips and source-rate video."""
import html
from pathlib import Path

import cv2
import numpy as np
from PIL import Image,ImageDraw
from .reach import palm_cues,reference_points
from ..fonts import load_font


def draw_frame(raw,row,person_id,region_id=None,parcel_evidence=False,show_identity=False):
    canvas=Image.fromarray(cv2.cvtColor(raw,cv2.COLOR_BGR2RGB));draw=ImageDraw.Draw(canvas)
    font=load_font(20)
    person_ids={person_id} if isinstance(person_id,str) else set(person_id)
    region_ids={region_id} if isinstance(region_id,str) or region_id is None else set(region_id)
    for z in row['shelf_polygons']:
        points=[tuple(p) for p in z['polygon']]
        col='#ffb94f' if z['id'] in region_ids else '#a2a9b5'
        draw.line(points+[points[0]],fill=col,width=4 if z['id'] in region_ids else 1)
        x,y=np.asarray(points).mean(axis=0)
        draw.text((max(0,min(raw.shape[1]-100,x)),max(0,min(raw.shape[0]-25,y))),
                  f'第{z["level"]}层' if z['level'] is not None else '层号未知',font=font,fill=col)
    for p in row['people']:
        if p['id'] not in person_ids:continue
        draw.rectangle(p['box'],outline='#51baf7',width=3)
        label=p['id']
        if show_identity and 'identity_status' in p:label+=' | '+(p.get('profile_id') or '人物未确定')
        draw.text((p['box'][0],max(0,p['box'][1]-25)),label,font=font,fill='#51baf7')
        for i,j in ((5,7),(7,9),(6,8),(8,10)):
            a,b=p['keypoints'][i],p['keypoints'][j]
            if min(a[2],b[2])>=.35:draw.line([tuple(a[:2]),tuple(b[:2])],fill='#51baf7',width=3)
        for cue in palm_cues(row,p):
            wrist=tuple(cue['wrist']);tip=tuple(cue['palm_estimate'])
            # Short dashed extension distinguishes an estimated region from
            # measured wrist joints. Never draw it as a contact/object track.
            for a in np.arange(0,1,.25):
                left=np.asarray(wrist)+(np.asarray(tip)-wrist)*a
                right=np.asarray(wrist)+(np.asarray(tip)-wrist)*min(1,a+.12)
                draw.line([tuple(left),tuple(right)],fill='#ffb94f',width=3)
            draw.ellipse([tip[0]-5,tip[1]-5,tip[0]+5,tip[1]+5],outline='#ffb94f',width=2)
    if parcel_evidence:
        for p in row.get('parcels',[]):
            if p['box'] is None or not p['valid'] or not person_ids.intersection(p.get('near_person_ids',[])):continue
            draw.rectangle(p['box'],outline='#73db9f',width=2)
            draw.text((p['box'][0],max(0,p['box'][1]-22)),p['id']+' '+p['source'],font=font,fill='#73db9f')
    draw.text((10,10),f'{row["time_ms"]/1000:.3f}s / {row["frame_id"]}',font=font,fill='white',stroke_width=2,stroke_fill='black')
    draw.text((10,40),'蓝：姿态腕点｜橙色虚线：估计手区，非接触证明',font=load_font(16),fill='white',stroke_width=2,stroke_fill='black')
    return cv2.cvtColor(np.asarray(canvas),cv2.COLOR_RGB2BGR)


def save_image(path,img):
    suffix=path.suffix
    ok,data=cv2.imencode(suffix,img,[cv2.IMWRITE_JPEG_QUALITY,94] if suffix.lower()=='.jpg' else [])
    if not ok:raise RuntimeError('Image encode failed')
    path.write_bytes(data.tobytes())


def export_video(source,rows,sessions,results,output,fps,parcel_evidence=False):
    cap=cv2.VideoCapture(str(source));writer=None;clips={};count=0
    font=load_font(20);small=load_font(17)
    try:
        for row in rows:
            ok,raw=cap.read()
            if not ok:raise ValueError('Source frame missing at export')
            active=[s for s in sessions if s['start_ms']<=row['time_ms']<=s['end_ms']]
            target=active[0] if active else None
            ids={p['id'] for p in row['people']} if any('identity_status' in p for p in row['people']) else {s['person_id'] for s in active}
            image=draw_frame(raw,row,ids,{s['region_id'] for s in active},parcel_evidence,show_identity=True)
            scale=min(1,960/raw.shape[0]);image=cv2.resize(image,(round(raw.shape[1]*scale),round(raw.shape[0]*scale)))
            h,w=image.shape[:2];board=Image.new('RGB',(w+540,h),'#141c29')
            board.paste(Image.fromarray(cv2.cvtColor(image,cv2.COLOR_BGR2RGB)),(0,0));draw=ImageDraw.Draw(board)
            lines=['货架交互会话｜五帧单次复核',f'源时间 {row["time_ms"]/1000:.2f}s',
                   '同一人连续操作合并；层号另行复核。', '模型评分未经校准，不代表真实概率。',
                   '三帧优先：首次伸手、最深处、收手。','当前会话：'+(target['id'] if target else '无活动会话')]
            y=15
            for text in lines:draw.text((w+15,y),text,font=small,fill='#dce7f4');y+=34
            for s in sessions:
                result=results.get(s['id'],{});op=result.get('opinion') or {}
                label={True:'可见取件',False:'未见该时段取件',None:'无法判断'}.get(op.get('pickup_detected'))
                score=op.get('pickup_score');score_text='未知' if score is None else f'{score:.2f}（自评分）'
                layer_text=(f'模型第{op.get("model_shelf_level")}层，存在冲突，最终层号未知'
                            if 'shelf_level_conflict' in op.get('warnings',[]) else f'复核层号：{op.get("shelf_level",s["shelf_level"])}')
                if result.get('status')!='completed':layer_text=f'层号待复核（算法候选：{s["shelf_level"]}）'
                archive_text=''
                if 'identity' in s:archive_text='｜档案 '+(s['identity']['profile_id'] or '人物未确定')
                for text in (f'{s["id"]} {s["person_id"]}{archive_text}',f'算法候选第{s["shelf_level"]}层',layer_text,
                             f'{s["start_ms"]/1000:.2f}–{s["end_ms"]/1000:.2f}s',
                             f'{label}；{score_text}',f'一次请求：{result.get("elapsed_s","未复核")}秒'):
                    draw.text((w+15,y),text,font=font,fill='#90e4b4');y+=32
                y+=22
            array=cv2.cvtColor(np.asarray(board),cv2.COLOR_RGB2BGR)
            if writer is None:
                height,width=array.shape[:2]
                writer=cv2.VideoWriter(str(output/'interaction_review.mp4'),cv2.VideoWriter_fourcc(*'mp4v'),fps,(width+width%2,height+height%2))
                if not writer.isOpened():raise RuntimeError('Cannot export session video')
            writer.write(cv2.copyMakeBorder(array,0,array.shape[0]%2,0,array.shape[1]%2,cv2.BORDER_CONSTANT))
            for s in sessions:
                evidence=s.get('algorithm_parameters',{}).get('evidence',{})
                if max(0,s['start_ms']-evidence.get('before_ms',300))<=row['time_ms']<=s['end_ms']+evidence.get('after_ms',300):
                    if s['id'] not in clips:
                        folder=output/'keyframes'/s['id'];folder.mkdir(parents=True,exist_ok=True)
                        clip=cv2.VideoWriter(str(folder/'source_clip.mp4'),cv2.VideoWriter_fourcc(*'mp4v'),fps,(raw.shape[1],raw.shape[0]))
                        if not clip.isOpened():raise RuntimeError('Cannot export source clip')
                        clips[s['id']]=clip
                    clips[s['id']].write(raw)
            count+=1
    finally:
        cap.release()
        if writer:writer.release()
        for clip in clips.values():clip.release()
    return count


def model_sheet(output,session_id,frames):
    sheet=Image.new('RGB',(1200,460),'#151c28');draw=ImageDraw.Draw(sheet)
    font=load_font(17)
    for i,f in enumerate(frames):
        img=Image.open(output/f['model_image_path']).convert('RGB');img.thumbnail((240,420))
        sheet.paste(img,(i*240+(240-img.width)//2,40))
        draw.text((i*240+4,3),f'{f["role"]}\n{f["actual_ms"]/1000:.3f}s',font=font,fill='white')
    sheet.save(output/'keyframes'/session_id/'five_model_frames.jpg',quality=95)


def reach_diagnostic(rows,session,frames,output):
    """Explain wrist versus estimated hand geometry, separately from raw evidence."""
    reach=session.get('reach_anchor')
    if not reach:return
    peak=next(f for f in frames if f['role']=='reach_peak')
    raw=Image.open(output/peak['raw_path']).convert('RGB')
    row=rows[peak['source_frame_index']]
    source=cv2.cvtColor(np.asarray(raw),cv2.COLOR_RGB2BGR)
    marked=draw_frame(source,row,session['person_id'],session['region_id'])
    canvas=Image.fromarray(cv2.cvtColor(marked,cv2.COLOR_BGR2RGB));draw=ImageDraw.Draw(canvas)
    wrist_path=[];palm_path=[]
    matrix=np.asarray(row.get('reference_to_source',np.eye(3)),float)
    for r in rows:
        if not reach['onset_ms']<=r['time_ms']<=reach['withdrawal_ms'] or not r.get('registration_valid'):continue
        person=next((p for p in r['people'] if p['id']==session['person_id']),None)
        if person is None:continue
        cue=next((c for c in palm_cues(r,person) if c['hand']==reach['hand']),None)
        if cue is None:continue
        points=reference_points(r,[cue['wrist'],cue['palm_estimate']])
        mapped=cv2.perspectiveTransform(points.reshape(-1,1,2),matrix).reshape(-1,2)
        wrist_path.append(tuple(mapped[0]));palm_path.append(tuple(mapped[1]))
    for path,col in ((wrist_path,'#51baf7'),(palm_path,'#ffb94f')):
        if len(path)>1:draw.line(path,fill=col,width=3)
    board=Image.new('RGB',(raw.width+500,raw.height),'#141c29');board.paste(canvas,(0,0));draw=ImageDraw.Draw(board)
    font=load_font(21)
    lines=['首次伸手轨迹核查（非原始证据）',
        '蓝色：检测到的腕点轨迹', '橙色：肘腕方向延长的估计手区',
        '估计手区不是接触证明，也不是包裹。',
        f'人物：{session["person_id"]}；主操作手：{reach["hand"]}',
        f'伸手起点：{reach["onset_ms"]/1000:.3f}s',
        f'转折峰值：{reach["peak_ms"]/1000:.3f}s',
        f'收手帧：{reach["withdrawal_ms"]/1000:.3f}s',
        f'算法来源层候选：{session["shelf_level"]}',
        '层号仅在首次伸手峰值附近投票。',
        '不按后续持有高度重新指定来源层。',
        '模型仍须检查物品原处与手中状态。']
    for i,line in enumerate(lines):draw.text((raw.width+15,20+i*42),line,font=font,fill='#dce7f4')
    board.save(output/'keyframes'/session['id']/'reach_geometry.jpg',quality=95)


def report(output,summary,sessions,details,ablation=None,evaluation=None):
    esc=lambda value:html.escape(str(value),quote=True)
    sections=[]
    for d in details:
        s=d['session'];r=d['review'];op=r.get('opinion') or {}
        cards=''.join(f'<figure><a href="{esc(f["raw_path"])}"><img src="{esc(f["raw_path"])}"></a><figcaption>{esc(f["role"])} · {f["actual_ms"]/1000:.3f}s · {esc(f["frame_id"])}</figcaption></figure>' for f in d['keyframes'])
        sections.append(f'<section><h2>{esc(s["id"])} · {esc(s["person_id"])} · 第{esc(s["shelf_level"])}层</h2>'
            f'<p>{s["start_ms"]/1000:.3f}–{s["end_ms"]/1000:.3f}s。结束依据：{esc(s["close_reason"])}。</p>'
            f'<p>有无取件：{esc(op.get("pickup_detected"))}；模型自评分：{esc(op.get("pickup_score"))}；一次请求：{esc(r.get("elapsed_s"))}秒。</p>'
            f'<p>{esc(op.get("reason",r.get("error","未调用模型")))}</p><p>层号依据：{esc(op.get("shelf_reason","待复核"))}</p>'
            f'<p><a href="keyframes/{esc(s["id"])}/source_clip.mp4">查看原始内容短片（重新编码）</a> · <a href="keyframes/{esc(s["id"])}/five_frames.jpg">五帧合图</a></p>'
            f'<div class="frames">{cards}</div></section>')
        if 'identity' in s:
            identity=s['identity']
            sections.append(f'<section><h2>人物档案归属</h2><p>本地轨迹 {esc(s["person_id"])} → 档案 {esc(identity["profile_id"] or "人物未确定")}；状态 {esc(identity["status"])}。独立匹配观测 {identity["independent_matched_observations"]} 次，匹配覆盖 {identity["matched_feature_coverage"]:.1%}。外观相似度不是身份概率。</p></section>')
        if (output/'keyframes'/s['id']/'reach_geometry.jpg').exists():
            sections.append(f'<section><h2>腕点与估计操作位置</h2><img src="keyframes/{esc(s["id"])}/reach_geometry.jpg"><p>中间三帧来自首次伸手的实际轨迹转折；橙色点是几何估计，不是真实手部或包裹检测。</p></section>')
    ab_images=''
    if ablation:
        for pair in ablation['pairs']:
            sid=esc(pair['session_id'])
            ab_images+=f'<p>{sid}：同源五帧。上：无包裹证据；下：加入Florence＋KLT。</p><img src="keyframes/{sid}/five_model_frames.jpg"><img src="with_parcels/keyframes/{sid}/five_model_frames.jpg"><p><a href="with_parcels/interaction_review.mp4">查看加入包裹信息的视频</a></p>'
    table_rows=''
    for d in details:
        r=d['review'];op=r.get('opinion') or {}
        decision={True:'有取件',False:'未见该时段取件',None:'无法判断'}[op.get('pickup_detected')]
        table_rows+=f'<tr><td>当前主流程</td><td>{decision}</td><td>{esc(op.get("pickup_score"))}</td><td>算法{d["session"]["shelf_level"]}；模型{esc(op.get("model_shelf_level",op.get("shelf_level")))}；最终{esc(op.get("shelf_level"))}</td><td>{esc(r.get("elapsed_s"))}秒</td></tr>'
        if ablation:
            p=next((p for p in ablation['pairs'] if p['session_id']==d['session']['id']),None)
            if p:
                br=p['with_parcels'];b=br.get('opinion') or {};bd={True:'有取件',False:'未见该时段取件',None:'无法判断'}[b.get('pickup_detected')]
                table_rows+=f'<tr><td>有Florence/KLT</td><td>{bd}</td><td>{esc(b.get("pickup_score"))}</td><td>算法{d["session"]["shelf_level"]}；模型{esc(b.get("model_shelf_level",b.get("shelf_level")))}；最终{esc(b.get("shelf_level"))}</td><td>{esc(br.get("elapsed_s"))}秒</td></tr>'
    experiment_s=summary.get('experiment_plus_control_retest_s',summary.get('experiment_elapsed_s'))
    conclusions=f'<section><h2>先看本次结论</h2><p>自动会话 {len(sessions)} 段；主流程 {summary.get("primary_pipeline_elapsed_s")} 秒，含视觉、一次复核、导出和校验；对照与验证累计 {experiment_s} 秒。离线再导出另列，不调用API。</p><table><tr><th>输入方案</th><th>取件意见</th><th>模型自评分</th><th>层号来源与冲突</th><th>一次请求</th></tr>{table_rows}</table><p>None表示未知。取件意见和模型评分不是准确率；原始层号意见与最终冲突结果分别保存。</p>'
    if summary.get('invalidated_control_api_calls'):
        conclusions+=f'<p>原{summary["invalidated_control_api_calls"]}次负例含答案标签，已作废且去标签重测，不计入准确性。实际API共{summary["api_calls_total"]}次，有效测试{summary.get("valid_test_api_calls")}次，业务主会话仅1次。</p>'
    if evaluation:
        conclusions+=f'<p>参考核查：{evaluation["correct_decidable_cases"]}/{len(evaluation["review_cases"])} 段得到正确且明确的取件结论，另有 {evaluation["unknown_or_failed_cases"]} 段未知。样本是同一视频和人工选取的负例，不能声称通用准确率。</p>'
        reference_levels=sorted({x.get('reference_shelf_level') for x in evaluation['boundary_matches'] if x.get('reference_shelf_level') is not None})
        conclusions+=f'<p>原片与用户人工标定复核层号：{esc(reference_levels)}。</p>'
        correction=evaluation.get('reference_layer_adjudication')
        if correction:
            correction_path='reference_adjudicated.json' if (output/'reference_adjudicated.json').exists() else 'reference_snapshot.json'
            conclusions+=f'<p>参考标签历史上由{esc(correction["original_assistant_label"])}修正为{esc(correction["corrected_assistant_label"])}；该历史修正发生在首版模型输出后，未经独立团队复核。当前运行参考见快照。<a href="{correction_path}">修正依据</a> · <a href="reference_snapshot.json">运行时参考快照</a></p>'
    if ablation:
        conclusions+=f'<p>Florence＋KLT附加处理 {ablation["additional_parcel_visual_wall_s"]} 秒；本组只有单视频对照，不能据此确认稳定增益。</p>'
        if (output/'parcel_keyframe_audit.json').exists():
            audit=__import__('json').loads((output/'parcel_keyframe_audit.json').read_text(encoding='utf-8'))
            conclusions+=f'<p>助手目视抽查：中间{audit["middle_frames_checked"]}帧，{audit["covered_middle_frames"]}帧有效框覆盖实际操作物品；不是检测AP。<a href="parcel_keyframe_audit.json">查看核查</a></p>'
            if 'frames_with_visible_manipulated_object_checked' in audit:
                conclusions+=f'<p>其中{audit["frames_with_visible_manipulated_object_checked"]}帧可见实际被操作物品，有效框覆盖{audit["covered_visible_manipulated_frames"]}帧；起始帧尚未持有，不计入这项分母。</p>'
            if (output/'parcel_auxiliary_example'/'f000244_annotated.jpg').exists():
                conclusions+='<p>局部辅助作用示例（未输入本次五帧复核）：8.133秒K0013覆盖绿色盒子，8.333秒仍在附近；不证明来源层或取件。</p><a href="parcel_auxiliary_example/f000244_annotated.jpg"><img style="max-height:620px" src="parcel_auxiliary_example/f000244_annotated.jpg"></a>'
    conclusions+='</section>'
    document=f'''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>货架交互五帧测试</title><style>body{{font:16px/1.65 system-ui,"Microsoft YaHei",sans-serif;max-width:1250px;margin:auto;padding:24px;background:#f2f5fa;color:#182335}}section{{background:white;padding:18px;margin:18px 0;border-radius:12px}}.frames{{display:grid;grid-template-columns:repeat(5,1fr);gap:8px}}figure{{margin:0}}img,video{{max-width:100%;height:auto}}video{{max-height:740px}}pre{{white-space:pre-wrap;overflow-wrap:anywhere;font-size:13px}}a{{color:#2458a5}}@media(max-width:700px){{.frames{{grid-template-columns:repeat(2,1fr)}}}}</style><h1>货架交互 → 五帧 → 一次多模态复核</h1><p>一次交互＝同一人对同一层的连续操作会话。查看、放回、再操作可合并；仅路过不启动。不是每次拿起/放下各一条。</p><p>本例仅单视频探索测试，评分未经校准。真实参考由助手观察原片制作，尚未经团队独立复核，不能报告跨场景准确率。</p><section><h2>直接看视频</h2><video controls src="interaction_review.mp4"></video><p><a href="result.json">完整结果JSON</a> · <a href="events.json">六字段事件</a> · <a href="summary.json">阶段耗时</a> · <a href="ablation.json">Florence＋KLT对照</a> · <a href="evaluation.json">参考对照</a></p></section>{''.join(sections)}<section><h2>时间与参数</h2><pre>{esc(__import__('json').dumps(summary,ensure_ascii=False,indent=2))}</pre></section><section><h2>有无包裹信息对照</h2>{ab_images}<pre>{esc(__import__('json').dumps(ablation,ensure_ascii=False,indent=2))}</pre></section><section><h2>有限样本参考对照</h2><pre>{esc(__import__('json').dumps(evaluation,ensure_ascii=False,indent=2))}</pre></section></html>'''
    document=document.replace('<section><h2>直接看视频</h2>',conclusions+'<section><h2>直接看视频</h2>',1)
    document=document.replace('一次交互＝同一人对同一层的连续操作会话。查看、放回、再操作可合并；仅路过不启动。不是每次拿起/放下各一条。',
        '一次交互候选＝同一人对货架的连续操作会话。查看、放回、再操作可合并；仅路过不确认交互。层号由首次操作位置与多帧复核确定，不按腕点高度指定；跨层分段尚未验证。')
    document=document.replace('<a href="result.json">完整结果JSON</a>',
                              '<a href="interaction_summary.json">精简JSON</a> · <a href="result.json">完整结果JSON</a>',1)
    if (output/'calibration_reference.png').exists():
        document=document.replace('<section><h2>有限样本参考对照</h2>',
            '<section><h2>用户原始人工标定对照</h2><img src="calibration_reference.png"><p>原位物品在第3层；腕点可能投影到下方第2层。</p></section><section><h2>有限样本参考对照</h2>',1)
    if (output/'shelf_reference.png').exists():
        document=document.replace('<section><h2>直接看视频</h2>',
            '<section><h2>导入JSON中的货架参考图</h2><img src="shelf_reference.png"><p>每个视频帧通过配准映射货架区域；配准失败时不使用该帧区域。请核查视频中的区域是否贴合真实货架。</p></section><section><h2>直接看视频</h2>',1)
    (output/'comparison.html').write_text(document,encoding='utf-8')
