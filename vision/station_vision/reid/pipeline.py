"""Screenshot enrollment -> BoT-SORT observations -> two-state gallery association.

Standalone video experiment. No parcel changes, Qwen requests or event-schema changes.
"""
from pathlib import Path
from collections import Counter
import argparse
import hashlib
import json
import time
import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont
from ..models import PoseTracker
from ..video import VideoSource
from .encoder import OSNetEncoder, crop_person, crop_quality, read_image
from .matching import GalleryMatcher

IMAGE_SUFFIXES = {'.jpg', '.jpeg', '.png', '.bmp', '.webp'}


def save_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')


def file_hash(path):
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(block)
    return result.hexdigest()


def enrollment_folders(folder):
    if not folder.is_dir():
        raise FileNotFoundError(f'Enrollment folder not found: {folder}')
    groups = []
    for sub in sorted(folder.iterdir()):
        if sub.name.startswith('G') and sub.name[1:].isdigit():continue  # Automatic profile crops are already enrolled; never renumber them.
        if sub.is_dir():
            images = sorted(p for p in sub.iterdir() if p.suffix.lower() in IMAGE_SUFFIXES and p.is_file())
            if images:
                groups.append((sub, images))
    if not groups:
        raise ValueError(f'No enrollment images. Put one person per subfolder in {folder}; see its README.md')
    return groups


def build_gallery(root, config, output, tracker, encoder):
    folder = root / config.get('enrollment_dir', 'data/person_enrollment')
    groups = enrollment_folders(folder)
    minimum = int(config.get('min_templates', 3))
    maximum = int(config.get('max_templates', 8))
    if minimum < 1 or maximum < minimum:
        raise ValueError('Invalid enrollment template limits')
    profiles = {}
    details = []
    rejected = []
    seen_images = {}
    registry_path = folder / 'profile_registry.json'
    registry = json.loads(registry_path.read_text(encoding='utf-8')) if registry_path.exists() else {}
    if (not isinstance(registry, dict) or any(not isinstance(v, str) or not v.startswith('G') or
            not v[1:].isdigit() for v in registry.values()) or len(set(registry.values())) != len(registry)):
        raise ValueError('Invalid enrollment profile_registry.json')
    next_profile = max((int(v[1:]) for v in registry.values()), default=0) + 1
    for group, paths in groups:
        good = []
        for path in paths:
            image = read_image(path)
            record = dict(path=str(path.relative_to(root)) if path.is_relative_to(root) else str(path),
                          sha256=file_hash(path), folder=group.name)
            if image is None:
                rejected.append(dict(**record, reason='image_decode_failed'))
                continue
            if record['sha256'] in seen_images:
                rejected.append(dict(**record, reason='duplicate_image', duplicate_of=seen_images[record['sha256']]))
                continue
            seen_images[record['sha256']] = str(path)
            result = tracker.model.predict(image, conf=.25, imgsz=tracker.imgsz,
                                           device=tracker.device, verbose=False)[0]
            detections = result.boxes.xyxy.cpu().numpy() if result.boxes is not None else []
            if len(detections) != 1:
                rejected.append(dict(**record, reason='expected_exactly_one_person', detected_people=len(detections)))
                continue
            box = detections[0].tolist()
            crop = crop_person(image, box)
            ok, quality = crop_quality(crop, **config.get('quality', {}))
            if not ok:
                rejected.append(dict(**record, reason=quality['reason'], quality=quality))
                continue
            good.append((crop, dict(**record, bbox_xyxy=box, quality=quality)))
        # Keep a bounded set of clear pictures; folders establish sample grouping,
        # not a claim that the algorithm proved they are the same person.
        good.sort(key=lambda pair: pair[1]['quality']['blur'], reverse=True)
        good = good[:maximum]
        if len(good) < minimum:
            rejected.append(dict(folder=group.name, reason='insufficient_valid_distinct_images',
                                 valid=len(good), required=minimum))
            continue
        if group.name not in registry:
            registry[group.name] = f'G{next_profile:04d}'
            next_profile += 1
        pid = registry[group.name]
        vectors = encoder.encode([crop for crop, _ in good])
        profiles[pid] = vectors.tolist()
        sample_records = []
        for i, (crop, record) in enumerate(good, 1):
            crop_path = output / 'gallery' / pid / f'{i:02d}.jpg'
            crop_path.parent.mkdir(parents=True, exist_ok=True)
            Image.fromarray(cv2.cvtColor(crop, cv2.COLOR_BGR2RGB)).save(crop_path, quality=95)
            sample_records.append(dict(**record, crop=str(crop_path.relative_to(output))))
        details.append(dict(person_id=pid, folder=group.name, samples=sample_records))
    save_json(output / 'enrollment_report.json', dict(profiles=details, rejected=rejected))
    if not profiles:
        raise ValueError('No valid person profile; inspect enrollment_report.json for rejected images')
    save_json(registry_path, registry)
    save_json(output / 'gallery.json', dict(architecture='osnet_x0_5', weights_sha256=encoder.sha256,
                                           profiles=profiles, details=details,
                                           grouping='one user-supplied person folder per profile'))
    return profiles, details, rejected


