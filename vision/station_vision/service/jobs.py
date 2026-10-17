"""Durable task bookkeeping only. This SQLite DB is NOT the business database."""
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import sqlite3
import threading
import uuid

from .backend import BackendPending, WritebackPending


def utc_now():
    return datetime.now(timezone.utc).isoformat(timespec='milliseconds')


@contextmanager
def single_worker_lock(folder):
    """Reject a second server worker before it can recover/claim our jobs."""
    folder = Path(folder)
    folder.mkdir(parents=True, exist_ok=True)
    with (folder / 'worker.lock').open('a+b') as stream:
        stream.seek(0, 2)
        if stream.tell() == 0:
            stream.write(b'0')
            stream.flush()
        stream.seek(0)
        try:
            if os.name == 'nt':
                import msvcrt
                msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            raise RuntimeError('Vision service already running; use exactly one worker') from None
        try:
            yield
        finally:
            stream.seek(0)
            if os.name == 'nt':
                msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(stream.fileno(), fcntl.LOCK_UN)


class JobStore:
    def __init__(self, folder):
        folder = Path(folder)
        folder.mkdir(parents=True, exist_ok=True)
        self.path = folder / 'jobs.sqlite3'
        with self.connect() as db:
            db.execute('''CREATE TABLE IF NOT EXISTS jobs (
                seq INTEGER PRIMARY KEY AUTOINCREMENT, job_id TEXT UNIQUE NOT NULL,
                recording_id TEXT NOT NULL, camera_id TEXT NOT NULL, role TEXT NOT NULL,
                status TEXT NOT NULL, attempt INTEGER NOT NULL DEFAULT 0,
                created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
                dependencies TEXT NOT NULL, result TEXT, error TEXT,
                UNIQUE(recording_id, camera_id))''')
            if 'options' not in {row['name'] for row in db.execute('PRAGMA table_info(jobs)')}:
                db.execute("ALTER TABLE jobs ADD COLUMN options TEXT NOT NULL DEFAULT '{}'")
            if 'next_retry_at' not in {row['name'] for row in db.execute('PRAGMA table_info(jobs)')}:
                db.execute('ALTER TABLE jobs ADD COLUMN next_retry_at TEXT')

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.path, timeout=15)
        db.row_factory = sqlite3.Row
        try:
            with db:
                yield db
        finally:
            db.close()

    @staticmethod
    def decode(row):
        if row is None:
            return None
        job = dict(row)
        for key in ('dependencies', 'result', 'error', 'options'):
            job[key] = json.loads(job[key]) if job[key] is not None else None
        return job

    def get(self, job_id):
        with self.connect() as db:
            return self.decode(db.execute('SELECT * FROM jobs WHERE job_id=?', (job_id,)).fetchone())

    def list(self, limit=50):
        with self.connect() as db:
            return [self.decode(r) for r in db.execute('SELECT * FROM jobs ORDER BY seq DESC LIMIT ?', (limit,))]

    def counts(self):
        with self.connect() as db:
            return {r['status']: r['n'] for r in db.execute('SELECT status,COUNT(*) AS n FROM jobs GROUP BY status')}

    def submit(self, recording_id, camera_id, role, left2right_flag=None):
        if left2right_flag is not None and type(left2right_flag) is not bool:
            raise ValueError('left2right_flag must be boolean')
        options = {'left2right_flag': left2right_flag} if role == 'A' and left2right_flag is not None else {}
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            old = db.execute('SELECT * FROM jobs WHERE recording_id=? AND camera_id=?', (recording_id, camera_id)).fetchone()
            if old:
                if old['role'] != role:
                    raise ValueError('Existing recording has a different camera role')
                if json.loads(old['options']) != options:
                    raise ValueError('Duplicate notice changed left2right_flag; existing task is preserved')
                return self.decode(old), False
            # Capture all unfinished A jobs already accepted. Later A requests
            # do not jump ahead of this B indefinitely. Failed A requires retry.
            deps = [r[0] for r in db.execute("SELECT job_id FROM jobs WHERE role='A' AND status!='completed' ORDER BY seq")] if role == 'B' else []
            job_id = 'vj_' + uuid.uuid4().hex
            now = utc_now()
            db.execute('''INSERT INTO jobs(job_id,recording_id,camera_id,role,status,created_at,updated_at,dependencies,options)
                          VALUES(?,?,?,?,?,?,?,?,?)''',
                       (job_id, recording_id, camera_id, role, 'waiting_for_a' if deps else 'queued', now, now, json.dumps(deps), json.dumps(options)))
            return self.decode(db.execute('SELECT * FROM jobs WHERE job_id=?', (job_id,)).fetchone()), True

    def recover(self):
        with self.connect() as db:
            db.execute("UPDATE jobs SET status='interrupted',updated_at=?,error=? WHERE status='running'",
                       (utc_now(), json.dumps({'code': 'service_interrupted', 'message': 'Explicit retry required; previous inference may be incomplete'})))

    def claim(self):
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            rows = db.execute('SELECT * FROM jobs ORDER BY seq').fetchall()
            states = {r['job_id']: r['status'] for r in rows}
            analysis_ready = {r['job_id']: (r['status']=='completed' or
                json.loads(r['result'] or '{}').get('analysis_status')=='completed') for r in rows}
            for row in rows:
                if row['status'] not in ('queued', 'waiting_for_a', 'blocked', 'waiting_backend', 'waiting_writeback'):
                    continue
                if row['status'] in ('waiting_backend','waiting_writeback') and row['next_retry_at'] and row['next_retry_at'] > utc_now():
                    continue
                deps = json.loads(row['dependencies'])
                bad = [d for d in deps if not analysis_ready.get(d) and (states.get(d) in ('failed', 'interrupted') or d not in states)]
                if bad:
                    db.execute("UPDATE jobs SET status='blocked',updated_at=?,error=? WHERE job_id=?",
                               (utc_now(), json.dumps({'code': 'a_dependency_failed', 'job_ids': bad}), row['job_id']))
                    continue
                if any(not analysis_ready[d] for d in deps):
                    db.execute("UPDATE jobs SET status='waiting_for_a',error=NULL WHERE job_id=?", (row['job_id'],))
                    continue
                db.execute("UPDATE jobs SET status='running',attempt=attempt+1,updated_at=?,error=NULL,next_retry_at=NULL WHERE job_id=?",
                           (utc_now(), row['job_id']))
                return self.decode(db.execute('SELECT * FROM jobs WHERE job_id=?', (row['job_id'],)).fetchone())
        return None

    def defer(self, job_id, error, retry_after_s, result=None):
        due = (datetime.now(timezone.utc) + timedelta(seconds=retry_after_s)).isoformat(timespec='milliseconds')
        with self.connect() as db:
            db.execute("UPDATE jobs SET status=?,updated_at=?,error=?,next_retry_at=?,result=COALESCE(?,result) WHERE job_id=? AND status='running'",
                       ('waiting_writeback' if result else 'waiting_backend', utc_now(), json.dumps(error), due,
                        json.dumps(result) if result else None, job_id))

    def finish(self, job_id, result=None, error=None, interrupted=False):
        status = 'interrupted' if interrupted else ('failed' if error else 'completed')
        with self.connect() as db:
            db.execute('UPDATE jobs SET status=?,updated_at=?,result=COALESCE(?,result),error=? WHERE job_id=? AND status=\'running\'',
                       (status, utc_now(), json.dumps(result) if result else None, json.dumps(error) if error else None, job_id))

    def retry(self, job_id):
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            row = db.execute('SELECT * FROM jobs WHERE job_id=?', (job_id,)).fetchone()
            if row is None:
                raise KeyError(job_id)
            if row['status'] not in ('failed', 'interrupted', 'waiting_backend','waiting_writeback'):
                raise ValueError('Only failed/interrupted/waiting tasks may be retried; blocked B resumes after A succeeds')
            saved = json.loads(row['result'] or '{}')
            keep_analysis = saved.get('analysis_status') == 'completed'
            # A retry is a newly unfinished dependency for every B not started.
            if row['role'] == 'A' and not keep_analysis:
                for b in db.execute("SELECT * FROM jobs WHERE role='B' AND status IN ('queued','waiting_for_a','blocked','waiting_backend')").fetchall():
                    deps = json.loads(b['dependencies'])
                    if job_id not in deps:
                        deps.append(job_id)
                        db.execute("UPDATE jobs SET dependencies=?,status='waiting_for_a',updated_at=? WHERE job_id=?", (json.dumps(deps), utc_now(), b['job_id']))
            db.execute("UPDATE jobs SET status='queued',error=NULL,result=?,next_retry_at=NULL,updated_at=? WHERE job_id=?",
                       (row['result'] if keep_analysis else None, utc_now(), job_id))
        return self.get(job_id)

    def sync_published_result(self, job_id, result_file):
        """Refresh a completed local task after its saved result is republished."""
        package = json.loads(Path(result_file).read_text(encoding='utf-8'))
        with self.connect() as db:
            job = self.decode(db.execute('SELECT * FROM jobs WHERE job_id=?', (job_id,)).fetchone())
            if not job or job['status'] not in ('completed','waiting_writeback') or not job['result']:
                return False
            if (package.get('recording_id') != job['recording_id'] or
                    package.get('camera_id') != job['camera_id'] or
                    package.get('writeback_status') != 'published' or
                    Path(job['result'].get('result_file', '')).resolve() != Path(result_file).resolve()):
                return False
            result = {**job['result'], 'writeback_status': 'published'}
            db.execute("UPDATE jobs SET result=?,updated_at=?,status='completed',error=NULL,next_retry_at=NULL WHERE job_id=?",
                       (json.dumps(result), utc_now(), job_id))
        return True


