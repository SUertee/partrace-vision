"""Human-readable offline result, with source/overlay frames and track coverage."""
import html
import json
from pathlib import Path

import cv2


def esc(value):
    return html.escape(str(value), quote=True)


def coverage_svg(rows):
    ids = sorted({p['id'] for r in rows for p in r['parcels']})
    duration = max(rows[-1]['time_ms'], 1)
    width, label, span, pitch = 1000, 75, 900, 24
    height = 45 + pitch * len(ids)
    parts = [f'<svg role="img" aria-label="包裹轨迹可用时间" viewBox="0 0 {width} {height}">']
    for i in range(6):
        x = label + span * i / 5
        parts += [f'<line x1="{x}" y1="25" x2="{x}" y2="{height}" stroke="#ddd"/>',
                  f'<text x="{x}" y="16" font-size="12">{duration*i/5000:.1f}s</text>']
    # Each source observation is a segment. Gray does not mean a parcel vanished.
    for i, pid in enumerate(ids):
        y = 36 + i * pitch
        parts.append(f'<text x="4" y="{y+4}" font-size="13">{esc(pid)}</text>')
        for n, row in enumerate(rows):
            p = next((p for p in row['parcels'] if p['id'] == pid), None)
            if p is None:
                continue
            x1 = label + row['time_ms'] / duration * span
            end = rows[min(n+1, len(rows)-1)]['time_ms']
            x2 = max(x1 + 1, label + end / duration * span)
            color = '#22a466' if p.get('corrected_now') else ('#e9ac16' if p['valid'] else '#a1a1aa')
            parts.append(f'<path d="M{x1:.1f},{y} H{x2:.1f}" stroke="{color}" stroke-width="7"/>')
    parts.append('</svg>')
    return ''.join(parts)


