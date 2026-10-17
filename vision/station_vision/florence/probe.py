"""Independent Florence-2 frame probe; never confirms actions or changes trackers."""
import argparse
from collections import defaultdict
from datetime import datetime
import html
import importlib.metadata
import json
import os
from pathlib import Path
import platform
import sys
import time
from unittest.mock import patch

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont

from .assets import digest
from ..video import VideoSource


TASKS = {
    'od': ('<OD>', ''),
    'dense': ('<DENSE_REGION_CAPTION>', ''),
    'boxes': ('<CAPTION_TO_PHRASE_GROUNDING>', 'cardboard boxes and parcels'),
    'held': ('<CAPTION_TO_PHRASE_GROUNDING>', 'the box held in the person\'s hands'),
    'open_boxes': ('<OPEN_VOCABULARY_DETECTION>', 'box'),
    'appearance': ('<CAPTION_TO_PHRASE_GROUNDING>', 'a small green box'),
}


def save_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')


def normalized_boxes(answer, task, width, height):
    """Keep only parsed, finite, ordered source-pixel rectangles; no fake score."""
    payload = answer.get(task)
    if not isinstance(payload, dict):
        raise ValueError('Missing task dictionary in Florence output')
    boxes = payload.get('bboxes', [])
    labels = payload.get('labels', payload.get('bboxes_labels', []))
    if len(boxes) != len(labels):
        raise ValueError('Box and label counts differ')
    kept, rejected = [], []
    for i, (box, label) in enumerate(zip(boxes, labels)):
        values = np.asarray(box, dtype=float)
        if values.shape != (4,) or not np.isfinite(values).all():
            rejected.append({'index': i, 'reason': 'invalid_coordinates'})
            continue
        if not isinstance(label, str) or not label.strip():
            rejected.append({'index': i, 'reason': 'missing_label'})
            continue
        values[[0, 2]] = np.clip(values[[0, 2]], 0, width)
        values[[1, 3]] = np.clip(values[[1, 3]], 0, height)
        if values[2] <= values[0] or values[3] <= values[1]:
            rejected.append({'index': i, 'reason': 'empty_or_reversed_box'})
            continue
        kept.append({'box': values.round(2).tolist(), 'label': label.strip(),
                     'source': 'florence2_generated', 'score': None})
    return kept, rejected


def font(size=20):
    path = Path('C:/Windows/Fonts/msyh.ttc')
    return ImageFont.truetype(str(path), size) if path.exists() else ImageFont.load_default()


def draw_boxes(raw, boxes, title):
    image = Image.fromarray(cv2.cvtColor(raw, cv2.COLOR_BGR2RGB))
    draw = ImageDraw.Draw(image)
    text_font = font(17)
    colors = ('#4ade80', '#facc15', '#60a5fa', '#f472b6', '#fb923c')
    for i, item in enumerate(boxes):
        x1, y1, x2, y2 = item['box']
        color = colors[i % len(colors)]
        draw.rectangle((x1, y1, x2, y2), outline=color, width=3)
        # Full generated descriptions remain in JSON/report; image labels stay short.
        label = f'{i + 1}: {item["label"]}'[:85]
        bounds = draw.textbbox((0, 0), label, font=text_font)
        tw, th = bounds[2] - bounds[0], bounds[3] - bounds[1]
        x = max(0, min(x1, image.width - tw - 5))
        y = max(0, y1 - th - 10)
        draw.rectangle((x, y, x + tw + 5, y + th + 9), fill='#111827')
        draw.text((x + 2, y), label, font=text_font, fill=color)
    image.thumbnail((450, 800))
    canvas = Image.new('RGB', (image.width, image.height + 80), '#111827')
    canvas.paste(image, (0, 80))
    draw = ImageDraw.Draw(canvas)
    draw.text((10, 8), title, font=font(19), fill='white')
    draw.text((10, 39), '框为模型生成；标签未核验；无检测置信度', font=font(16), fill='#cbd5e1')
    return canvas


