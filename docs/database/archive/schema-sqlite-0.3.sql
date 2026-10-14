-- ParTrace Vision · 驿站追迹 · 最小数据库设计 0.3-mvp-draft
-- 依据：技术方案 r51、PRD r230；用户于 2026-10-13 补入语义向量及最小聊天/Agent 分析历史：9 表、68 字段。
-- SQLite 仅用于本地 DDL/约束验证；不是现有数据库迁移，不锁定生产引擎。
-- 首版输入：固定摄像头/区域的一段完整连续预录视频，以及一份完整分析结果包。
-- camera_id/config_version/zone_id 引用仓库中不可覆盖的配置，不另建配置表。
-- source_mode=sdk 仅允许已验证取得的连续视频；SDK 原生事件、离散帧包暂不导入。
-- 录像只发布一次：完整包在单事务写入；失败回滚。重算或重新校时须换 recording_id。
-- recording_id + manifest_sha256 在服务端处理重试：同 ID 同内容返回原结果，异内容冲突。
-- 媒体文件不入库；storage_key 为服务端存储键，available 须先核实真实文件。
-- started_at 只有 UTC 起点得到验证才填写；NULL 不参与真实日期命中。
-- created_at/started_at/finished_at 统一存 Unix UTC 毫秒时间戳（INTEGER）；API 转为 ISO 8601 UTC 字符串。
-- 子对象时间只存视频偏移毫秒；UTC = 录像已验证起点 + 偏移，不重复存三套时钟。
-- track_key 只在同一 recording 内有效；失去跟踪后换 key，关键帧点间默认未知。
-- 一个 review 只针对一个 event，引用该事件全部固定关键帧；不做多事件分组或复核编辑。
-- review_id 是客户端重试键；服务端比较已有规范化请求内容，不再新增幂等键列。
-- 向量首版编码事件/关键帧文字描述；模型和生产向量引擎未选，JSON 列只验证结构，不提供 ANN 索引。
-- 向量通过 event_id 回到原事件；带 evidence_id 时必须属于同一事件，候选描述不升级事件观测状态。
-- 只在 model_name/model_version/dimensions 相同的向量空间计算相似度；模型版本须标识权重及编码预处理。
-- content_sha256 由服务端对规范化描述及事件/帧定位计算；本 DDL 仅检查摘要格式，不证明摘要正确。
-- 同源同模型版本重试复用已有向量；重算产生新 ID，禁止静默覆盖，媒体缺失仍保留向量和来源引用。
-- 一个 AI 会话归属一个用户和一份录像；用户消息先保存，运行再关联该消息，完成后保存完整助手答复。
-- running/stopped/failed 的临时摘要不存聊天正文；每个 completed 运行至多一条完整助手答复。
-- Agent 运行保存确认后的 query/query_text/steps、模型配置版本及引用；不保存密钥或自动人工核查结论。
-- references_json 的事件/帧 ID 是逻辑引用：服务验证完整形状、同录像归属及证据状态，SQL 不伪装 JSON 外键。
PRAGMA foreign_keys = ON;

-- 用户表示使用 App 的账号，不是监控中的人物；鉴权方式尚待确认。
CREATE TABLE users (
    user_id TEXT PRIMARY KEY NOT NULL,
    display_name TEXT NOT NULL,
    role TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'active',
    created_at INTEGER NOT NULL,
    CONSTRAINT ck_user_name CHECK (length(trim(user_id)) > 0 AND length(trim(display_name)) > 0),
    CONSTRAINT ck_user_role CHECK (role IN ('admin', 'viewer')),
    CONSTRAINT ck_user_status CHECK (status IN ('active', 'disabled')),
    CONSTRAINT ck_user_created CHECK (created_at >= 0)
);