def build_overview(output, source, rows, candidates, events, summary):
    from ..interaction.pipeline import annotate
    folder = output / 'gallery'
    folder.mkdir(exist_ok=True)
    times = (0, 2, 4, 6, 8, 10, 12, 16, 18)
    selected = sorted({min(range(len(rows)), key=lambda i: abs(rows[i]['time_ms']-sec*1000))
                       for sec in times if sec*1000 <= rows[-1]['time_ms']})
    cap = cv2.VideoCapture(str(source))
    cards = []
    try:
        for index in selected:
            cap.set(cv2.CAP_PROP_POS_FRAMES, index)
            ok, raw = cap.read()
            if not ok:
                raise RuntimeError('Cannot decode gallery source frame')
            row = rows[index]
            base = row['frame_id']
            for name, image in (('raw', raw), ('overlay', annotate(raw, row))):
                ok, encoded = cv2.imencode('.jpg', image, [cv2.IMWRITE_JPEG_QUALITY, 92])
                if not ok:
                    raise RuntimeError('Cannot save gallery frame')
                (folder / f'{base}_{name}.jpg').write_bytes(encoded.tobytes())
            tracks = '; '.join(p['id']+' '+p['source']+(' 有效' if p['valid'] else ' 待稳定/失效')+
                (' 靠近 '+','.join(p.get('near_person_ids', [])) if p.get('near_person_ids') else '')
                for p in row['parcels'] if p['box'] is not None)
            cards.append(f'<article><h3>{row["time_ms"]/1000:.2f}s · {base}</h3>'
                f'<div class="pair"><figure><a href="gallery/{base}_raw.jpg"><img src="gallery/{base}_raw.jpg"></a><figcaption>源画面</figcaption></figure>'
                f'<figure><a href="gallery/{base}_overlay.jpg"><img src="gallery/{base}_overlay.jpg"></a><figcaption>人物、手腕、包裹及货架区域</figcaption></figure></div>'
                f'<p class="small">{esc(tracks or "本帧无可显示包裹框")}</p></article>')
    finally:
        cap.release()
    m = summary['florence_klt']
    wall = m['observation_wall_s']
    fps = len(rows) / max(wall, .001)
    ratio = summary['duration_ms'] / 1000 / max(wall, .001)
    names = {'take':'拿起','put':'放下','touch':'触碰','carrying':'已持有',
             'no_interaction':'无交互','uncertain':'无法判断'}
    event_rows = ''.join(f'<tr><td>{esc(e["id"])}</td><td>{esc(e["person_id"] or "人物未确定")}</td>'
        f'<td>{e["start_ms"]/1000:.2f}–{e["end_ms"]/1000:.2f}s</td><td>{esc(names[e["action"]])}</td>'
        f'<td>{e["shelf_level"] if e["shelf_level"] is not None else "未知/不适用"}</td></tr>' for e in events)
    if not events:
        event_rows = '<tr><td colspan="5">未生成动作意见。未启用或尚未完成复核，不等于视频没有交互。</td></tr>'
    candidate_rows = ''.join(f'<tr><td>{esc(c["id"])}</td><td>{esc(c["person_id"])}</td><td>{esc(c["target_id"])}</td>'
        f'<td>{"手物候选" if c["target_type"]=="parcel" else "货架区域候选"}</td>'
        f'<td>{c["active_start_ms"]/1000:.2f}–{c["active_end_ms"]/1000:.2f}s</td>'
        f'<td>{esc(c.get("suppressed_reason") or "保留")}</td></tr>' for c in candidates)
    review_note = f'复核成功 {summary["reviews_completed"]}，失败 {summary["reviews_failed"]}，未完成 {summary["requests_not_reviewed"]} 个请求。所有意见均未人工确认。'
    reuse = summary.get('observations_reused_from')
    localization = f'../{esc(reuse)}/localization/calls.jsonl' if reuse else 'localization/calls.jsonl'
    inspection = ''
    note_path = output / 'visual_inspection.json'
    if note_path.exists():
        note = json.loads(note_path.read_text(encoding='utf-8'))
        if note['source_sha256'] != summary['source_sha256']:
            raise ValueError('Visual inspection belongs to another source')
        inspection = '<section><h2>本次图像抽查</h2><ul>'+''.join('<li>'+esc(s)+'</li>' for s in note['observations'])+'</ul><p>仅抽查少量帧，不是完整真值评估，也没有确认所有动作。</p></section>'
    document = f'''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Florence 定位与 KLT 跟踪结果</title><style>
body{{font:16px/1.65 system-ui,"Microsoft YaHei",sans-serif;max-width:1150px;margin:auto;padding:24px;background:#f4f6f9;color:#182235}}
h1{{font-size:27px}} .cards{{display:grid;grid-template-columns:repeat(auto-fit,minmax(190px,1fr));gap:12px}}
.metric,article,section{{background:white;border-radius:12px;padding:18px;margin:16px 0}} .metric b{{display:block;font-size:25px}}
.small{{font-size:13px;color:#526075;overflow-wrap:anywhere}} .note{{border-left:4px solid #e9ac16;padding:12px;background:#fff9e8}}
.pair{{display:grid;grid-template-columns:1fr 1fr;gap:12px}} figure{{margin:0;text-align:center}} img{{max-width:100%;max-height:600px}}
video{{width:100%;max-height:750px;background:#111}} table{{border-collapse:collapse;width:100%;font-size:14px}} td,th{{padding:8px;border-bottom:1px solid #ddd;text-align:left}}
a{{color:#185ab3}} svg{{width:100%}} @media(max-width:600px){{body{{padding:12px}}.pair{{gap:4px}}section{{overflow:auto}}}}
</style><h1>Florence 定位 → KLT 跟踪 → 候选 → 多帧复核</h1>
<p>运行：{esc(output.name)}。基础版本：72d0913；模型：Florence-2-{esc(m['variant'])}；本次为单视频离线实验。</p>
<div class="note">Florence 只给出框和标签。KLT 框的移动、手腕靠近和货架像素变化均不能单独确认拿取。无人工真值，尚不能报告识别准确率。</div>
{inspection}
<div class="cards"><div class="metric">视频时长<b>{summary['duration_ms']/1000:.2f}s</b>{len(rows)} 个源帧</div>
<div class="metric">观测处理耗时<b>{wall:.1f}s</b>含姿态、配准、定位、跟踪；不含模型加载、复核和导出</div>
<div class="metric">观测吞吐<b>{fps:.2f} 帧/s</b>源视频 {summary['fps']:.0f} 帧/s；速度比 {ratio:.3f}×</div>
<div class="metric">候选数<b>{len(candidates)}</b>定位调用 {m['localization_calls']}；跟踪失效记录 {m['klt_losses']}</div></div>
<section><h2>直接看效果</h2><p>绿色框：本帧 Florence 定位或校正。黄色框：KLT。灰色框：待稳定、无效或人物有歧义。NEAR 只表示靠近。</p>
<video controls preload="metadata" src="joint_review.mp4"></video><p><a href="joint_review.mp4">打开联合视频</a> · <a href="report.md">复核依据与原始证据</a> · <a href="{localization}">定位输入、原始回答与坐标</a></p>
<p class="small">若浏览器不支持本机 MP4 编码，请用本地播放器打开；下方源画面与标注图可直接对照。</p></section>
<section><h2>包裹轨迹的可用时间</h2><p>黄色仅表示 KLT 当前通过质量检查。轨迹 ID 是短时轨迹，不是订单身份。重新出现可能产生新 ID。</p>{coverage_svg(rows)}</section>
<section><h2>动作意见</h2><p>{esc(review_note)}</p><table><tr><th>ID</th><th>人物</th><th>时间</th><th>做了什么</th><th>货架层</th></tr>{event_rows}</table></section>
<section><h2>交互候选</h2><table><tr><th>ID</th><th>人物</th><th>目标</th><th>类型</th><th>时间</th><th>处理</th></tr>{candidate_rows}</table></section>
<h2>逐时刻对照</h2><p>点击图片可查看原尺寸。图库用于观察框的位置；原始复核证据及哈希另保存在 evidence/。</p>{''.join(cards)}
<p><a href="summary.json">完整指标</a> · <a href="events.json">六字段事件</a> · <a href="observations.jsonl">每帧观测</a></p></html>'''
    (output / 'comparison.html').write_text(document, encoding='utf-8')


def render_saved(root, name):
    if not name.replace('_','').replace('-','').isalnum():
        raise ValueError('Unsafe run name')
    output = root / 'outputs' / name
    read = lambda name: json.loads((output / name).read_text(encoding='utf-8'))
    summary = read('summary.json')
    rows = [json.loads(line) for line in (output / 'observations.jsonl').read_text(encoding='utf-8').splitlines()]
    build_overview(output, Path(summary['source_path']), rows, read('candidates.json'), read('events.json'), summary)
