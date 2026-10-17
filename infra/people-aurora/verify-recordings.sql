-- Run in the partrace database on the configured Aurora cluster.
-- People metadata belongs to recording_jobs; regional facts appear only after publication.
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
       j.sdk_timestamp_ms, -- raw SDK metadata, not calibrated UTC
       j.created_at AS accepted_at,
       j.finished_at,
       j.event_count,
       j.evidence_count,
       j.error_code
FROM public.recordings AS r
JOIN public.recording_jobs AS j ON j.job_id = r.recording_id
WHERE r.camera_id IN ('cam_doorbell_a', 'cam_sphere_b')
ORDER BY j.created_at DESC;