CREATE TABLE recordings (
    recording_id TEXT PRIMARY KEY NOT NULL,
    camera_id TEXT NOT NULL,
    config_version TEXT NOT NULL,
    source_mode TEXT NOT NULL,
    started_at INTEGER,
    duration_ms INTEGER NOT NULL,
    manifest_sha256 TEXT NOT NULL,
    storage_key TEXT,
    media_status TEXT NOT NULL DEFAULT 'missing',
    created_at INTEGER NOT NULL,
    CONSTRAINT ck_recording_config CHECK (length(trim(camera_id)) > 0 AND length(trim(config_version)) > 0),
    CONSTRAINT ck_recording_source CHECK (source_mode IN ('prerecorded', 'sdk', 'mock')),
    CONSTRAINT ck_recording_time CHECK (started_at IS NULL OR started_at >= 0),
    CONSTRAINT ck_recording_duration CHECK (duration_ms > 0),
    CONSTRAINT ck_recording_manifest CHECK (length(manifest_sha256) = 64 AND manifest_sha256 NOT GLOB '*[^0-9a-f]*'),
    CONSTRAINT ck_recording_media CHECK (media_status IN ('available', 'missing')),
    CONSTRAINT ck_recording_storage CHECK ((storage_key IS NULL OR length(trim(storage_key)) > 0) AND (media_status <> 'available' OR storage_key IS NOT NULL)),
    CONSTRAINT ck_recording_created CHECK (created_at >= 0)
);

