-- Explicit additive source-first migration; run only through the development management command.
-- Existing completed facts stay published. Unfinished jobs reuse their durable original media.
BEGIN;
SET LOCAL TIME ZONE 'UTC';
LOCK TABLE recordings, recording_jobs IN SHARE ROW EXCLUSIVE MODE;
ALTER TABLE recordings ADD COLUMN publication_status TEXT NOT NULL DEFAULT 'published';
ALTER TABLE recordings ALTER COLUMN manifest_sha256 DROP NOT NULL;
ALTER TABLE recordings DROP CONSTRAINT ck_recording_manifest;
ALTER TABLE recordings ADD CONSTRAINT ck_recording_manifest CHECK (
    (publication_status = 'published' AND manifest_sha256 IS NOT NULL AND manifest_sha256 ~ '^[0-9a-f]{64}$')
    OR (publication_status = 'pending' AND manifest_sha256 IS NULL)
);
ALTER TABLE recordings ADD CONSTRAINT ck_recording_publication CHECK (
    publication_status IN ('pending', 'published') AND
    (publication_status <> 'pending' OR (source_mode = 'sdk' AND started_at IS NULL AND storage_key IS NOT NULL))
);

CREATE OR REPLACE FUNCTION partrace_recording_facts_immutable() RETURNS trigger
LANGUAGE plpgsql AS $body$
BEGIN
    IF ROW(NEW.recording_id, NEW.camera_id, NEW.config_version, NEW.source_mode, NEW.started_at, NEW.duration_ms,
        NEW.storage_key, NEW.created_at)
        IS DISTINCT FROM ROW(OLD.recording_id, OLD.camera_id, OLD.config_version, OLD.source_mode, OLD.started_at, OLD.duration_ms,
        OLD.storage_key, OLD.created_at) OR
        (ROW(NEW.publication_status, NEW.manifest_sha256) IS DISTINCT FROM ROW(OLD.publication_status, OLD.manifest_sha256)
         AND NOT (OLD.publication_status = 'pending' AND NEW.publication_status = 'published' AND NEW.manifest_sha256 IS NOT NULL)) THEN
        RAISE EXCEPTION 'recording facts are immutable; create a new recording' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$body$;

DO $body$
BEGIN
    IF EXISTS (SELECT 1 FROM recording_jobs j LEFT JOIN recordings r ON r.recording_id=j.job_id
               WHERE j.status='completed' AND r.recording_id IS NULL) THEN
        RAISE EXCEPTION 'completed recording job is missing published facts; repair before migrating' USING ERRCODE = '23514';
    END IF;
    IF EXISTS (SELECT 1 FROM recording_jobs j JOIN recordings r ON r.recording_id=j.job_id
               WHERE ROW(j.camera_id,j.config_version,j.duration_ms,j.storage_key)
                     IS DISTINCT FROM ROW(r.camera_id,r.config_version,r.duration_ms,r.storage_key)
                     OR r.source_mode <> 'sdk' OR r.started_at IS NOT NULL) THEN
        RAISE EXCEPTION 'recording job source metadata conflicts with existing facts; repair before migrating' USING ERRCODE = '23514';
    END IF;
END;
$body$;

INSERT INTO recordings(recording_id,camera_id,config_version,source_mode,started_at,duration_ms,manifest_sha256,storage_key,media_status,created_at,publication_status)
SELECT j.job_id,j.camera_id,j.config_version,'sdk',NULL,j.duration_ms,NULL,j.storage_key,'available',j.created_at,'pending'
FROM recording_jobs j
WHERE j.status <> 'completed' AND NOT EXISTS (SELECT 1 FROM recordings r WHERE r.recording_id=j.job_id);
COMMIT;
