"""Public LAN notification routes; HTTP returns before video processing."""
from contextlib import asynccontextmanager
import json
import os
from pathlib import Path

from fastapi import FastAPI, HTTPException, Query
from pydantic import BaseModel, ConfigDict, Field

from .config import load_config
from .jobs import JobStore, QueueWorker, single_worker_lock
from .runner import VisionRunner


class RecordingNotice(BaseModel):
    model_config = ConfigDict(extra='forbid', strict=True)
    recording_id: str = Field(min_length=1, max_length=100, pattern=r'^[A-Za-z0-9][A-Za-z0-9_.:-]*$')
    camera_id: str = Field(min_length=1, max_length=100, pattern=r'^[A-Za-z0-9][A-Za-z0-9_.:-]*$')


class JobAccepted(BaseModel):
    created: bool
    job_id: str
    status: str
    depends_on_a_jobs: list[str]
    status_url: str


class ARecordingNotice(RecordingNotice):
    left2right_flag: bool = Field(description='true: 左到右enter_zone，右到左leave_zone；false反之。无需分界线。')


def create_app(config=None, runner=None, start_worker=True):
    if config is None:
        root = Path(__file__).resolve().parents[2]
        config = load_config(root, os.environ.get('STATION_VISION_SERVICE_CONFIG', 'configs/vision_service.json'))
    store = JobStore(config.state_dir)
    worker = QueueWorker(store, runner or VisionRunner(config), config.values['poll_interval_s'])

    @asynccontextmanager
    async def lifespan(app):
        with single_worker_lock(config.state_dir):
            if start_worker:
                worker.start()
            try:
                yield
            finally:
                if start_worker:
                    worker.stop()

    app = FastAPI(title='Station Vision A/B', version='1.5.0', lifespan=lifespan)
    app.state.store, app.state.worker = store, worker

    def accept(notice, role):
        if config.values['camera_roles'].get(notice.camera_id) != role:
            raise HTTPException(422, 'camera_id is not configured for route ' + role)
        try:
            job, created = store.submit(notice.recording_id, notice.camera_id, role,
                                        left2right_flag=getattr(notice, 'left2right_flag', None))
        except ValueError as exc:
            raise HTTPException(409, str(exc)) from None
        worker.notify()
        return {'created': created, 'job_id': job['job_id'], 'status': job['status'],
                'depends_on_a_jobs': job['dependencies'], 'status_url': '/vision/v1/jobs/' + job['job_id']}

    @app.post('/vision/v1/camera-a/jobs', status_code=202, response_model=JobAccepted)
    def camera_a(notice: ARecordingNotice):
        return accept(notice, 'A')

    @app.post('/vision/v1/camera-b/jobs', status_code=202, response_model=JobAccepted)
    def camera_b(notice: RecordingNotice):
        return accept(notice, 'B')

    @app.get('/vision/v1/jobs')
    def jobs(limit: int = Query(50, ge=1, le=200)):
        return {'jobs': store.list(limit)}

    def get_job(job_id):
        job = store.get(job_id)
        if job is None:
            raise HTTPException(404, 'Unknown job')
        return job

    @app.get('/vision/v1/jobs/{job_id}')
    def job_status(job_id: str):
        return get_job(job_id)

    @app.get('/vision/v1/jobs/{job_id}/result')
    def job_result(job_id: str):
        job = get_job(job_id)
        if not job.get('result') or job['result'].get('analysis_status') not in ('completed','partial'):
            raise HTTPException(409, 'Result not ready; check job status')
        return json.loads(Path(job['result']['result_file']).read_text(encoding='utf-8'))

    @app.post('/vision/v1/jobs/{job_id}/retry', status_code=202)
    def retry(job_id: str):
        get_job(job_id)
        try:
            job = store.retry(job_id)
            worker.notify()
            return job
        except ValueError as exc:
            raise HTTPException(409, str(exc)) from None

    @app.get('/healthz')
    def health():
        alive = bool(worker.thread and worker.thread.is_alive())
        if start_worker and (not alive or worker.fatal_error):
            raise HTTPException(503, 'Queue worker unavailable')
        return {'status': 'ok', 'authentication': 'none', 'worker_count': 1, 'worker_alive': alive,
                'archive_policy': 'A_complete_then_B_load_latest',
                'backend_writeback': 'recording_jobs_complete' if config.values['backend'].get('mode')=='recording_jobs' else 'pending_contract',
                'notification_mode': 'durable_queue', 'queue': store.counts(),
                'source_selection': 'recording_id',
                'analysis_policy': 'local_analysis_then_direct_publication' if config.values['backend'].get('publication_mode', 'direct') == 'direct' else 'local_analysis_then_leased_publication',
                'default_zone_id': config.values.get('default_zone_id', 'E3')}

    return app
