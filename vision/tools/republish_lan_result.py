"""Republish saved real outputs with configured direct/legacy mode; no VLM."""
import argparse
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import threading

from station_vision.service.config import load_config
from station_vision.service.backend import ServiceError
from station_vision.service.lan_jobs import LanJobSource
from station_vision.reid.profile_store import atomic_json


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('result_package', type=Path)
    parser.add_argument('--config', default='configs/vision_service.lan.json')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    config = load_config(root, args.config)
    package = json.loads(args.result_package.read_text(encoding='utf-8'))
    expected = package.get('remote_source', {}).get('source_sha256')
    if not expected:
        raise SystemExit('Saved result has no verified remote source hash.')
    config.values['backend'].setdefault('source_sha256', {})[package['recording_id']] = expected
    local_job = {k: package[k] for k in ('recording_id','camera_id','role')}
    stamp = datetime.now(timezone(timedelta(hours=8))).strftime('%Y%m%d_%H%M%S_%f')
    work = args.result_package.resolve().parent / ('publication_retry_' + stamp)
    work.mkdir()
    try:
        with LanJobSource(config, local_job, threading.Event()) as remote:
            # The previous exact event IDs/images are kept. Never infer again
            # after a network or configuration failure.
            publication = remote.publish(package, work)
            atomic_json(work/'writeback.json', publication)
            if publication['status'] == 'published':
                package.update(writeback=publication, writeback_status='published')
                package['media_storage_scope'] = 'local_paths_with_verified_backend_copy'
                atomic_json(args.result_package, package)
                if (config.state_dir/'jobs.sqlite3').is_file():
                    from station_vision.service.jobs import JobStore
                    JobStore(config.state_dir).sync_published_result(package['job_id'], args.result_package)
            print(json.dumps({'writeback_status':publication['status'], 'publication':publication,
                              'report':str(work), 'algorithm_runs':0, 'qwen_calls':0}, ensure_ascii=False))
    except ServiceError as exc:
        result = {'status':'not_published', 'error':{'code':exc.code,'message':exc.public_message},
                  'algorithm_runs':0,'qwen_calls':0}
        atomic_json(work/'writeback.json', result)
        print(json.dumps(result, ensure_ascii=False))
        raise SystemExit(1) from None


if __name__ == '__main__':
    main()
