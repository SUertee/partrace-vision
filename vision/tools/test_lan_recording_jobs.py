"""Notify the local A/B service, then collect real remote publication/readback."""
import argparse
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import time

import httpx


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--service', default='http://10.0.5.155:8010')
    parser.add_argument('--a-recording', default='lan_981094dbf2cd791399f92f075835898194ff2602fd072f7a')
    parser.add_argument('--b-recording', default='lan_04e5f9a36180fdd4aa4ca851e37c362ce845728c29e18d78')
    parser.add_argument('--a-camera', default='lan_video_1')
    parser.add_argument('--b-camera', default='lan_video_2')
    parser.add_argument('--timeout', type=float, default=1800)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    stamp = datetime.now(timezone(timedelta(hours=8))).strftime('%Y%m%d_%H%M%S_%f')
    folder = root / 'outputs' / ('lan_api_test_' + stamp)
    folder.mkdir(parents=True)
    report = {'service': args.service, 'started_at': stamp, 'jobs': []}
    started = time.perf_counter()
    def save():
        (folder/'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    with httpx.Client(trust_env=False, timeout=10) as client:
        notices = [('A', 'camera-a', {'recording_id':args.a_recording,'camera_id':args.a_camera,'left2right_flag':True}),
                   ('B', 'camera-b', {'recording_id':args.b_recording,'camera_id':args.b_camera})]
        for role, path, body in notices:
            response = client.post(args.service + '/vision/v1/' + path + '/jobs', json=body)
            response.raise_for_status()
            report['jobs'].append({'role': role, 'request':body, 'notification':response.json(), 'http_status':response.status_code})
        save()
        last = {}
        while time.perf_counter() - started < args.timeout:
            for row in report['jobs']:
                jid = row['notification']['job_id']
                response = client.get(args.service + '/vision/v1/jobs/' + jid)
                response.raise_for_status()
                row['job'] = response.json()
                status = row['job']['status']
                if last.get(jid) != status:
                    print(json.dumps({'role':row['role'],'job_id':jid,'status':status,'elapsed_s':round(time.perf_counter()-started,1)}, ensure_ascii=False), flush=True)
                    last[jid] = status
                if status == 'completed' and 'result' not in row:
                    response = client.get(args.service + '/vision/v1/jobs/' + jid + '/result')
                    response.raise_for_status()
                    row['result'] = response.json()
            report['elapsed_s'] = round(time.perf_counter() - started, 3)
            save()
            if all(r['job']['status'] in ('completed','failed','blocked','interrupted') for r in report['jobs']):
                break
            time.sleep(2)
    lines = ['# 局域网原片 A/B 实测', '', '| 路线 | 本地任务 | 回写 | 事件 | 关键帧 |', '|---|---|---|---:|---:|']
    for row in report['jobs']:
        result = row.get('result', {})
        publication = result.get('writeback', {})
        remote = publication.get('remote_job', {})
        lines.append(f"| {row['role']} | {row['job']['status']} | {result.get('writeback_status', 'not_started')} | {remote.get('event_count', '-')} | {remote.get('evidence_count', '-')} |")
        if result:
            lines += ['', f"{row['role']} 输出：`{result['output_dir']}`。"]
        if publication.get('error'):
            lines += ['', f"回写错误：`{publication['error']['code']}`：{publication['error']['message']}。"]
    lines += ['', f"两路总耗时：{report['elapsed_s']:.3f} 秒。图片回读的 SHA-256 校验列表及源片下载摘要见 report.json。"]
    (folder/'report.md').write_text('\n'.join(lines)+'\n', encoding='utf-8')
    print('REPORT ' + str(folder), flush=True)


if __name__ == '__main__':
    main()