class QueueWorker:
    def __init__(self, store, runner, poll_interval=.2):
        self.store, self.runner, self.poll_interval = store, runner, poll_interval
        self.stop_event = threading.Event()
        self.wakeup = threading.Event()
        self.thread = None
        self.fatal_error = None

    def start(self):
        self.store.recover()
        self.thread = threading.Thread(target=self.loop, name='vision-queue', daemon=True)
        self.thread.start()

    def loop(self):
        try:
            while not self.stop_event.is_set():
                job = self.store.claim()
                if not job:
                    self.wakeup.wait(self.poll_interval)
                    self.wakeup.clear()
                    continue
                try:
                    result = self.runner.run(job, self.stop_event)
                    self.store.finish(job['job_id'], result=result)
                except BackendPending as exc:
                    if self.stop_event.is_set():
                        self.store.finish(job['job_id'], error={'code':'service_interrupted'}, interrupted=True)
                    else:
                        self.store.defer(job['job_id'], {'code':exc.code, 'message':exc.public_message}, exc.retry_after_s,
                                         result=exc.result if isinstance(exc,WritebackPending) else None)
                except Exception as exc:
                    # Never serialize arbitrary HTTP bodies, subprocess output or credentials.
                    self.store.finish(job['job_id'], error={'code': getattr(exc, 'code', 'processing_failed'),
                        'message': getattr(exc, 'public_message', 'Inspect the local job diagnostics')},
                        interrupted=self.stop_event.is_set())
        except Exception:
            self.fatal_error = 'queue_worker_failed'

    def stop(self):
        self.stop_event.set()
        self.wakeup.set()
        if self.thread:
            self.thread.join(timeout=150)
            if self.thread.is_alive():
                raise RuntimeError('Worker has not stopped; keep the single-worker lock held')

    def notify(self):
        self.wakeup.set()
