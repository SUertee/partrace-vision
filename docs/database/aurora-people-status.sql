-- Read-only inspection for the independently deployed AWS People extension.
-- Connect to Aurora database partrace, schema public.
-- The current dev baseline does not yet contain recording_jobs/publication_status.
-- Counts are live; SDK timestamps below are uncalibrated source metadata.
BEGIN READ ONLY;

SELECT r.camera_id,
       r.publication_status,
       j.status AS vision_status,
       count(*) AS recording_count
FROM public.recordings AS r
JOIN public.recording_jobs AS j ON j.job_id = r.recording_id
WHERE r.camera_id IN ('cam_doorbell_a', 'cam_sphere_b')
GROUP BY r.camera_id, r.publication_status, j.status
ORDER BY r.camera_id, j.status;

SELECT r.recording_id,
       r.camera_id,
       r.source_mode,
       r.duration_ms,
       r.publication_status,
       r.media_status,
       j.status AS vision_status,
       j.attempts,
       j.event_type AS sdk_trigger_type,
       j.sdk_event_id,
       j.sdk_timestamp_ms,
       j.created_at AS accepted_at,
       j.finished_at,
       j.event_count,
       j.evidence_count,
       j.error_code
FROM public.recordings AS r
JOIN public.recording_jobs AS j ON j.job_id = r.recording_id
WHERE r.camera_id IN ('cam_doorbell_a', 'cam_sphere_b')
ORDER BY j.created_at DESC;

COMMIT;