CREATE TABLE events (
    event_id TEXT PRIMARY KEY NOT NULL,
    recording_id TEXT NOT NULL,
    zone_id TEXT NOT NULL,
    track_key TEXT,
    event_type TEXT NOT NULL,
    observation_status TEXT NOT NULL DEFAULT 'candidate',
    start_offset_ms INTEGER NOT NULL,
    end_offset_ms INTEGER NOT NULL,
    detection_source TEXT NOT NULL,
    summary TEXT,
    CONSTRAINT fk_event_recording FOREIGN KEY (recording_id) REFERENCES recordings (recording_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_event_zone CHECK (length(trim(zone_id)) > 0),
    CONSTRAINT ck_event_track CHECK (track_key IS NULL OR length(trim(track_key)) > 0),
    CONSTRAINT ck_event_type CHECK (event_type IN ('enter_zone', 'dwell', 'shelf_interaction', 'leave_zone')),
    CONSTRAINT ck_event_observation CHECK (observation_status IN ('observed', 'candidate')),
    CONSTRAINT ck_event_offsets CHECK (start_offset_ms >= 0 AND end_offset_ms >= start_offset_ms),
    CONSTRAINT ck_event_source CHECK (detection_source IN ('automatic', 'manual', 'mock'))
);

CREATE TABLE evidence (
    evidence_id TEXT PRIMARY KEY NOT NULL,
    event_id TEXT NOT NULL,
    offset_ms INTEGER NOT NULL,
    bbox_json TEXT,
    storage_key TEXT,
    media_status TEXT NOT NULL DEFAULT 'missing',
    CONSTRAINT uq_evidence_event UNIQUE (evidence_id, event_id),
    CONSTRAINT fk_evidence_event FOREIGN KEY (event_id) REFERENCES events (event_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_evidence_offset CHECK (offset_ms >= 0),
    CONSTRAINT ck_evidence_bbox CHECK (
        bbox_json IS NULL OR (
            json_valid(bbox_json) AND json_type(bbox_json) = 'array' AND json_array_length(bbox_json) = 4
            AND json_type(bbox_json, '$[0]') IN ('integer', 'real')
            AND json_type(bbox_json, '$[1]') IN ('integer', 'real')
            AND json_type(bbox_json, '$[2]') IN ('integer', 'real')
            AND json_type(bbox_json, '$[3]') IN ('integer', 'real')
            AND json_extract(bbox_json, '$[0]') >= 0 AND json_extract(bbox_json, '$[1]') >= 0
            AND json_extract(bbox_json, '$[2]') > 0 AND json_extract(bbox_json, '$[3]') > 0
            AND json_extract(bbox_json, '$[0]') + json_extract(bbox_json, '$[2]') <= 1
            AND json_extract(bbox_json, '$[1]') + json_extract(bbox_json, '$[3]') <= 1
        )
    ),
    CONSTRAINT ck_evidence_media CHECK (media_status IN ('available', 'missing')),
    CONSTRAINT ck_evidence_storage CHECK ((storage_key IS NULL OR length(trim(storage_key)) > 0) AND (media_status <> 'available' OR storage_key IS NOT NULL))
);

CREATE TABLE event_embeddings (
    embedding_id TEXT PRIMARY KEY NOT NULL,
    event_id TEXT NOT NULL,
    evidence_id TEXT,
    source_text TEXT NOT NULL,
    embedding_json TEXT NOT NULL,
    model_name TEXT NOT NULL,
    model_version TEXT NOT NULL,
    dimensions INTEGER NOT NULL,
    content_sha256 TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    CONSTRAINT fk_embedding_event FOREIGN KEY (event_id) REFERENCES events (event_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_embedding_evidence_event FOREIGN KEY (evidence_id, event_id) REFERENCES evidence (evidence_id, event_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_embedding_identity CHECK (length(trim(embedding_id)) > 0 AND length(trim(event_id)) > 0 AND (evidence_id IS NULL OR length(trim(evidence_id)) > 0)),
    CONSTRAINT ck_embedding_source CHECK (length(trim(source_text)) > 0),
    CONSTRAINT ck_embedding_model CHECK (length(trim(model_name)) > 0 AND length(trim(model_version)) > 0),
    CONSTRAINT ck_embedding_shape CHECK (
        json_valid(embedding_json) AND json_type(embedding_json) = 'array'
        AND dimensions > 0 AND json_array_length(embedding_json) = dimensions
    ),
    CONSTRAINT ck_embedding_content CHECK (length(content_sha256) = 64 AND content_sha256 NOT GLOB '*[^0-9a-f]*'),
    CONSTRAINT ck_embedding_created CHECK (created_at >= 0)
);

CREATE TABLE review_records (
    review_id TEXT PRIMARY KEY NOT NULL,
    event_id TEXT NOT NULL,
    query_json TEXT NOT NULL,
    decision TEXT NOT NULL,
    note TEXT,
    reviewer_id TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    CONSTRAINT fk_review_event FOREIGN KEY (event_id) REFERENCES events (event_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_review_user FOREIGN KEY (reviewer_id) REFERENCES users (user_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_review_query CHECK (json_valid(query_json) AND json_type(query_json) = 'object'),
    CONSTRAINT ck_review_decision CHECK (decision IN ('confirmed', 'dismissed', 'uncertain')),
    CONSTRAINT ck_review_reviewer CHECK (length(trim(reviewer_id)) > 0),
    CONSTRAINT ck_review_created CHECK (created_at >= 0)
);

CREATE TABLE conversations (
    conversation_id TEXT PRIMARY KEY NOT NULL,
    user_id TEXT NOT NULL,
    recording_id TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    CONSTRAINT fk_conversation_user FOREIGN KEY (user_id) REFERENCES users (user_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_conversation_recording FOREIGN KEY (recording_id) REFERENCES recordings (recording_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_conversation_id CHECK (length(trim(conversation_id)) > 0),
    CONSTRAINT ck_conversation_created CHECK (created_at >= 0)
);

-- 先插入 run_id=NULL 的 user 消息，再创建引用它的运行；循环外键无须先插入助手消息。
CREATE TABLE messages (
    message_id TEXT PRIMARY KEY NOT NULL,
    conversation_id TEXT NOT NULL,
    role TEXT NOT NULL,
    content TEXT NOT NULL,
    run_id TEXT,
    created_at INTEGER NOT NULL,
    CONSTRAINT fk_message_conversation FOREIGN KEY (conversation_id) REFERENCES conversations (conversation_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_message_run FOREIGN KEY (run_id) REFERENCES agent_runs (run_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_message_content CHECK (length(trim(message_id)) > 0 AND length(trim(content)) > 0),
    CONSTRAINT ck_message_role_run CHECK ((role = 'user' AND run_id IS NULL) OR (role = 'assistant' AND run_id IS NOT NULL)),
    CONSTRAINT ck_message_created CHECK (created_at >= 0)
);

CREATE TABLE agent_runs (
    run_id TEXT PRIMARY KEY NOT NULL,
    conversation_id TEXT NOT NULL,
    input_message_id TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'running',
    plan_json TEXT NOT NULL,
    references_json TEXT NOT NULL DEFAULT '[]',
    model_key TEXT NOT NULL,
    error_json TEXT,
    created_at INTEGER NOT NULL,
    finished_at INTEGER,
    CONSTRAINT fk_run_conversation FOREIGN KEY (conversation_id) REFERENCES conversations (conversation_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_run_input_message FOREIGN KEY (input_message_id) REFERENCES messages (message_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_run_identity CHECK (length(trim(run_id)) > 0 AND length(trim(model_key)) > 0),
    CONSTRAINT ck_run_status CHECK (status IN ('running', 'completed', 'stopped', 'failed')),
    CONSTRAINT ck_run_plan CHECK (COALESCE(
        json_valid(plan_json) AND json_type(plan_json) = 'object'
        AND json_type(plan_json, '$.query') = 'object'
        AND json_type(plan_json, '$.query_text') = 'text'
        AND length(trim(json_extract(plan_json, '$.query_text'))) > 0
        AND json_type(plan_json, '$.steps') = 'array'
        AND json_type(plan_json, '$.query.recording_id') = 'text'
        AND length(trim(json_extract(plan_json, '$.query.recording_id'))) > 0
        AND json_type(plan_json, '$.query.camera_id') = 'text'
        AND length(trim(json_extract(plan_json, '$.query.camera_id'))) > 0
        AND json_type(plan_json, '$.query.zone_id') = 'text'
        AND length(trim(json_extract(plan_json, '$.query.zone_id'))) > 0
        AND json_type(plan_json, '$.query.start_offset_ms') = 'integer'
        AND json_extract(plan_json, '$.query.start_offset_ms') >= 0
        AND json_type(plan_json, '$.query.end_offset_ms') = 'integer'
        AND json_extract(plan_json, '$.query.end_offset_ms') > json_extract(plan_json, '$.query.start_offset_ms'),
        0
    )),
    CONSTRAINT ck_run_references CHECK (json_valid(references_json) AND json_type(references_json) = 'array'),
    CONSTRAINT ck_run_time CHECK (created_at >= 0 AND (finished_at IS NULL OR finished_at >= created_at)),
    CONSTRAINT ck_run_terminal CHECK (
        (status = 'running' AND finished_at IS NULL AND error_json IS NULL)
        OR (status IN ('completed', 'stopped') AND finished_at IS NOT NULL AND error_json IS NULL)
        OR (status = 'failed' AND finished_at IS NOT NULL AND error_json IS NOT NULL)
    ),
    CONSTRAINT ck_run_error CHECK (error_json IS NULL OR (json_valid(error_json) AND json_type(error_json) = 'object'))
);

-- 主键已有索引；以下仅覆盖首版按摄像头、区域、局部轨迹和事件读取的查询。
CREATE INDEX idx_recordings_camera_time ON recordings (camera_id, started_at);
CREATE INDEX idx_events_recording_zone_time ON events (recording_id, zone_id, start_offset_ms);
CREATE INDEX idx_events_recording_track_time ON events (recording_id, track_key, start_offset_ms);
CREATE INDEX idx_evidence_event_offset ON evidence (event_id, offset_ms);
CREATE INDEX idx_embeddings_event ON event_embeddings (event_id);
CREATE INDEX idx_embeddings_model_space ON event_embeddings (model_name, model_version, dimensions);
-- 两条部分唯一索引同时覆盖文字描述和带帧描述，避免 SQL NULL 的重复豁免。
CREATE UNIQUE INDEX uq_embeddings_event_text_source ON event_embeddings (event_id, model_name, model_version, content_sha256) WHERE evidence_id IS NULL;
CREATE UNIQUE INDEX uq_embeddings_evidence_source ON event_embeddings (event_id, evidence_id, model_name, model_version, content_sha256) WHERE evidence_id IS NOT NULL;
CREATE INDEX idx_reviews_event_created ON review_records (event_id, created_at);
CREATE INDEX idx_reviews_reviewer_created ON review_records (reviewer_id, created_at);
CREATE INDEX idx_conversations_user_created ON conversations (user_id, created_at);
CREATE INDEX idx_messages_conversation_created ON messages (conversation_id, created_at);
CREATE UNIQUE INDEX uq_messages_assistant_run ON messages (run_id) WHERE role = 'assistant';
CREATE INDEX idx_runs_conversation_created ON agent_runs (conversation_id, created_at);
CREATE INDEX idx_runs_status_created ON agent_runs (status, created_at);

-- 运行只消费本会话的 user 输入；确认范围不得换到会话之外的录像。
CREATE TRIGGER run_input_and_scope
BEFORE INSERT ON agent_runs
BEGIN
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1 FROM messages
        WHERE message_id = NEW.input_message_id AND conversation_id = NEW.conversation_id AND role = 'user'
    ) THEN RAISE(ABORT, 'run input must be a user message in the same conversation') END;
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1 FROM conversations
        WHERE conversation_id = NEW.conversation_id
            AND recording_id = json_extract(NEW.plan_json, '$.query.recording_id')
    ) THEN RAISE(ABORT, 'run query must use the conversation recording') END;
END;

CREATE TRIGGER assistant_message_completed_run
BEFORE INSERT ON messages
WHEN NEW.role = 'assistant'
BEGIN
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1 FROM agent_runs
        WHERE run_id = NEW.run_id AND conversation_id = NEW.conversation_id AND status = 'completed'
    ) THEN RAISE(ABORT, 'assistant reply requires a completed run in the same conversation') END;
END;

-- 只校验本库能证明的时间边界；真实时间校准、文件与配置存在性由导入器验证。
CREATE TRIGGER event_offset_in_recording
BEFORE INSERT ON events
WHEN NEW.end_offset_ms > (SELECT duration_ms FROM recordings WHERE recording_id = NEW.recording_id)
BEGIN
    SELECT RAISE(ABORT, 'event offsets exceed recording duration');
END;

CREATE TRIGGER evidence_offset_in_recording
BEFORE INSERT ON evidence
WHEN NEW.offset_ms > (
    SELECT r.duration_ms FROM recordings r JOIN events e ON e.recording_id = r.recording_id
    WHERE e.event_id = NEW.event_id
)
BEGIN
    SELECT RAISE(ABORT, 'evidence offset exceeds recording duration');
END;

-- JSON 布尔/null/字符串/嵌套值不是向量维度；JSON 合法仍可能包含溢出至无穷的数字。
CREATE TRIGGER embedding_numeric_finite_nonzero
BEFORE INSERT ON event_embeddings
BEGIN
    SELECT CASE WHEN EXISTS (
        SELECT 1 FROM json_each(NEW.embedding_json)
        WHERE type NOT IN ('integer', 'real')
            OR (value - value) IS NULL
    ) THEN RAISE(ABORT, 'embedding values must be finite numbers') END;
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1 FROM json_each(NEW.embedding_json) WHERE value <> 0
    ) THEN RAISE(ABORT, 'embedding must not be all zero') END;
END;

-- 完整分析发布后不再追加 Event/Evidence；该发布边界由单事务导入服务执行。
-- 不覆盖已有时间、来源、配置、事件事实或关键帧定位；只更新媒体可用状态。
CREATE TRIGGER recording_facts_immutable
BEFORE UPDATE ON recordings
WHEN NEW.recording_id IS NOT OLD.recording_id
    OR NEW.camera_id IS NOT OLD.camera_id
    OR NEW.config_version IS NOT OLD.config_version
    OR NEW.source_mode IS NOT OLD.source_mode
    OR NEW.started_at IS NOT OLD.started_at
    OR NEW.duration_ms IS NOT OLD.duration_ms
    OR NEW.manifest_sha256 IS NOT OLD.manifest_sha256
    OR NEW.storage_key IS NOT OLD.storage_key
    OR NEW.created_at IS NOT OLD.created_at
BEGIN
    SELECT RAISE(ABORT, 'recording facts are immutable; create a new recording');
END;

CREATE TRIGGER event_facts_immutable
BEFORE UPDATE ON events
BEGIN
    SELECT RAISE(ABORT, 'event facts are immutable');
END;

CREATE TRIGGER evidence_location_immutable
BEFORE UPDATE ON evidence
WHEN NEW.evidence_id IS NOT OLD.evidence_id
    OR NEW.event_id IS NOT OLD.event_id
    OR NEW.offset_ms IS NOT OLD.offset_ms
    OR NEW.bbox_json IS NOT OLD.bbox_json
    OR NEW.storage_key IS NOT OLD.storage_key
BEGIN
    SELECT RAISE(ABORT, 'evidence location is immutable');
END;

CREATE TRIGGER review_append_only
BEFORE UPDATE ON review_records
BEGIN
    SELECT RAISE(ABORT, 'review records are append-only');
END;

CREATE TRIGGER embedding_facts_immutable
BEFORE UPDATE ON event_embeddings
BEGIN
    SELECT RAISE(ABORT, 'embeddings are immutable; reuse an existing row or create a new embedding');
END;

CREATE TRIGGER conversation_ownership_immutable
BEFORE UPDATE ON conversations
BEGIN
    SELECT RAISE(ABORT, 'conversation ownership and scope are immutable');
END;

CREATE TRIGGER message_append_only
BEFORE UPDATE ON messages
BEGIN
    SELECT RAISE(ABORT, 'messages are append-only');
END;

CREATE TRIGGER run_facts_immutable
BEFORE UPDATE ON agent_runs
WHEN NEW.run_id IS NOT OLD.run_id
    OR NEW.conversation_id IS NOT OLD.conversation_id
    OR NEW.input_message_id IS NOT OLD.input_message_id
    OR NEW.plan_json IS NOT OLD.plan_json
    OR NEW.model_key IS NOT OLD.model_key
    OR NEW.created_at IS NOT OLD.created_at
BEGIN
    SELECT RAISE(ABORT, 'run input, confirmed plan and model configuration are immutable');
END;

CREATE TRIGGER run_terminal_immutable
BEFORE UPDATE ON agent_runs
WHEN OLD.status <> 'running'
BEGIN
    SELECT RAISE(ABORT, 'terminal runs are immutable');
END;

-- 媒体丢失仍保留全部索引和人工记录，首版不提供删除历史记录的接口。
CREATE TRIGGER recording_keep_history BEFORE DELETE ON recordings
BEGIN SELECT RAISE(ABORT, 'keep recording history'); END;
CREATE TRIGGER event_keep_history BEFORE DELETE ON events
BEGIN SELECT RAISE(ABORT, 'keep event history'); END;
CREATE TRIGGER evidence_keep_history BEFORE DELETE ON evidence
BEGIN SELECT RAISE(ABORT, 'keep evidence history'); END;
CREATE TRIGGER review_keep_history BEFORE DELETE ON review_records
BEGIN SELECT RAISE(ABORT, 'keep review history'); END;
CREATE TRIGGER embedding_keep_history BEFORE DELETE ON event_embeddings
BEGIN SELECT RAISE(ABORT, 'keep embedding history'); END;
CREATE TRIGGER conversation_keep_history BEFORE DELETE ON conversations
BEGIN SELECT RAISE(ABORT, 'keep conversation history'); END;
CREATE TRIGGER message_keep_history BEFORE DELETE ON messages
BEGIN SELECT RAISE(ABORT, 'keep message history'); END;
CREATE TRIGGER run_keep_history BEFORE DELETE ON agent_runs
BEGIN SELECT RAISE(ABORT, 'keep run history'); END;