def get_font(size):
    for name in ('C:/Windows/Fonts/msyh.ttc', 'C:/Windows/Fonts/simhei.ttf'):
        if Path(name).exists():
            return ImageFont.truetype(name, size)
    return ImageFont.load_default()


def render(frame, time_ms, people, states, gallery_details, output):
    h, w = frame.shape[:2]
    canvas = Image.new('RGB', (w + 380, h), (20, 23, 30))
    canvas.paste(Image.fromarray(cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)), (0, 0))
    draw = ImageDraw.Draw(canvas)
    font, small = get_font(22), get_font(18)
    draw.text((w + 14, 12), '人物档案关联先验', font=font, fill='white')
    draw.text((w + 14, 45), f'源时间 {time_ms / 1000:.2f}s', font=small, fill='white')
    draw.text((w + 14, 72), '仅人物关联，不分析拿取动作', font=small, fill='#bbbbbb')
    y = 110
    for person in people:
        public = states[person.track_id]
        label = public['person_id']
        matched = public['status'] == 'matched'
        color = '#44dd99' if matched else '#ffbb55'
        x1, y1, x2, y2 = person.bbox
        draw.rectangle((x1, y1, x2, y2), outline=color, width=3)
        text = f'{label} ' + ('已关联' if matched else '未匹配')
        text_y = max(0, y1 - 29)
        box = draw.textbbox((x1, text_y), text, font=font)
        draw.rectangle(box, fill=(10, 10, 10))
        draw.text((x1, text_y), text, font=font, fill=color)
        for a, b in ((5, 7), (7, 9), (6, 8), (8, 10)):
            if len(person.keypoints) > max(a, b):
                p, q = person.keypoints[a], person.keypoints[b]
                if min(p[2], q[2]) >= .35:
                    draw.line((*p[:2], *q[:2]), fill=color, width=2)
        if y < h - 60:
            draw.text((w + 14, y), text, font=font, fill=color)
            y += 35
    y += 20
    draw.text((w + 14, y), '注册档案代表图', font=small, fill='white')
    y += 30
    for detail in gallery_details:
        if y + 155 > h:
            break
        with Image.open(output / detail['samples'][0]['crop']) as picture:
            thumbnail = picture.convert('RGB')
            thumbnail.thumbnail((95, 145))
            canvas.paste(thumbnail, (w + 14, y))
        draw.text((w + 126, y), detail['person_id'], font=font, fill='white')
        draw.text((w + 126, y + 35), detail['folder'][:16], font=small, fill='#bbbbbb')
        y += 165
    return cv2.cvtColor(np.asarray(canvas), cv2.COLOR_RGB2BGR)


