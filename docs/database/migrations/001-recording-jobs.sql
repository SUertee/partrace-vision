-- Explicit additive migration for an existing 0.3 database; no data is replaced.
BEGIN;
CREATE TABLE recording_jobs (
    job_id TEXT PRIMARY KEY NOT NULL,
    camera_id TEXT NOT NULL,
    config_version TEXT NOT NULL,
    sdk_event_id TEXT NOT NULL,
    event_type TEXT NOT NULL DEFAULT 'people',
    duration_ms BIGINT NOT NULL,
    sdk_timestamp_ms BIGINT,
    created_by TEXT NOT NULL REFERENCES users(user_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    video_sha256 TEXT NOT NULL,
    request_sha256 TEXT NOT NULL,
    storage_key TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'queued',
    attempts INTEGER NOT NULL DEFAULT 0,
    worker_id TEXT,
    worker_user_id TEXT REFERENCES users(user_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    lease_token_sha256 TEXT,
    lease_expires_at TIMESTAMPTZ,
    result_sha256 TEXT,
    error_code TEXT,
    event_count INTEGER,
    evidence_count INTEGER,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    CONSTRAINT uq_recording_job_sdk_event UNIQUE(camera_id,sdk_event_id),
    CONSTRAINT ck_recording_job_metadata CHECK (length(trim(camera_id)) > 0 AND length(trim(config_version)) > 0 AND length(trim(sdk_event_id)) > 0 AND event_type='people' AND duration_ms>0 AND (sdk_timestamp_ms IS NULL OR sdk_timestamp_ms>=0)),
    CONSTRAINT ck_recording_job_hashes CHECK (video_sha256 ~ '^[0-9a-f]{64}$' AND request_sha256 ~ '^[0-9a-f]{64}$' AND (result_sha256 IS NULL OR result_sha256 ~ '^[0-9a-f]{64}$') AND (lease_token_sha256 IS NULL OR lease_token_sha256 ~ '^[0-9a-f]{64}$')),
    CONSTRAINT ck_recording_job_status CHECK (status IN ('queued','processing','completed','failed') AND attempts>=0),
    CONSTRAINT ck_recording_job_lease CHECK ((status='processing')=(lease_expires_at IS NOT NULL) AND (status='queued' OR (worker_id IS NOT NULL AND worker_user_id IS NOT NULL AND lease_token_sha256 IS NOT NULL))),
    CONSTRAINT ck_recording_job_result CHECK ((status IN ('completed','failed'))=(finished_at IS NOT NULL) AND (status<>'completed' OR (result_sha256 IS NOT NULL AND event_count>=0 AND evidence_count>=0 AND error_code IS NULL)) AND (status<>'failed' OR error_code IS NOT NULL)),
    CONSTRAINT ck_recording_job_time CHECK (isfinite(created_at) AND created_at>=TIMESTAMPTZ '1970-01-01 00:00:00+00' AND isfinite(updated_at) AND updated_at>=created_at AND (started_at IS NULL OR (isfinite(started_at) AND started_at>=created_at)) AND (finished_at IS NULL OR (isfinite(finished_at) AND finished_at>=created_at)) AND (lease_expires_at IS NULL OR isfinite(lease_expires_at)))
);
CREATE INDEX idx_recording_jobs_claim ON recording_jobs(status,lease_expires_at,created_at,job_id);
CREATE INDEX idx_recording_jobs_camera ON recording_jobs(camera_id,created_at);
COMMIT;
