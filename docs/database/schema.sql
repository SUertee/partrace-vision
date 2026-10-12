-- ParTrace Vision · 驿站追迹 · D01 数据库设计草案 0.2-draft
-- 依据：技术方案 r51、PRD r230；生成基准日期：2026-10-12。
-- SQLite 仅用于本地 DDL/约束验证；不是现有数据库迁移，不锁定生产引擎。
-- API 时间为 ISO 8601 UTC，SQL 时间为 epoch 毫秒。未知时间允许 NULL。
-- 媒体文件不入库；storage_key 由服务端分配，临时访问 URL 不持久化。
PRAGMA foreign_keys = ON;

CREATE TABLE cameras (
    camera_id TEXT PRIMARY KEY NOT NULL,
    source_namespace TEXT NOT NULL,
    source_device_id TEXT NOT NULL,
    display_name TEXT NOT NULL,
    camera_status TEXT NOT NULL DEFAULT 'unknown',
    config_version INTEGER NOT NULL DEFAULT 1,
    created_epoch_ms INTEGER NOT NULL,
    CONSTRAINT uq_camera_source UNIQUE (source_namespace, source_device_id),
    CONSTRAINT ck_camera_status CHECK (camera_status IN ('unknown', 'online', 'offline')),
    CONSTRAINT ck_camera_version CHECK (config_version >= 1),
    CONSTRAINT ck_camera_created CHECK (created_epoch_ms >= 0)
);