class FlorenceProbe:
    def __init__(self, root, device, cpu_threads=4, variant='large-ft'):
        os.environ['HF_MODULES_CACHE'] = str(root / '.runtime/hf_modules')
        os.environ['HF_HUB_OFFLINE'] = '1'
        os.environ['TRANSFORMERS_OFFLINE'] = '1'
        os.environ['HF_HUB_DISABLE_TELEMETRY'] = '1'
        import torch
        from transformers import AutoModelForCausalLM, AutoProcessor, dynamic_module_utils
        self.torch = torch
        if device == 'cuda' and not torch.cuda.is_available():
            raise RuntimeError('CUDA requested but unavailable; no silent fallback')
        self.device = device
        self.dtype = torch.float16 if device == 'cuda' else torch.float32
        torch.set_num_threads(cpu_threads)
        if variant not in ('large-ft', 'base-ft'):
            raise ValueError('Use an explicit supported Microsoft variant')
        folder = root / 'models/florence2' / variant
        if not (folder / 'manifest.json').exists():
            raise RuntimeError(f'Prepare Florence-2-{variant} first: .venv-track/Scripts/python.exe -m tools.prepare_florence2 --variant {variant}')
        manifest = json.loads((folder / 'manifest.json').read_text(encoding='utf-8'))
        if manifest.get('status') != 'ready':
            raise ValueError('Prepare the pinned Microsoft model first')
        for name, record in manifest['files'].items():
            if digest(folder / name) != record['sha256']:
                raise ValueError('Florence model/source hash mismatch: ' + name)
        self.manifest = manifest
        original_imports = dynamic_module_utils.get_imports

        def eager_imports(filename):
            imports = original_imports(filename)
            # 4.44's static import checker treats optional guarded imports as
            # required. The verified Microsoft code guards flash_attn, and
            # this adapter explicitly selects eager attention on both devices.
            if Path(filename).name == 'modeling_florence2.py':
                return [name for name in imports if name != 'flash_attn']
            return imports

        started = time.perf_counter()
        with patch('transformers.dynamic_module_utils.get_imports', eager_imports):
            self.processor = AutoProcessor.from_pretrained(str(folder), trust_remote_code=True,
                                                            local_files_only=True)
            self.model = AutoModelForCausalLM.from_pretrained(str(folder), trust_remote_code=True,
                local_files_only=True, use_safetensors=True, torch_dtype=self.dtype,
                attn_implementation='eager').to(device).eval()
        self.sync()
        self.load_s = time.perf_counter() - started

    def sync(self):
        if self.device == 'cuda':
            self.torch.cuda.synchronize()

    def run(self, raw, name, beams=3, max_tokens=1024, text_input=None):
        task, text = TASKS[name]
        if text_input is not None:
            if not text:
                raise ValueError('This Florence task does not accept text input')
            text = text_input
        image = Image.fromarray(cv2.cvtColor(raw, cv2.COLOR_BGR2RGB))
        self.sync()
        started = time.perf_counter()
        inputs = self.processor(text=task + text, images=image, return_tensors='pt')
        tensors = {key: value.to(device=self.device,
                    dtype=self.dtype if value.is_floating_point() else value.dtype)
                   for key, value in inputs.items()}
        with self.torch.inference_mode():
            output = self.model.generate(input_ids=tensors['input_ids'],
                pixel_values=tensors['pixel_values'], do_sample=False,
                max_new_tokens=max_tokens, num_beams=beams)
        self.sync()
        generated = self.processor.batch_decode(output, skip_special_tokens=False)[0]
        answer = self.processor.post_process_generation(generated, task=task,
                                                        image_size=image.size)
        boxes, rejected = normalized_boxes(answer, task, image.width, image.height)
        elapsed = time.perf_counter() - started
        token_count = int(output.shape[-1])
        exhausted = token_count >= max_tokens + 1
        return {'task_name': name, 'task_token': task, 'text_input': text,
                'device': self.device, 'dtype': str(self.dtype),
                'pixel_tensor_shape': list(tensors['pixel_values'].shape),
                'boxes': boxes, 'rejected': rejected, 'raw_text': generated,
                'parsed_answer': answer, 'elapsed_s': round(elapsed, 4),
                'generated_sequence_tokens': token_count,
                'status': 'token_limit_reached' if exhausted else 'completed',
                'num_beams': beams, 'max_new_tokens': max_tokens,
                'object_confidence_available': False}