def run(root, config, output, device, max_seconds=0, realtime=False):
    # Fail before model loading/output creation if the user has not supplied screenshots.
    enrollment_folders(root / config.get('enrollment_dir', 'data/person_enrollment'))
    if output.exists():
        raise FileExistsError(f'Choose a new RunName; output already exists: {output}')
    source = (root / config['video']).resolve()
    if not source.is_file():
        raise FileNotFoundError(source)
    observation_fps = float(config.get('observation_fps', 10))
    reid_fps = float(config.get('reid_fps', 4))
    if not 0 < reid_fps <= observation_fps <= 120:
        raise ValueError('Require 0 < reid_fps <= observation_fps <= 120')
    output.mkdir(parents=True)
    save_json(output / 'config.json', config)
    started = time.perf_counter()
    encoder = OSNetEncoder(root, device=device, weights=config.get('reid_weights', 'models/reid/osnet_x0_5_msmt17.pth'))
    tracker = PoseTracker(root, dict(device=device, imgsz=int(config.get('imgsz', 640)),
                                   detection_conf=.15, tracker_type=config.get('tracker_type', 'botsort'),
                                   gmc_method='none'), observation_fps)
    profiles, gallery_details, rejected = build_gallery(root, config, output, tracker, encoder)
    # Enrollment used predict(), not nonconsecutive screenshot tracking.
    tracker.reset()
    matcher = GalleryMatcher(profiles, **config.get('matching', {}))
    video = VideoSource(source, sample_fps=observation_fps,
                        start_s=float(config.get('start_s', 0)),
                        end_s=(float(config.get('start_s', 0)) + max_seconds) if max_seconds > 0 else None)
    writer = None
    feature_times = {}
    counts = Counter()
    pose_times, encoder_times, frame_times = [], [], []
    snapshots = []
    pace_start = time.perf_counter()
    first_pts = None
    last_pts = None
    try:
        with (output / 'people.jsonl').open('w', encoding='utf-8') as public_stream, \
             (output / 'observations.jsonl').open('w', encoding='utf-8') as debug_stream:
            for index, pts, basis, frame in video:
                if first_pts is None:
                    first_pts = pts
                if realtime:
                    delay = (pts - first_pts) / 1000 - (time.perf_counter() - pace_start)
                    if delay > 0:
                        time.sleep(delay)
                tick = time.perf_counter()
                people = [p for p in tracker.process(frame, 'B') if p.track_id is not None]
                pose_times.append(time.perf_counter() - tick)
                features, candidates, crops = {}, [], []
                quality_records = {}
                for p in people:
                    if pts - feature_times.get(p.track_id, -1e12) + 1e-5 < 1000 / reid_fps:
                        continue
                    feature_times[p.track_id] = pts
                    crop = crop_person(frame, p.bbox)
                    ok, quality = crop_quality(crop, **config.get('quality', {}))
                    quality_records[p.track_id] = quality
                    if ok:
                        candidates.append(p.track_id)
                        crops.append(crop)
                feature_tick = time.perf_counter()
                encoded = encoder.encode(crops)
                encoder_times.append(time.perf_counter() - feature_tick)
                features.update(zip(candidates, encoded))
                states = matcher.update(pts, [dict(track_id=p.track_id, feature=features.get(p.track_id)) for p in people])
                public = dict(time_ms=round(pts, 3), people=[states[p.track_id] for p in people])
                public_stream.write(json.dumps(public, ensure_ascii=False) + '\n')
                debug_people = [dict(**p.to_dict(), **states[p.track_id],
                                     unknown_id=matcher.states[p.track_id].unknown_id,
                                     similarity=matcher.states[p.track_id].score,
                                     quality=quality_records.get(p.track_id),
                                     feature_observed=p.track_id in features) for p in people]
                debug_stream.write(json.dumps(dict(source_frame_index=index, source_pts_ms=pts,
                                                   timestamp_basis=basis, people=debug_people), ensure_ascii=False) + '\n')
                for value in states.values():
                    counts[value['status']] += 1
                annotated = render(frame, pts, people, states, gallery_details, output)
                if writer is None:
                    writer = cv2.VideoWriter(str(output / 'person_reid_review.mp4'),
                                             cv2.VideoWriter_fourcc(*'mp4v'), observation_fps,
                                             (annotated.shape[1], annotated.shape[0]))
                    if not writer.isOpened():
                        raise RuntimeError('Cannot create person_reid_review.mp4')
                writer.write(annotated)
                counts['observations'] += 1
                counts['feature_crops'] += len(crops)
                last_pts = pts
                frame_times.append(time.perf_counter() - tick)
                if len(snapshots) < 4 and (not snapshots or pts - snapshots[-1]['time_ms'] >= 1000):
                    name = f'preview_{len(snapshots):02d}.jpg'
                    Image.fromarray(cv2.cvtColor(annotated, cv2.COLOR_BGR2RGB)).save(output / name, quality=95)
                    snapshots.append(dict(time_ms=pts, image=name))
                if counts['observations'] % 25 == 0:
                    print(f'frames={counts["observations"]} source={pts / 1000:.1f}s states={dict(counts)}', flush=True)
    finally:
        if writer is not None:
            writer.release()
        video.cap.release()
    if not counts['observations']:
        raise ValueError('No frames in requested search interval')
    save_json(output / 'identity_links.json', matcher.transitions)
    save_json(output / 'tracks.json', [dict(track_id=k, unknown_id=s.unknown_id,
                                           final_person_id=matcher.public(k)['person_id']) for k, s in matcher.states.items()])
    def timing(values):
        return dict(mean_ms=round(float(np.mean(values)) * 1000, 2),
                    p95_ms=round(float(np.percentile(values, 95)) * 1000, 2)) if values else None
    summary = dict(status='completed', scope='reference_images_to_single_video_person_association',
                   source_path=str(source), source_sha256=file_hash(source),
                   gallery_profiles=len(profiles), enrollment_rejected=len(rejected),
                   model='osnet_x0_5_msmt17', model_sha256=encoder.sha256,
                   local_tracker=config.get('tracker_type', 'botsort'), local_tracker_reid=False,
                   tracker_runtime=type(tracker.model.predictor.trackers[0]).__name__,
                   device=device, counts=dict(counts), local_tracks=len(matcher.states),
                   transitions=len(matcher.transitions), api_calls=0,
                   elapsed_s=round(time.perf_counter() - started, 3), source_first_ms=first_pts,
                   source_last_ms=last_pts, observation_fps=observation_fps,
                   paced_replay=realtime, timings=dict(pose=timing(pose_times),
                   osnet=timing(encoder_times), total_observation=timing(frame_times)),
                   cross_camera_evaluated=False, ground_truth_evaluated=False,
                   original_interaction_pipeline_changed=False,
                   preview_timing='Sampled observations; use displayed source timestamps for source-video lookup',
                   public_states=['matched', 'unmatched'], similarity_is_probability=False,
                   input_purpose=config.get('input_purpose', 'user_screenshot_gallery'),
                   previews=snapshots)
    save_json(output / 'summary.json', summary)
    lines = ['# 人物档案与B视频关联先验', '',
             '仅验证人物档案、未知编号、持续关联与可视化；没有调用Qwen或修改包裹/动作分析。', '',
             '[关联结果视频](person_reid_review.mp4) · [逐时刻人物输出](people.jsonl) · [建档检查](enrollment_report.json)', '',
             '## 档案', '', '|编号|截图文件夹|有效模板|', '|---|---|---|']
    lines += [f'|{d["person_id"]}|{d["folder"]}|{len(d["samples"])}|' for d in gallery_details]
    lines += ['', '## 运行记录', '', '```json', json.dumps(summary, ensure_ascii=False, indent=2), '```', '',
              'matched/unmatched是程序阈值和连续观察判断，不是人工确认。相似度不是身份概率。',
              '当前没有跨镜或人工真值评测。截图与搜索视频是否同源需由输入说明，不能将同视频检索当跨镜识别率。',
              '视频仅显示采样观测；源时间写在画面与JSON中，未做全帧证据导出。']
    (output / 'report.md').write_text('\n'.join(lines), encoding='utf-8')
    print(json.dumps(summary, ensure_ascii=False), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', default='configs/reid/screenshot_gallery.json')
    parser.add_argument('--run-name', required=True)
    parser.add_argument('--device', choices=['cuda', 'cpu'], default='cuda')
    parser.add_argument('--video', help='Override the search video without editing the config')
    parser.add_argument('--enrollment-dir', help='Override the per-person screenshot folder')
    parser.add_argument('--max-seconds', type=float, default=0)
    parser.add_argument('--realtime', action='store_true', help='Pace video by source timestamps; not a live-stream implementation')
    args = parser.parse_args()
    if args.max_seconds < 0:
        parser.error('--max-seconds must be nonnegative')
    if not args.run_name or Path(args.run_name).name != args.run_name or args.run_name in ('.', '..'):
        parser.error('--run-name must be a simple directory name')
    root = Path(__file__).resolve().parents[2]
    config = json.loads((root / args.config).read_text(encoding='utf-8-sig'))
    if args.video:
        config['video'] = args.video
    if args.enrollment_dir:
        config['enrollment_dir'] = args.enrollment_dir
    try:
        run(root, config, root / 'outputs' / args.run_name, args.device, args.max_seconds, args.realtime)
    except (ValueError, FileNotFoundError, FileExistsError) as exc:
        parser.exit(2, str(exc) + '\n')


if __name__ == '__main__':
    main()