CREATE TABLE zone_configs (
    zone_config_id TEXT PRIMARY KEY NOT NULL,
    camera_id TEXT NOT NULL,
    zone_id TEXT NOT NULL,
    config_version INTEGER NOT NULL,
    roi_json TEXT,
    diagram_position_json TEXT,
    calibration_status TEXT NOT NULL DEFAULT 'unknown',
    calibration_version TEXT,
    created_epoch_ms INTEGER NOT NULL,
    CONSTRAINT uq_zone_version UNIQUE (camera_id, zone_id, config_version),
    CONSTRAINT uq_zone_camera_identity UNIQUE (zone_config_id, camera_id, zone_id),
    CONSTRAINT fk_zone_camera FOREIGN KEY (camera_id) REFERENCES cameras (camera_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_zone_version CHECK (config_version >= 1),
    CONSTRAINT ck_zone_roi CHECK (roi_json IS NULL OR (json_valid(roi_json) AND json_type(roi_json) IN ('array', 'object'))),
    CONSTRAINT ck_zone_position CHECK (diagram_position_json IS NULL OR (json_valid(diagram_position_json) AND json_type(diagram_position_json) = 'object')),
    CONSTRAINT ck_zone_calibration CHECK (calibration_status IN ('unknown', 'uncalibrated', 'verified')),
    CONSTRAINT ck_zone_verified CHECK (calibration_status <> 'verified' OR calibration_version IS NOT NULL),
    CONSTRAINT ck_zone_created CHECK (created_epoch_ms >= 0)
);

CREATE TABLE recordings (
    recording_id TEXT PRIMARY KEY NOT NULL,
    camera_id TEXT NOT NULL,
    source_mode TEXT NOT NULL,
    source_recording_id TEXT NOT NULL,
    media_kind TEXT NOT NULL,
    raw_start_epoch_ms INTEGER,
    raw_end_epoch_ms INTEGER,
    clock_offset_ms INTEGER,
    start_epoch_ms INTEGER,
    end_epoch_ms INTEGER,
    time_base_num INTEGER,
    time_base_den INTEGER,
    raw_time_origin_pts INTEGER,
    time_mapping_version TEXT,
    alignment_status TEXT NOT NULL DEFAULT 'unknown',
    alignment_reason TEXT DEFAULT 'not_verified',
    storage_key TEXT,
    content_sha256 TEXT,
    media_verified_epoch_ms INTEGER,
    availability TEXT NOT NULL DEFAULT 'unverified',
    availability_reason TEXT DEFAULT 'not_verified',
    expires_epoch_ms INTEGER,
    deleted_epoch_ms INTEGER,
    retention_policy_version TEXT,
    schema_version TEXT NOT NULL DEFAULT '0.2-draft',
    created_epoch_ms INTEGER NOT NULL,
    CONSTRAINT uq_recording_source UNIQUE (camera_id, source_mode, source_recording_id),
    CONSTRAINT uq_recording_camera UNIQUE (recording_id, camera_id),
    CONSTRAINT uq_recording_mode UNIQUE (recording_id, source_mode),
    CONSTRAINT fk_recording_camera FOREIGN KEY (camera_id) REFERENCES cameras (camera_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_recording_source CHECK (source_mode IN ('prerecorded', 'sdk', 'mock')),
    CONSTRAINT ck_recording_kind CHECK (media_kind IN ('video', 'frame_set', 'event_metadata')),
    CONSTRAINT ck_recording_metadata_only CHECK (media_kind <> 'event_metadata' OR (source_mode = 'sdk' AND availability <> 'available')),
    CONSTRAINT ck_recording_raw_interval CHECK (raw_end_epoch_ms IS NULL OR (raw_start_epoch_ms IS NOT NULL AND raw_end_epoch_ms >= raw_start_epoch_ms)),
    CONSTRAINT ck_recording_interval CHECK (end_epoch_ms IS NULL OR (start_epoch_ms IS NOT NULL AND end_epoch_ms >= start_epoch_ms)),
    CONSTRAINT ck_recording_epoch CHECK ((raw_start_epoch_ms IS NULL OR raw_start_epoch_ms >= 0) AND (start_epoch_ms IS NULL OR start_epoch_ms >= 0)),
    CONSTRAINT ck_recording_time_base CHECK ((time_base_num IS NULL AND time_base_den IS NULL) OR (time_base_num IS NOT NULL AND time_base_den IS NOT NULL AND time_base_num > 0 AND time_base_den > 0)),
    CONSTRAINT ck_recording_aligned_start CHECK (start_epoch_ms IS NULL OR (raw_start_epoch_ms IS NOT NULL AND clock_offset_ms IS NOT NULL AND start_epoch_ms = raw_start_epoch_ms + clock_offset_ms)),
    CONSTRAINT ck_recording_aligned_end CHECK (end_epoch_ms IS NULL OR (raw_end_epoch_ms IS NOT NULL AND clock_offset_ms IS NOT NULL AND end_epoch_ms = raw_end_epoch_ms + clock_offset_ms)),
    CONSTRAINT ck_recording_alignment CHECK (alignment_status IN ('aligned', 'unknown')),
    CONSTRAINT ck_recording_known_time CHECK ((alignment_status = 'unknown' AND start_epoch_ms IS NULL AND end_epoch_ms IS NULL AND length(trim(coalesce(alignment_reason, ''))) > 0) OR (alignment_status = 'aligned' AND start_epoch_ms IS NOT NULL AND time_mapping_version IS NOT NULL AND time_base_num IS NOT NULL)),
    CONSTRAINT ck_recording_availability CHECK (availability IN ('available', 'missing', 'expired', 'deleted', 'unverified')),
    CONSTRAINT ck_recording_hash CHECK (content_sha256 IS NULL OR (length(content_sha256) = 64 AND content_sha256 NOT GLOB '*[^0-9a-f]*')),
    CONSTRAINT ck_recording_available CHECK (availability <> 'available' OR (storage_key IS NOT NULL AND length(storage_key) > 0 AND content_sha256 IS NOT NULL AND media_verified_epoch_ms IS NOT NULL)),
    CONSTRAINT ck_recording_unavailable_reason CHECK (availability = 'available' OR length(trim(coalesce(availability_reason, ''))) > 0),
    CONSTRAINT ck_recording_retention CHECK (expires_epoch_ms IS NULL OR (expires_epoch_ms >= 0 AND retention_policy_version IS NOT NULL)),
    CONSTRAINT ck_recording_deleted CHECK (availability <> 'deleted' OR deleted_epoch_ms IS NOT NULL),
    CONSTRAINT ck_recording_created CHECK (created_epoch_ms >= 0)
);

CREATE TABLE index_imports (
    import_id TEXT PRIMARY KEY NOT NULL,
    recording_id TEXT NOT NULL,
    camera_id TEXT NOT NULL,
    index_version INTEGER NOT NULL,
    producer_id TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    payload_sha256 TEXT NOT NULL,
    schema_version TEXT NOT NULL,
    detection_source TEXT NOT NULL,
    detector_version TEXT,
    tracker_version TEXT,
    time_mapping_version TEXT,
    time_mapping_snapshot_json TEXT NOT NULL DEFAULT '{"alignment_status":"unknown"}',
    index_status TEXT NOT NULL DEFAULT 'pending',
    failure_reason TEXT,
    created_epoch_ms INTEGER NOT NULL,
    completed_epoch_ms INTEGER,
    CONSTRAINT uq_import_version UNIQUE (recording_id, index_version),
    CONSTRAINT uq_import_idempotency UNIQUE (producer_id, idempotency_key),
    CONSTRAINT uq_import_recording_camera UNIQUE (import_id, recording_id, camera_id),
    CONSTRAINT uq_import_detection_source UNIQUE (import_id, detection_source),
    CONSTRAINT uq_import_time_mapping UNIQUE (import_id, time_mapping_version),
    CONSTRAINT fk_import_recording FOREIGN KEY (recording_id, camera_id) REFERENCES recordings (recording_id, camera_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_import_version CHECK (index_version >= 1),
    CONSTRAINT ck_import_hash CHECK (length(payload_sha256) = 64 AND payload_sha256 NOT GLOB '*[^0-9a-f]*'),
    CONSTRAINT ck_import_source CHECK (detection_source IN ('automatic', 'manual', 'sdk_event', 'mock')),
    CONSTRAINT ck_import_automatic_version CHECK (detection_source <> 'automatic' OR detector_version IS NOT NULL),
    CONSTRAINT ck_import_time_mapping CHECK (json_valid(time_mapping_snapshot_json) AND json_type(time_mapping_snapshot_json) = 'object' AND json_type(time_mapping_snapshot_json, '$.alignment_status') IS 'text' AND json_extract(time_mapping_snapshot_json, '$.alignment_status') IN ('aligned', 'unknown')),
    CONSTRAINT ck_import_aligned_mapping CHECK (json_extract(time_mapping_snapshot_json, '$.alignment_status') <> 'aligned' OR time_mapping_version IS NOT NULL),
    CONSTRAINT ck_import_status CHECK (index_status IN ('pending', 'partial', 'complete', 'failed')),
    CONSTRAINT ck_import_complete CHECK (index_status <> 'complete' OR (completed_epoch_ms IS NOT NULL AND completed_epoch_ms >= created_epoch_ms)),
    CONSTRAINT ck_import_failed CHECK (index_status <> 'failed' OR length(trim(coalesce(failure_reason, ''))) > 0),
    CONSTRAINT ck_import_created CHECK (created_epoch_ms >= 0)
);

CREATE TABLE track_segments (
    track_segment_id TEXT PRIMARY KEY NOT NULL,
    import_id TEXT NOT NULL,
    recording_id TEXT NOT NULL,
    camera_id TEXT NOT NULL,
    local_track_id TEXT NOT NULL,
    segment_no INTEGER NOT NULL DEFAULT 0,
    detection_source TEXT NOT NULL,
    raw_start_epoch_ms INTEGER,
    raw_end_epoch_ms INTEGER,
    start_epoch_ms INTEGER,
    end_epoch_ms INTEGER,
    start_offset_ms INTEGER,
    end_offset_ms INTEGER,
    alignment_status TEXT NOT NULL DEFAULT 'unknown',
    time_mapping_version TEXT,
    trajectory_kind TEXT NOT NULL DEFAULT 'discrete',
    points_json TEXT NOT NULL DEFAULT '[]',
    zone_sequence_json TEXT NOT NULL DEFAULT '[]',
    unknown_intervals_json TEXT NOT NULL DEFAULT '[]',
    end_reason TEXT NOT NULL DEFAULT 'unknown',
    unknown_reason TEXT,
    created_epoch_ms INTEGER NOT NULL,
    CONSTRAINT uq_segment_local UNIQUE (import_id, local_track_id, segment_no),
    CONSTRAINT uq_segment_import_identity UNIQUE (track_segment_id, import_id, recording_id, camera_id),
    CONSTRAINT fk_segment_import FOREIGN KEY (import_id, recording_id, camera_id) REFERENCES index_imports (import_id, recording_id, camera_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_segment_source FOREIGN KEY (import_id, detection_source) REFERENCES index_imports (import_id, detection_source) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_segment_time_mapping FOREIGN KEY (import_id, time_mapping_version) REFERENCES index_imports (import_id, time_mapping_version) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_segment_no CHECK (segment_no >= 0),
    CONSTRAINT ck_segment_raw_time CHECK ((raw_start_epoch_ms IS NULL OR raw_start_epoch_ms >= 0) AND (raw_end_epoch_ms IS NULL OR (raw_start_epoch_ms IS NOT NULL AND raw_end_epoch_ms >= raw_start_epoch_ms))),
    CONSTRAINT ck_segment_source CHECK (detection_source IN ('automatic', 'manual', 'mock')),
    CONSTRAINT ck_segment_alignment CHECK (alignment_status IN ('aligned', 'unknown')),
    CONSTRAINT ck_segment_time CHECK ((alignment_status = 'unknown' AND start_epoch_ms IS NULL AND end_epoch_ms IS NULL AND length(trim(coalesce(unknown_reason, ''))) > 0) OR (alignment_status = 'aligned' AND start_epoch_ms IS NOT NULL AND end_epoch_ms IS NOT NULL AND end_epoch_ms >= start_epoch_ms AND start_epoch_ms >= 0 AND time_mapping_version IS NOT NULL)),
    CONSTRAINT ck_segment_offsets CHECK ((start_offset_ms IS NULL AND end_offset_ms IS NULL) OR (start_offset_ms IS NOT NULL AND end_offset_ms IS NOT NULL AND start_offset_ms >= 0 AND end_offset_ms >= start_offset_ms)),
    CONSTRAINT ck_segment_trajectory CHECK (trajectory_kind IN ('continuous', 'discrete')),
    CONSTRAINT ck_segment_points CHECK (json_valid(points_json) AND json_type(points_json) = 'array'),
    CONSTRAINT ck_segment_zones CHECK (json_valid(zone_sequence_json) AND json_type(zone_sequence_json) = 'array'),
    CONSTRAINT ck_segment_unknowns CHECK (json_valid(unknown_intervals_json) AND json_type(unknown_intervals_json) = 'array'),
    CONSTRAINT ck_segment_end CHECK (end_reason IN ('left_view', 'occluded', 'lost', 'recording_end', 'unknown')),
    CONSTRAINT ck_segment_created CHECK (created_epoch_ms >= 0)
);

CREATE TABLE events (
    event_id TEXT PRIMARY KEY NOT NULL,
    import_id TEXT NOT NULL,
    recording_id TEXT NOT NULL,
    camera_id TEXT NOT NULL,
    zone_config_id TEXT,
    zone_id TEXT,
    track_segment_id TEXT,
    source_event_id TEXT NOT NULL,
    event_type TEXT NOT NULL,
    detection_source TEXT NOT NULL,
    observation_status TEXT NOT NULL DEFAULT 'candidate',
    raw_start_epoch_ms INTEGER,
    raw_end_epoch_ms INTEGER,
    start_epoch_ms INTEGER,
    end_epoch_ms INTEGER,
    start_offset_ms INTEGER,
    end_offset_ms INTEGER,
    alignment_status TEXT NOT NULL DEFAULT 'unknown',
    time_mapping_version TEXT,
    confidence REAL,
    doorway_direction TEXT,
    enhancement_tags_json TEXT NOT NULL DEFAULT '[]',
    metadata_json TEXT NOT NULL DEFAULT '{}',
    unknown_reason TEXT,
    created_epoch_ms INTEGER NOT NULL,
    CONSTRAINT uq_event_source UNIQUE (import_id, source_event_id),
    CONSTRAINT uq_event_recording_camera UNIQUE (event_id, recording_id, camera_id),
    CONSTRAINT fk_event_import FOREIGN KEY (import_id, recording_id, camera_id) REFERENCES index_imports (import_id, recording_id, camera_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_event_source FOREIGN KEY (import_id, detection_source) REFERENCES index_imports (import_id, detection_source) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_event_time_mapping FOREIGN KEY (import_id, time_mapping_version) REFERENCES index_imports (import_id, time_mapping_version) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_event_zone FOREIGN KEY (zone_config_id, camera_id, zone_id) REFERENCES zone_configs (zone_config_id, camera_id, zone_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_event_segment FOREIGN KEY (track_segment_id, import_id, recording_id, camera_id) REFERENCES track_segments (track_segment_id, import_id, recording_id, camera_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_event_zone_pair CHECK ((zone_config_id IS NULL AND zone_id IS NULL) OR (zone_config_id IS NOT NULL AND zone_id IS NOT NULL)),
    CONSTRAINT ck_event_source CHECK (detection_source IN ('automatic', 'manual', 'sdk_event', 'mock')),
    CONSTRAINT ck_event_type CHECK (event_type IN ('enter_zone', 'dwell', 'shelf_interaction', 'leave_zone', 'doorway_crossing', 'device_detection')),
    CONSTRAINT ck_event_sdk CHECK ((detection_source = 'sdk_event' AND event_type = 'device_detection' AND track_segment_id IS NULL) OR (detection_source <> 'sdk_event' AND event_type <> 'device_detection' AND zone_config_id IS NOT NULL)),
    CONSTRAINT ck_event_sdk_raw_type CHECK (detection_source <> 'sdk_event' OR (json_type(metadata_json, '$.raw_sdk_type') IS 'text' AND length(trim(json_extract(metadata_json, '$.raw_sdk_type'))) > 0)),
    CONSTRAINT ck_event_observation CHECK (observation_status IN ('observed', 'candidate')),
    CONSTRAINT ck_event_raw_time CHECK ((raw_start_epoch_ms IS NULL OR raw_start_epoch_ms >= 0) AND (raw_end_epoch_ms IS NULL OR (raw_start_epoch_ms IS NOT NULL AND raw_end_epoch_ms >= raw_start_epoch_ms))),
    CONSTRAINT ck_event_alignment CHECK (alignment_status IN ('aligned', 'unknown')),
    CONSTRAINT ck_event_time CHECK ((alignment_status = 'unknown' AND start_epoch_ms IS NULL AND end_epoch_ms IS NULL AND length(trim(coalesce(unknown_reason, ''))) > 0) OR (alignment_status = 'aligned' AND start_epoch_ms IS NOT NULL AND end_epoch_ms IS NOT NULL AND end_epoch_ms >= start_epoch_ms AND start_epoch_ms >= 0 AND time_mapping_version IS NOT NULL)),
    CONSTRAINT ck_event_offsets CHECK ((start_offset_ms IS NULL AND end_offset_ms IS NULL) OR (start_offset_ms IS NOT NULL AND end_offset_ms IS NOT NULL AND start_offset_ms >= 0 AND end_offset_ms >= start_offset_ms)),
    CONSTRAINT ck_event_confidence CHECK (confidence IS NULL OR (confidence >= 0.0 AND confidence <= 1.0)),
    CONSTRAINT ck_event_direction CHECK (doorway_direction IS NULL OR doorway_direction IN ('entering', 'leaving', 'unknown')),
    CONSTRAINT ck_event_doorway CHECK (event_type <> 'doorway_crossing' OR (doorway_direction IS NOT NULL AND doorway_direction IN ('entering', 'leaving'))),
    CONSTRAINT ck_event_tags CHECK (json_valid(enhancement_tags_json) AND json_type(enhancement_tags_json) = 'array'),
    CONSTRAINT ck_event_payload CHECK (json_valid(metadata_json) AND json_type(metadata_json) = 'object'),
    CONSTRAINT ck_event_created CHECK (created_epoch_ms >= 0)
);

CREATE TABLE evidence (
    evidence_id TEXT PRIMARY KEY NOT NULL,
    recording_id TEXT NOT NULL,
    camera_id TEXT NOT NULL,
    source_mode TEXT NOT NULL,
    media_kind TEXT NOT NULL,
    raw_captured_epoch_ms INTEGER,
    raw_frame_pts INTEGER,
    captured_epoch_ms INTEGER,
    offset_ms INTEGER,
    context_start_offset_ms INTEGER,
    context_end_offset_ms INTEGER,
    alignment_status TEXT NOT NULL DEFAULT 'unknown',
    alignment_reason TEXT DEFAULT 'not_verified',
    time_mapping_version TEXT,
    metadata_json TEXT NOT NULL DEFAULT '{}',
    storage_key TEXT,
    content_sha256 TEXT,
    media_verified_epoch_ms INTEGER,
    availability TEXT NOT NULL DEFAULT 'unverified',
    availability_reason TEXT DEFAULT 'not_verified',
    expires_epoch_ms INTEGER,
    deleted_epoch_ms INTEGER,
    retention_policy_version TEXT,
    created_epoch_ms INTEGER NOT NULL,
    CONSTRAINT uq_evidence_recording_camera UNIQUE (evidence_id, recording_id, camera_id),
    CONSTRAINT fk_evidence_recording FOREIGN KEY (recording_id, camera_id) REFERENCES recordings (recording_id, camera_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_evidence_mode FOREIGN KEY (recording_id, source_mode) REFERENCES recordings (recording_id, source_mode) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_evidence_source CHECK (source_mode IN ('prerecorded', 'sdk', 'mock')),
    CONSTRAINT ck_evidence_kind CHECK (media_kind IN ('keyframe', 'video_clip')),
    CONSTRAINT ck_evidence_alignment CHECK (alignment_status IN ('aligned', 'unknown')),
    CONSTRAINT ck_evidence_time CHECK ((alignment_status = 'unknown' AND captured_epoch_ms IS NULL AND length(trim(coalesce(alignment_reason, ''))) > 0) OR (alignment_status = 'aligned' AND captured_epoch_ms IS NOT NULL AND captured_epoch_ms >= 0 AND time_mapping_version IS NOT NULL)),
    CONSTRAINT ck_evidence_metadata CHECK (json_valid(metadata_json) AND json_type(metadata_json) = 'object'),
    CONSTRAINT ck_evidence_offset CHECK (offset_ms IS NULL OR offset_ms >= 0),
    CONSTRAINT ck_evidence_context CHECK ((context_start_offset_ms IS NULL AND context_end_offset_ms IS NULL) OR (context_start_offset_ms IS NOT NULL AND context_end_offset_ms IS NOT NULL AND context_start_offset_ms >= 0 AND context_end_offset_ms >= context_start_offset_ms AND offset_ms IS NOT NULL AND offset_ms >= context_start_offset_ms AND offset_ms <= context_end_offset_ms)),
    CONSTRAINT ck_evidence_availability CHECK (availability IN ('available', 'missing', 'expired', 'deleted', 'unverified')),
    CONSTRAINT ck_evidence_hash CHECK (content_sha256 IS NULL OR (length(content_sha256) = 64 AND content_sha256 NOT GLOB '*[^0-9a-f]*')),
    CONSTRAINT ck_evidence_available CHECK (availability <> 'available' OR (storage_key IS NOT NULL AND length(storage_key) > 0 AND content_sha256 IS NOT NULL AND media_verified_epoch_ms IS NOT NULL)),
    CONSTRAINT ck_evidence_unavailable_reason CHECK (availability = 'available' OR length(trim(coalesce(availability_reason, ''))) > 0),
    CONSTRAINT ck_evidence_retention CHECK (expires_epoch_ms IS NULL OR (expires_epoch_ms >= 0 AND retention_policy_version IS NOT NULL)),
    CONSTRAINT ck_evidence_deleted CHECK (availability <> 'deleted' OR deleted_epoch_ms IS NOT NULL),
    CONSTRAINT ck_evidence_created CHECK (created_epoch_ms >= 0)
);

CREATE TABLE event_evidence (
    event_id TEXT NOT NULL,
    evidence_id TEXT NOT NULL,
    recording_id TEXT NOT NULL,
    camera_id TEXT NOT NULL,
    display_order INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (event_id, evidence_id),
    CONSTRAINT fk_event_evidence_event FOREIGN KEY (event_id, recording_id, camera_id) REFERENCES events (event_id, recording_id, camera_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_event_evidence_asset FOREIGN KEY (evidence_id, recording_id, camera_id) REFERENCES evidence (evidence_id, recording_id, camera_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_event_evidence_order CHECK (display_order >= 0)
);

CREATE TABLE coverage_intervals (
    coverage_id TEXT PRIMARY KEY NOT NULL,
    camera_id TEXT NOT NULL,
    zone_config_id TEXT,
    zone_id TEXT,
    recording_id TEXT,
    import_id TEXT,
    capture_kind TEXT NOT NULL,
    raw_start_epoch_ms INTEGER,
    raw_end_epoch_ms INTEGER,
    start_epoch_ms INTEGER,
    end_epoch_ms INTEGER,
    alignment_status TEXT NOT NULL DEFAULT 'unknown',
    time_mapping_version TEXT,
    index_status TEXT NOT NULL DEFAULT 'pending',
    unknown_reason TEXT,
    source_mode TEXT NOT NULL,
    created_epoch_ms INTEGER NOT NULL,
    CONSTRAINT fk_coverage_camera FOREIGN KEY (camera_id) REFERENCES cameras (camera_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_coverage_zone FOREIGN KEY (zone_config_id, camera_id, zone_id) REFERENCES zone_configs (zone_config_id, camera_id, zone_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_coverage_recording FOREIGN KEY (recording_id, camera_id) REFERENCES recordings (recording_id, camera_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_coverage_mode FOREIGN KEY (recording_id, source_mode) REFERENCES recordings (recording_id, source_mode) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_coverage_import FOREIGN KEY (import_id, recording_id, camera_id) REFERENCES index_imports (import_id, recording_id, camera_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_coverage_time_mapping FOREIGN KEY (import_id, time_mapping_version) REFERENCES index_imports (import_id, time_mapping_version) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_coverage_zone_pair CHECK ((zone_config_id IS NULL AND zone_id IS NULL) OR (zone_config_id IS NOT NULL AND zone_id IS NOT NULL)),
    CONSTRAINT ck_coverage_source CHECK (source_mode IN ('prerecorded', 'sdk', 'mock')),
    CONSTRAINT ck_coverage_kind CHECK (capture_kind IN ('continuous', 'frame_sample', 'gap')),
    CONSTRAINT ck_coverage_input CHECK (capture_kind = 'gap' OR recording_id IS NOT NULL),
    CONSTRAINT ck_coverage_import CHECK (import_id IS NULL OR recording_id IS NOT NULL),
    CONSTRAINT ck_coverage_index CHECK (index_status IN ('pending', 'partial', 'complete', 'failed')),
    CONSTRAINT ck_coverage_complete CHECK (index_status <> 'complete' OR import_id IS NOT NULL),
    CONSTRAINT ck_coverage_alignment CHECK (alignment_status IN ('aligned', 'unknown')),
    CONSTRAINT ck_coverage_raw_time CHECK ((raw_start_epoch_ms IS NULL OR raw_start_epoch_ms >= 0) AND (raw_end_epoch_ms IS NULL OR (raw_start_epoch_ms IS NOT NULL AND raw_end_epoch_ms >= raw_start_epoch_ms))),
    CONSTRAINT ck_coverage_time CHECK ((alignment_status = 'unknown' AND start_epoch_ms IS NULL AND end_epoch_ms IS NULL AND length(trim(coalesce(unknown_reason, ''))) > 0) OR (alignment_status = 'aligned' AND start_epoch_ms IS NOT NULL AND end_epoch_ms IS NOT NULL AND start_epoch_ms >= 0 AND end_epoch_ms >= start_epoch_ms)),
    CONSTRAINT ck_coverage_shape CHECK (alignment_status = 'unknown' OR (capture_kind = 'frame_sample' AND end_epoch_ms = start_epoch_ms) OR (capture_kind IN ('continuous', 'gap') AND end_epoch_ms > start_epoch_ms)),
    CONSTRAINT ck_coverage_gap_reason CHECK (capture_kind <> 'gap' OR length(trim(coalesce(unknown_reason, ''))) > 0),
    CONSTRAINT ck_coverage_created CHECK (created_epoch_ms >= 0)
);

CREATE TABLE journeys (
    journey_id TEXT PRIMARY KEY NOT NULL,
    actor_id TEXT NOT NULL,
    camera_id TEXT,
    zone_id TEXT,
    query_start_epoch_ms INTEGER NOT NULL,
    query_end_epoch_ms INTEGER NOT NULL,
    server_snapshot_json TEXT NOT NULL,
    snapshot_schema_version TEXT NOT NULL DEFAULT '0.2-draft',
    review_version INTEGER NOT NULL DEFAULT 1,
    decision_status TEXT NOT NULL DEFAULT 'pending',
    idempotency_key TEXT NOT NULL,
    request_sha256 TEXT NOT NULL,
    created_epoch_ms INTEGER NOT NULL,
    updated_epoch_ms INTEGER NOT NULL,
    CONSTRAINT uq_journey_idempotency UNIQUE (actor_id, idempotency_key),
    CONSTRAINT uq_journey_current_version UNIQUE (journey_id, review_version),
    CONSTRAINT fk_journey_camera FOREIGN KEY (camera_id) REFERENCES cameras (camera_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_journey_query CHECK (query_start_epoch_ms >= 0 AND query_end_epoch_ms > query_start_epoch_ms),
    CONSTRAINT ck_journey_snapshot CHECK (json_valid(server_snapshot_json) AND json_type(server_snapshot_json) = 'object' AND json_type(server_snapshot_json, '$.baseline') IS 'object' AND json_type(server_snapshot_json, '$.human_selection') IS 'object'),
    CONSTRAINT ck_journey_version CHECK (review_version >= 1),
    CONSTRAINT ck_journey_decision CHECK (decision_status IN ('pending', 'confirmed', 'rejected', 'split')),
    CONSTRAINT ck_journey_hash CHECK (length(request_sha256) = 64 AND request_sha256 NOT GLOB '*[^0-9a-f]*'),
    CONSTRAINT ck_journey_time CHECK (created_epoch_ms >= 0 AND updated_epoch_ms >= created_epoch_ms)
);

CREATE TABLE journey_items (
    journey_item_id TEXT PRIMARY KEY NOT NULL,
    journey_id TEXT NOT NULL,
    journey_version INTEGER NOT NULL,
    item_kind TEXT NOT NULL,
    event_id TEXT,
    track_segment_id TEXT,
    evidence_id TEXT,
    display_order INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uq_journey_item_order UNIQUE (journey_id, item_kind, display_order),
    CONSTRAINT uq_journey_event UNIQUE (journey_id, event_id),
    CONSTRAINT uq_journey_segment UNIQUE (journey_id, track_segment_id),
    CONSTRAINT uq_journey_evidence UNIQUE (journey_id, evidence_id),
    CONSTRAINT fk_journey_item_version FOREIGN KEY (journey_id, journey_version) REFERENCES journeys (journey_id, review_version) ON DELETE RESTRICT ON UPDATE NO ACTION DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_journey_item_event FOREIGN KEY (event_id) REFERENCES events (event_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_journey_item_segment FOREIGN KEY (track_segment_id) REFERENCES track_segments (track_segment_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_journey_item_evidence FOREIGN KEY (evidence_id) REFERENCES evidence (evidence_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_journey_item_type CHECK ((item_kind = 'event' AND event_id IS NOT NULL AND track_segment_id IS NULL AND evidence_id IS NULL) OR (item_kind = 'segment' AND event_id IS NULL AND track_segment_id IS NOT NULL AND evidence_id IS NULL) OR (item_kind = 'evidence' AND event_id IS NULL AND track_segment_id IS NULL AND evidence_id IS NOT NULL)),
    CONSTRAINT ck_journey_item_version CHECK (journey_version >= 1),
    CONSTRAINT ck_journey_item_order CHECK (display_order >= 0)
);

CREATE TABLE review_actions (
    review_action_id TEXT PRIMARY KEY NOT NULL,
    journey_id TEXT NOT NULL,
    actor_id TEXT NOT NULL,
    action TEXT NOT NULL,
    reason TEXT NOT NULL,
    expected_review_version INTEGER NOT NULL,
    from_review_version INTEGER NOT NULL,
    to_review_version INTEGER NOT NULL,
    before_snapshot_json TEXT NOT NULL,
    after_snapshot_json TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    request_sha256 TEXT NOT NULL,
    response_snapshot_json TEXT NOT NULL DEFAULT '{}',
    created_epoch_ms INTEGER NOT NULL,
    CONSTRAINT uq_review_idempotency UNIQUE (actor_id, idempotency_key),
    CONSTRAINT uq_review_version UNIQUE (journey_id, to_review_version),
    CONSTRAINT fk_review_journey FOREIGN KEY (journey_id) REFERENCES journeys (journey_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_review_action CHECK (action IN ('confirm', 'reject', 'split', 'comment')),
    CONSTRAINT ck_review_reason CHECK (length(trim(reason)) > 0),
    CONSTRAINT ck_review_versions CHECK (from_review_version >= 1 AND expected_review_version = from_review_version AND to_review_version = from_review_version + 1),
    CONSTRAINT ck_review_before CHECK (json_valid(before_snapshot_json) AND json_type(before_snapshot_json) = 'object'),
    CONSTRAINT ck_review_after CHECK (json_valid(after_snapshot_json) AND json_type(after_snapshot_json) = 'object'),
    CONSTRAINT ck_review_response CHECK (json_valid(response_snapshot_json) AND json_type(response_snapshot_json) = 'object'),
    CONSTRAINT ck_review_hash CHECK (length(request_sha256) = 64 AND request_sha256 NOT GLOB '*[^0-9a-f]*'),
    CONSTRAINT ck_review_created CHECK (created_epoch_ms >= 0)
);

CREATE TABLE retention_cleanup_logs (
    cleanup_log_id TEXT PRIMARY KEY NOT NULL,
    task_id TEXT NOT NULL,
    attempt_no INTEGER NOT NULL,
    target_kind TEXT NOT NULL,
    recording_id TEXT,
    evidence_id TEXT,
    storage_key_snapshot TEXT,
    retention_policy_version TEXT NOT NULL,
    policy_snapshot_json TEXT NOT NULL,
    due_epoch_ms INTEGER NOT NULL,
    started_epoch_ms INTEGER NOT NULL,
    finished_epoch_ms INTEGER NOT NULL,
    result TEXT NOT NULL,
    failure_reason TEXT,
    CONSTRAINT uq_cleanup_attempt UNIQUE (task_id, attempt_no),
    CONSTRAINT fk_cleanup_recording FOREIGN KEY (recording_id) REFERENCES recordings (recording_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_cleanup_evidence FOREIGN KEY (evidence_id) REFERENCES evidence (evidence_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_cleanup_target CHECK ((target_kind = 'recording' AND recording_id IS NOT NULL AND evidence_id IS NULL) OR (target_kind = 'evidence' AND recording_id IS NULL AND evidence_id IS NOT NULL)),
    CONSTRAINT ck_cleanup_attempt CHECK (attempt_no >= 1),
    CONSTRAINT ck_cleanup_policy CHECK (json_valid(policy_snapshot_json) AND json_type(policy_snapshot_json) = 'object'),
    CONSTRAINT ck_cleanup_time CHECK (due_epoch_ms >= 0 AND started_epoch_ms >= 0 AND finished_epoch_ms >= started_epoch_ms),
    CONSTRAINT ck_cleanup_result CHECK (result IN ('deleted', 'already_missing', 'failed', 'skipped')),
    CONSTRAINT ck_cleanup_failure CHECK (result <> 'failed' OR length(trim(coalesce(failure_reason, ''))) > 0)
);

-- 结构化查询与两侧 FK 索引；时间相交仍须额外检查 end_epoch_ms。
CREATE INDEX idx_recordings_camera_time ON recordings (camera_id, start_epoch_ms, recording_id);
CREATE INDEX idx_recordings_expiry ON recordings (availability, expires_epoch_ms);
CREATE INDEX idx_import_latest_complete ON index_imports (recording_id, index_status, index_version DESC);
CREATE INDEX idx_segments_recording_time ON track_segments (recording_id, start_epoch_ms, track_segment_id);
CREATE INDEX idx_segments_import_source ON track_segments (import_id, detection_source);
CREATE INDEX idx_events_zone_time ON events (zone_id, start_epoch_ms, event_id);
CREATE INDEX idx_events_camera_time ON events (camera_id, start_epoch_ms, event_id);
CREATE INDEX idx_events_import_time ON events (import_id, start_epoch_ms, event_id);
CREATE INDEX idx_events_import_source ON events (import_id, detection_source);
CREATE INDEX idx_events_segment_time ON events (track_segment_id, start_epoch_ms);
CREATE INDEX idx_events_zone_config ON events (zone_config_id, camera_id, zone_id);
CREATE INDEX idx_evidence_recording_time ON evidence (recording_id, captured_epoch_ms);
CREATE INDEX idx_evidence_expiry ON evidence (availability, expires_epoch_ms);
CREATE INDEX idx_event_evidence_asset ON event_evidence (evidence_id, event_id);
CREATE INDEX idx_coverage_camera_zone_time ON coverage_intervals (camera_id, zone_id, start_epoch_ms);
CREATE INDEX idx_coverage_zone_config ON coverage_intervals (zone_config_id, camera_id, zone_id);
CREATE INDEX idx_coverage_recording ON coverage_intervals (recording_id, camera_id);
CREATE INDEX idx_coverage_import ON coverage_intervals (import_id, recording_id, camera_id);
CREATE INDEX idx_journeys_actor_updated ON journeys (actor_id, updated_epoch_ms, journey_id);
CREATE INDEX idx_journeys_camera ON journeys (camera_id);
CREATE INDEX idx_journey_items_event ON journey_items (event_id);
CREATE INDEX idx_journey_items_segment ON journey_items (track_segment_id);
CREATE INDEX idx_journey_items_evidence ON journey_items (evidence_id);
CREATE INDEX idx_cleanup_recording ON retention_cleanup_logs (recording_id, finished_epoch_ms);
CREATE INDEX idx_cleanup_evidence ON retention_cleanup_logs (evidence_id, finished_epoch_ms);

-- 区域配置历史不可覆盖；新增 ROI 或标定需新 config_version。
CREATE TRIGGER zone_configs_no_update BEFORE UPDATE ON zone_configs
BEGIN SELECT RAISE(ABORT, 'zone_config_is_immutable'); END;
CREATE TRIGGER zone_configs_no_delete BEFORE DELETE ON zone_configs
BEGIN SELECT RAISE(ABORT, 'zone_config_history_is_retained'); END;

-- 索引完整批次封存。导入事务先写 pending/partial，再写事实，最后 complete。
CREATE TRIGGER index_imports_complete_no_update BEFORE UPDATE ON index_imports
WHEN OLD.index_status = 'complete'
BEGIN SELECT RAISE(ABORT, 'completed_import_is_immutable'); END;
CREATE TRIGGER index_imports_no_delete BEFORE DELETE ON index_imports
BEGIN SELECT RAISE(ABORT, 'index_import_history_is_retained'); END;

CREATE TRIGGER recordings_time_sealed BEFORE UPDATE OF camera_id, source_mode, source_recording_id, media_kind, raw_start_epoch_ms, raw_end_epoch_ms, clock_offset_ms, start_epoch_ms, end_epoch_ms, time_base_num, time_base_den, raw_time_origin_pts, time_mapping_version, alignment_status ON recordings
WHEN EXISTS (SELECT 1 FROM index_imports WHERE recording_id = OLD.recording_id AND index_status = 'complete')
BEGIN SELECT RAISE(ABORT, 'recording_time_basis_is_sealed'); END;

CREATE TRIGGER track_segments_complete_no_insert BEFORE INSERT ON track_segments
WHEN EXISTS (SELECT 1 FROM index_imports WHERE import_id = NEW.import_id AND index_status = 'complete')
BEGIN SELECT RAISE(ABORT, 'completed_import_is_immutable'); END;
CREATE TRIGGER track_segments_complete_no_update BEFORE UPDATE ON track_segments
WHEN EXISTS (SELECT 1 FROM index_imports WHERE import_id IN (OLD.import_id, NEW.import_id) AND index_status = 'complete')
BEGIN SELECT RAISE(ABORT, 'completed_segment_is_immutable'); END;
CREATE TRIGGER track_segments_complete_no_delete BEFORE DELETE ON track_segments
WHEN EXISTS (SELECT 1 FROM index_imports WHERE import_id = OLD.import_id AND index_status = 'complete')
BEGIN SELECT RAISE(ABORT, 'completed_segment_is_immutable'); END;

CREATE TRIGGER events_complete_no_insert BEFORE INSERT ON events
WHEN EXISTS (SELECT 1 FROM index_imports WHERE import_id = NEW.import_id AND index_status = 'complete')
BEGIN SELECT RAISE(ABORT, 'completed_import_is_immutable'); END;
CREATE TRIGGER events_complete_no_update BEFORE UPDATE ON events
WHEN EXISTS (SELECT 1 FROM index_imports WHERE import_id IN (OLD.import_id, NEW.import_id) AND index_status = 'complete')
BEGIN SELECT RAISE(ABORT, 'completed_event_is_immutable'); END;
CREATE TRIGGER events_complete_no_delete BEFORE DELETE ON events
WHEN EXISTS (SELECT 1 FROM index_imports WHERE import_id = OLD.import_id AND index_status = 'complete')
BEGIN SELECT RAISE(ABORT, 'completed_event_is_immutable'); END;

CREATE TRIGGER event_evidence_complete_no_insert BEFORE INSERT ON event_evidence
WHEN EXISTS (SELECT 1 FROM events JOIN index_imports USING (import_id) WHERE event_id = NEW.event_id AND index_status = 'complete')
BEGIN SELECT RAISE(ABORT, 'completed_event_evidence_is_immutable'); END;
CREATE TRIGGER event_evidence_complete_no_update BEFORE UPDATE ON event_evidence
WHEN EXISTS (SELECT 1 FROM events JOIN index_imports USING (import_id) WHERE event_id IN (OLD.event_id, NEW.event_id) AND index_status = 'complete')
BEGIN SELECT RAISE(ABORT, 'completed_event_evidence_is_immutable'); END;
CREATE TRIGGER event_evidence_complete_no_delete BEFORE DELETE ON event_evidence
WHEN EXISTS (SELECT 1 FROM events JOIN index_imports USING (import_id) WHERE event_id = OLD.event_id AND index_status = 'complete')
BEGIN SELECT RAISE(ABORT, 'completed_event_evidence_is_immutable'); END;

-- 已封存事实引用的证据，定位元数据不可覆盖；可用性/过期/删除状态可更新。
CREATE TRIGGER evidence_position_sealed BEFORE UPDATE OF evidence_id, recording_id, camera_id, source_mode, media_kind, raw_captured_epoch_ms, raw_frame_pts, captured_epoch_ms, offset_ms, context_start_offset_ms, context_end_offset_ms, alignment_status, time_mapping_version, metadata_json ON evidence
WHEN EXISTS (SELECT 1 FROM event_evidence JOIN events USING (event_id) JOIN index_imports USING (import_id) WHERE evidence_id = OLD.evidence_id AND index_status = 'complete')
BEGIN SELECT RAISE(ABORT, 'evidence_position_is_sealed'); END;

CREATE TRIGGER event_evidence_time_mapping_guard BEFORE INSERT ON event_evidence
WHEN EXISTS (
    SELECT 1 FROM events JOIN evidence
    ON events.event_id = NEW.event_id AND evidence.evidence_id = NEW.evidence_id
    WHERE events.time_mapping_version IS NOT NULL AND evidence.time_mapping_version IS NOT NULL
      AND events.time_mapping_version <> evidence.time_mapping_version
)
BEGIN SELECT RAISE(ABORT, 'evidence_time_mapping_conflict'); END;

-- 元数据事件不是已采帧；frame_set 的离散样本不能补成连续覆盖。
CREATE TRIGGER coverage_media_kind_guard BEFORE INSERT ON coverage_intervals
WHEN EXISTS (
    SELECT 1 FROM recordings WHERE recording_id = NEW.recording_id
    AND ((media_kind = 'event_metadata' AND NEW.capture_kind <> 'gap')
      OR (media_kind = 'frame_set' AND NEW.capture_kind = 'continuous'))
)
BEGIN SELECT RAISE(ABORT, 'capture_kind_has_no_input_evidence'); END;

CREATE TRIGGER coverage_complete_no_insert BEFORE INSERT ON coverage_intervals
WHEN EXISTS (SELECT 1 FROM index_imports WHERE import_id = NEW.import_id AND index_status = 'complete')
BEGIN SELECT RAISE(ABORT, 'completed_coverage_is_immutable'); END;
CREATE TRIGGER coverage_complete_no_update BEFORE UPDATE ON coverage_intervals
WHEN EXISTS (SELECT 1 FROM index_imports WHERE import_id IN (OLD.import_id, NEW.import_id) AND index_status = 'complete')
BEGIN SELECT RAISE(ABORT, 'completed_coverage_is_immutable'); END;
CREATE TRIGGER coverage_complete_no_delete BEFORE DELETE ON coverage_intervals
WHEN EXISTS (SELECT 1 FROM index_imports WHERE import_id = OLD.import_id AND index_status = 'complete')
BEGIN SELECT RAISE(ABORT, 'completed_coverage_is_immutable'); END;

CREATE TRIGGER evidence_content_no_replace BEFORE UPDATE OF storage_key, content_sha256 ON evidence
WHEN OLD.content_sha256 IS NOT NULL AND (OLD.content_sha256 IS NOT NEW.content_sha256 OR OLD.storage_key IS NOT NEW.storage_key)
BEGIN SELECT RAISE(ABORT, 'evidence_content_is_immutable'); END;
CREATE TRIGGER recordings_content_no_replace BEFORE UPDATE OF storage_key, content_sha256 ON recordings
WHEN OLD.content_sha256 IS NOT NULL AND (OLD.content_sha256 IS NOT NEW.content_sha256 OR OLD.storage_key IS NOT NEW.storage_key)
BEGIN SELECT RAISE(ABORT, 'recording_content_is_immutable'); END;
CREATE TRIGGER coverage_media_kind_update_guard BEFORE UPDATE OF capture_kind, recording_id ON coverage_intervals
WHEN EXISTS (
    SELECT 1 FROM recordings WHERE recording_id = NEW.recording_id
    AND ((media_kind = 'event_metadata' AND NEW.capture_kind <> 'gap')
      OR (media_kind = 'frame_set' AND NEW.capture_kind = 'continuous'))
)
BEGIN SELECT RAISE(ABORT, 'capture_kind_has_no_input_evidence'); END;

-- 当前选择由服务层在同一事务替换，历史保存在不可变 ReviewAction 快照。
-- SQLite 演示：先幂等重放检查，INSERT review_actions 触发 CAS 和 Journey 更新，
-- 再替换 journey_items；其版本 FK 延迟到事务提交检查，不允许留下旧版本选择。
CREATE TRIGGER review_actions_expected_version BEFORE INSERT ON review_actions
BEGIN
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1 FROM journeys
        WHERE journey_id = NEW.journey_id
          AND review_version = NEW.expected_review_version
          AND server_snapshot_json = NEW.before_snapshot_json
          AND NEW.created_epoch_ms >= updated_epoch_ms
    ) THEN RAISE(ABORT, 'review_version_or_snapshot_conflict') END;
END;

CREATE TRIGGER review_actions_apply AFTER INSERT ON review_actions
BEGIN
    UPDATE journeys
    SET review_version = NEW.to_review_version,
        server_snapshot_json = NEW.after_snapshot_json,
        decision_status = CASE NEW.action
            WHEN 'confirm' THEN 'confirmed'
            WHEN 'reject' THEN 'rejected'
            WHEN 'split' THEN 'split'
            ELSE decision_status
        END,
        updated_epoch_ms = NEW.created_epoch_ms
    WHERE journey_id = NEW.journey_id AND review_version = NEW.expected_review_version;
END;

CREATE TRIGGER journeys_query_no_update BEFORE UPDATE OF journey_id, actor_id, camera_id, zone_id, query_start_epoch_ms, query_end_epoch_ms, idempotency_key, request_sha256, created_epoch_ms ON journeys
BEGIN SELECT RAISE(ABORT, 'saved_query_is_immutable'); END;
CREATE TRIGGER journeys_baseline_no_change BEFORE UPDATE OF server_snapshot_json ON journeys
WHEN json_extract(OLD.server_snapshot_json, '$.baseline') IS NOT json_extract(NEW.server_snapshot_json, '$.baseline')
BEGIN SELECT RAISE(ABORT, 'journey_baseline_is_immutable'); END;
CREATE TRIGGER journeys_version_guard BEFORE UPDATE OF review_version, server_snapshot_json, decision_status, updated_epoch_ms ON journeys
WHEN NOT EXISTS (
    SELECT 1 FROM review_actions
    WHERE journey_id = OLD.journey_id
      AND from_review_version = OLD.review_version
      AND to_review_version = NEW.review_version
      AND before_snapshot_json = OLD.server_snapshot_json
      AND after_snapshot_json = NEW.server_snapshot_json
      AND created_epoch_ms = NEW.updated_epoch_ms
)
BEGIN SELECT RAISE(ABORT, 'journey_update_requires_review_action'); END;
CREATE TRIGGER review_actions_no_update BEFORE UPDATE ON review_actions
BEGIN SELECT RAISE(ABORT, 'review_action_is_append_only'); END;
CREATE TRIGGER review_actions_no_delete BEFORE DELETE ON review_actions
BEGIN SELECT RAISE(ABORT, 'review_action_is_append_only'); END;
CREATE TRIGGER cleanup_logs_no_update BEFORE UPDATE ON retention_cleanup_logs
BEGIN SELECT RAISE(ABORT, 'cleanup_log_is_append_only'); END;
CREATE TRIGGER cleanup_logs_no_delete BEFORE DELETE ON retention_cleanup_logs
BEGIN SELECT RAISE(ABORT, 'cleanup_log_is_append_only'); END;