def render(output, results, metadata):
    groups = defaultdict(list)
    for row in results:
        groups[row['frame_index']].append(row)
    sections, lines = [], ['# Florence-2 包裹定位独立测试', '',
        f'设备：{metadata["device"]}；模型：{metadata["model_manifest"]["repository"]}。',
        '模型生成矩形框和标签，不输出可直接对比 YOLO 的目标置信度。未确认人物归属、拿取或货架层。',
        '这是少量源帧试验，未计算准确率，也未证明视频实时性。下载、加载和预热不计入单次调用时间。', '']
    review_path = output / 'manual_visual_review.json'
    if review_path.exists():
        review = json.loads(review_path.read_text(encoding='utf-8'))
        if review.get('source_sha256') != metadata['source_sha256']:
            raise ValueError('Visual review belongs to a different video')
        sections.append('<h2>本轮实际观察</h2><ul>')
        lines.extend(['## 本轮实际观察', ''])
        for finding in review.get('findings', []):
            sections.append('<li>' + html.escape(finding) + '</li>')
            lines.append('- ' + finding)
        sections.append('</ul><p>这是助手对少量原帧的定性检查，不是人工标注测试集。</p>')
        for picture in review.get('comparison_images', []):
            path = picture['path']
            sections.append('<p>' + html.escape(picture['caption']) + '</p><a href="' +
                html.escape(path, quote=True) + '"><img style="max-width:900px;width:100%" src="' +
                html.escape(path, quote=True) + '"></a>')
            lines.extend(['', picture['caption'], f'![同源框选对照]({path})'])
    for index, rows in groups.items():
        sections.append(f'<h2>源时间 {rows[0]["time_ms"] / 1000:.3f}s · 帧 {index}</h2><div class="cards">')
        lines.extend([f'## 源帧 {index}', ''])
        for row in rows:
            title = f'{row["task_name"]} · {row["elapsed_s"]:.3f}s · {len(row["boxes"])} 个框'
            descriptions = '<br>'.join(html.escape(item['label']) for item in row['boxes']) or '没有有效框'
            path = row['visualization']
            sections.append('<article><h3>' + html.escape(title) + '</h3><p>' +
                html.escape(row['task_token'] + row['text_input']) + '</p><a href="' + path +
                '"><img src="' + path + '"></a><p>' + descriptions + '</p></article>')
            lines.extend([f'- {title}；提示：`{row["task_token"]}{row["text_input"]}`',
                          f'  ![预测框与标签]({path})'])
        sections.append('</div>')
    (output / 'report.md').write_text('\n'.join(lines) + '\n', encoding='utf-8')
    page = '<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>Florence-2 包裹定位</title>'
    page += '<style>body{font:16px sans-serif;background:#111827;color:#e5e7eb;margin:25px}a{color:#7dd3fc}.cards{display:flex;flex-wrap:wrap;gap:18px}article{width:360px;padding:12px;background:#1f2937}img{width:100%}p{line-height:1.5;overflow-wrap:anywhere}</style>'
    page += '<h1>Florence-2：源帧、矩形框与标签</h1><p>少量源帧独立验证；没有接入跟踪或动作判定。框是模型生成结果，标签可能错误。没有目标检测置信度或人工真值准确率。</p>'
    page += '<p>设备：' + html.escape(metadata['device']) + '；模型：' + html.escape(metadata['model_manifest']['repository']) + '。显示时间包括预处理、生成与解析，CUDA 已同步；模型下载、加载和预热另计。</p>'
    (output / 'comparison.html').write_text(page + ''.join(sections) + '</html>', encoding='utf-8')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--video', default='test_video_new/0fc72122c2a6a082e2d4f3fdcdd2a8f1.mp4')
    parser.add_argument('--frames', default='120,240,360')
    parser.add_argument('--tasks', default='od,dense,boxes,held,open_boxes')
    parser.add_argument('--device', choices=['cpu', 'cuda'], default='cuda')
    parser.add_argument('--variant', choices=['large-ft', 'base-ft'], default='large-ft')
    parser.add_argument('--cpu-threads', type=int, default=4)
    parser.add_argument('--beams', type=int, default=3)
    parser.add_argument('--max-tokens', type=int, default=1024)
    parser.add_argument('--run-name', default='florence_' + datetime.now().strftime('%Y%m%d_%H%M%S'))
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    if Path(sys.prefix).resolve() != (root / '.venv-track').resolve():
        raise RuntimeError('Use .venv-track')
    if not args.run_name.replace('_', '').replace('-', '').isalnum():
        raise ValueError('Unsafe run name')
    indices = [int(item) for item in args.frames.split(',')]
    names = args.tasks.split(',')
    if not indices or min(indices) < 0 or len(set(indices)) != len(indices):
        raise ValueError('Use unique nonnegative source frame indices')
    if not names or any(name not in TASKS for name in names) or len(names) != len(set(names)):
        raise ValueError('Choose unique supported tasks: ' + ','.join(TASKS))
    if args.cpu_threads < 1 or args.beams < 1 or not 8 <= args.max_tokens <= 4096:
        raise ValueError('Invalid thread/beam/token settings')
    source = (root / args.video).resolve()
    src = VideoSource(source, 120)
    frames = {}
    for index, pts, basis, raw in src:
        if index in indices:
            frames[index] = (pts, basis, raw)
        if index >= max(indices):
            break
    if set(frames) != set(indices):
        raise ValueError('Some requested source frames are unavailable')
    output = root / 'outputs' / args.run_name
    output.mkdir(exist_ok=False)
    (output / 'raw').mkdir()
    (output / 'visuals').mkdir()
    metadata = {'status': 'loading', 'source': str(source), 'source_sha256': digest(source),
        'device': args.device, 'cpu_threads': args.cpu_threads, 'nominal_source_fps': src.fps,
        'training_performed': False, 'main_flow_modified': False, 'accuracy_evaluated': False,
        'action_or_person_association_verified': False, 'results': []}
    save_json(output / 'summary.json', metadata)
    try:
        model = FlorenceProbe(root, args.device, args.cpu_threads, args.variant)
        metadata.update(model_manifest=model.manifest, model_load_s=round(model.load_s, 3),
            packages={name: importlib.metadata.version(name) for name in
                      ('torch', 'transformers', 'timm', 'einops', 'huggingface-hub', 'safetensors')})
        metadata['hardware'] = model.torch.cuda.get_device_name() if args.device == 'cuda' else platform.processor()
        tick = time.perf_counter()
        model.run(frames[indices[0]][2], names[0], args.beams, args.max_tokens)
        metadata['warmup_s'] = round(time.perf_counter() - tick, 3)
        for index in indices:
            pts, basis, raw = frames[index]
            ok, encoded = cv2.imencode('.jpg', raw, [cv2.IMWRITE_JPEG_QUALITY, 92])
            if not ok:
                raise RuntimeError('Cannot encode original evidence frame')
            raw_name = f'raw/f{index:06d}.jpg'
            (output / raw_name).write_bytes(encoded.tobytes())
            for name in names:
                result = model.run(raw, name, args.beams, args.max_tokens)
                result.update(frame_index=index, frame_id=f'f{index:06d}', time_ms=round(pts, 3),
                    timestamp_basis=basis, raw_path=raw_name, raw_sha256=digest(output / raw_name))
                visual_name = f'visuals/f{index:06d}_{name}.jpg'
                draw_boxes(raw, result['boxes'], f'{pts/1000:.2f}s | {name} | {result["elapsed_s"]:.3f}s').save(output / visual_name, quality=94)
                result['visualization'] = visual_name
                metadata['results'].append(result)
                save_json(output / 'summary.json', metadata)
                render(output, metadata['results'], metadata)
                print(json.dumps({key: result[key] for key in
                    ('frame_index', 'task_name', 'status', 'elapsed_s', 'boxes')}, ensure_ascii=False), flush=True)
        metadata['status'] = 'completed'
        metadata['all_tasks_complete'] = all(row['status'] == 'completed' for row in metadata['results'])
        save_json(output / 'summary.json', metadata)
        print('OUTPUT ' + str(output), flush=True)
    except BaseException as error:
        metadata.update(status='failed', error=str(error))
        save_json(output / 'summary.json', metadata)
        raise


if __name__ == '__main__':
    main()
