-- ParTrace Vision · 驿站追迹 · 最小数据库设计 0.3-mvp-draft
-- 依据：技术方案 r51、PRD r230；用户于 2026-10-13 选择 PostgreSQL + pgvector、确认包裹入出库并取消会话固定录像：11 表、77 字段；2026-10-16 events 增加可空 shelf_level（货架层级自下而上 1–20，未知为 NULL）、zone_id 改为可空且默认 'E3'（zone_id 表示货架编号，不表示层级；层级由 shelf_level 表示）：11 表、78 字段。
-- PostgreSQL 17 / pgvector 0.8.7 新空库 DDL 草案；不是既有库迁移，也不代表数据库或后端已部署。
-- SQLite 历史验证稿已完整保存在 archive/schema-sqlite-0.3.sql，不再作为当前生产选型。
-- 首版输入：固定摄像头/区域的一段完整连续预录视频，以及一份完整分析结果包。
-- camera_id/config_version/zone_id 引用仓库中不可覆盖的配置，不另建配置表。
-- source_mode=sdk 仅允许已验证取得的连续视频；SDK 原生事件、离散帧包暂不导入。
-- 录像只发布一次：完整包在单事务写入；失败回滚。重算或重新校时须换 recording_id。
-- recording_id + manifest_sha256 在服务端处理重试：同 ID 同内容返回原结果，异内容冲突。
-- 媒体文件不入库；storage_key 为服务端存储键，available 须先核实真实文件。
-- started_at 只有 UTC 起点得到验证才填写；NULL 不参与真实日期命中。
-- 绝对时间统一 TIMESTAMPTZ；服务绑定带时区参数，API 输出 ISO 8601 UTC，不以本地时区补全未知时间。
-- 子对象时间只存 BIGINT 视频偏移毫秒；UTC = 录像已验证起点 + 偏移，不重复存三套时钟。
-- events.zone_id 表示货架编号（如 E3），不表示层级；层级由 shelf_level 表示，未知货架写 NULL，缺省填 'E3'。
-- track_key 只在同一 recording 内有效；失去跟踪后换 key，关键帧点间默认未知。
-- 一个 review 只针对一个 event，引用该事件全部固定关键帧；不做多事件分组或复核编辑。
-- review_id 是客户端重试键；服务端比较已有规范化请求内容，不再新增幂等键列。
-- 首版编码事件/关键帧描述文本；模型/维数未冻结，使用无定长 vector，维数 1..16000 且等于 dimensions。
-- vector 输入类型自身拒绝 NaN/Infinity，CHECK 拒绝全零；API 数组映射 embedding，而非旧 embedding_json。
-- 首版先按授权、录像、区域、时间及模型空间过滤，再用 <=> 精确余弦检索；暂不创建跨模型 ANN 索引。
-- 向量通过 event_id 回到原事件；带 evidence_id 时必须属于同一事件，候选描述不升级事件观测状态。
-- 只在 model_name/model_version/dimensions 相同的空间比较；版本须标识权重和编码预处理配置。
-- content_sha256 由服务端对规范化描述及事件/帧定位计算；本 DDL 仅检查摘要格式，不证明摘要正确。
-- 同源同模型版本重试复用已有向量；新描述/模型采用新 ID，禁止覆盖，媒体缺失仍保留向量和引用。
-- 一个 AI 会话只归属一个用户；每次运行保存独立的单录像 query，同会话的不同运行可查询不同录像。
-- 用户消息先保存，运行再关联同会话消息，完成后保存完整助手答复；视频授权按本次运行由服务校验。
-- running/stopped/failed 临时摘要不存聊天正文；每个 completed 运行至多一条完整助手答复。
-- Agent 运行保存确认后的 query/query_text/steps、模型配置版本及引用；不保存密钥或自动人工核查结论。
-- JSON 字段统一 JSONB；references_json 仍是逻辑引用，由服务验证成员形状、同录像归属和当前证据状态。
-- parcel_no 是唯一文本单号并作为二维码内容，保留前导零；不保存二维码图片或重复的当前状态。
-- 包裹状态按最后一条 sequence_no 派生；首条入库、状态交替、序号分配和幂等比较由服务事务锁定包裹后验证。
-- SQL 只保证流水序号为正且同包裹唯一，不保证连续或动作交替；出库 zone_id 由服务保留最近入库位置。
-- 入出库操作人和 created_at 由服务根据启用管理员身份填写；Agent 仅可读取。
-- 事务失败整体回滚；建库账户须已具备 vector 扩展安装权限，本文件不创建账户或修改权限。
BEGIN;
SET LOCAL TIME ZONE 'UTC';
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE users (
    user_id TEXT PRIMARY KEY NOT NULL,
    display_name TEXT NOT NULL,
    role TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'active',
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_user_name CHECK (length(trim(user_id)) > 0 AND length(trim(display_name)) > 0),
    CONSTRAINT ck_user_role CHECK (role IN ('admin', 'viewer')),
    CONSTRAINT ck_user_status CHECK (status IN ('active', 'disabled')),
    CONSTRAINT ck_user_created CHECK (isfinite(created_at) AND created_at >= TIMESTAMPTZ '1970-01-01 00:00:00+00')
);

CREATE TABLE recordings (
    recording_id TEXT PRIMARY KEY NOT NULL,
    camera_id TEXT NOT NULL,
    config_version TEXT NOT NULL,
    source_mode TEXT NOT NULL,
    started_at TIMESTAMPTZ,
    duration_ms BIGINT NOT NULL,
    manifest_sha256 TEXT NOT NULL,
    storage_key TEXT,
    media_status TEXT NOT NULL DEFAULT 'missing',
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_recording_config CHECK (length(trim(camera_id)) > 0 AND length(trim(config_version)) > 0),
    CONSTRAINT ck_recording_source CHECK (source_mode IN ('prerecorded', 'sdk', 'mock')),
    CONSTRAINT ck_recording_time CHECK (started_at IS NULL OR (isfinite(started_at) AND started_at >= TIMESTAMPTZ '1970-01-01 00:00:00+00')),
    CONSTRAINT ck_recording_duration CHECK (duration_ms > 0),
    CONSTRAINT ck_recording_manifest CHECK (manifest_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_recording_media CHECK (media_status IN ('available', 'missing')),
    CONSTRAINT ck_recording_storage CHECK ((storage_key IS NULL OR length(trim(storage_key)) > 0) AND (media_status <> 'available' OR storage_key IS NOT NULL)),
    CONSTRAINT ck_recording_created CHECK (isfinite(created_at) AND created_at >= TIMESTAMPTZ '1970-01-01 00:00:00+00')
);

CREATE TABLE events (
    event_id TEXT PRIMARY KEY NOT NULL,
    recording_id TEXT NOT NULL,
    zone_id TEXT DEFAULT 'E3',
    track_key TEXT,
    event_type TEXT NOT NULL,
    shelf_level INTEGER,
    observation_status TEXT NOT NULL DEFAULT 'candidate',
    start_offset_ms BIGINT NOT NULL,
    end_offset_ms BIGINT NOT NULL,
    detection_source TEXT NOT NULL,
    summary TEXT,
    CONSTRAINT fk_event_recording FOREIGN KEY (recording_id) REFERENCES recordings (recording_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_event_zone CHECK (zone_id IS NULL OR length(trim(zone_id)) > 0),
    CONSTRAINT ck_event_track CHECK (track_key IS NULL OR length(trim(track_key)) > 0),
    CONSTRAINT ck_event_type CHECK (event_type IN ('enter_zone', 'dwell', 'shelf_interaction', 'leave_zone')),
    CONSTRAINT ck_event_shelf CHECK (shelf_level IS NULL OR shelf_level BETWEEN 1 AND 20),
    CONSTRAINT ck_event_observation CHECK (observation_status IN ('observed', 'candidate')),
    CONSTRAINT ck_event_offsets CHECK (start_offset_ms >= 0 AND end_offset_ms >= start_offset_ms),
    CONSTRAINT ck_event_source CHECK (detection_source IN ('automatic', 'manual', 'mock'))
);

CREATE TABLE evidence (
    evidence_id TEXT PRIMARY KEY NOT NULL,
    event_id TEXT NOT NULL,
    offset_ms BIGINT NOT NULL,
    bbox_json JSONB,
    storage_key TEXT,
    media_status TEXT NOT NULL DEFAULT 'missing',
    CONSTRAINT uq_evidence_event UNIQUE (evidence_id, event_id),
    CONSTRAINT fk_evidence_event FOREIGN KEY (event_id) REFERENCES events (event_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_evidence_offset CHECK (offset_ms >= 0),
    CONSTRAINT ck_evidence_bbox CHECK (
        bbox_json IS NULL OR CASE
            WHEN jsonb_typeof(bbox_json) = 'array' THEN CASE
                WHEN jsonb_array_length(bbox_json) = 4
                    AND jsonb_typeof(bbox_json -> 0) = 'number'
                    AND jsonb_typeof(bbox_json -> 1) = 'number'
                    AND jsonb_typeof(bbox_json -> 2) = 'number'
                    AND jsonb_typeof(bbox_json -> 3) = 'number'
                THEN (bbox_json ->> 0)::numeric >= 0 AND (bbox_json ->> 1)::numeric >= 0
                    AND (bbox_json ->> 2)::numeric > 0 AND (bbox_json ->> 3)::numeric > 0
                    AND (bbox_json ->> 0)::numeric + (bbox_json ->> 2)::numeric <= 1
                    AND (bbox_json ->> 1)::numeric + (bbox_json ->> 3)::numeric <= 1
                ELSE FALSE
            END
            ELSE FALSE
        END
    ),
    CONSTRAINT ck_evidence_media CHECK (media_status IN ('available', 'missing')),
    CONSTRAINT ck_evidence_storage CHECK ((storage_key IS NULL OR length(trim(storage_key)) > 0) AND (media_status <> 'available' OR storage_key IS NOT NULL))
);

CREATE TABLE event_embeddings (
    embedding_id TEXT PRIMARY KEY NOT NULL,
    event_id TEXT NOT NULL,
    evidence_id TEXT,
    source_text TEXT NOT NULL,
    embedding VECTOR NOT NULL,
    model_name TEXT NOT NULL,
    model_version TEXT NOT NULL,
    dimensions INTEGER NOT NULL,
    content_sha256 TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_embedding_event FOREIGN KEY (event_id) REFERENCES events (event_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_embedding_evidence_event FOREIGN KEY (evidence_id, event_id) REFERENCES evidence (evidence_id, event_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_embedding_identity CHECK (length(trim(embedding_id)) > 0 AND length(trim(event_id)) > 0 AND (evidence_id IS NULL OR length(trim(evidence_id)) > 0)),
    CONSTRAINT ck_embedding_source CHECK (length(trim(source_text)) > 0),
    CONSTRAINT ck_embedding_model CHECK (length(trim(model_name)) > 0 AND length(trim(model_version)) > 0),
    CONSTRAINT ck_embedding_shape CHECK (dimensions BETWEEN 1 AND 16000 AND vector_dims(embedding) = dimensions),
    CONSTRAINT ck_embedding_nonzero CHECK (vector_norm(embedding) > 0),
    CONSTRAINT ck_embedding_content CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_embedding_created CHECK (isfinite(created_at) AND created_at >= TIMESTAMPTZ '1970-01-01 00:00:00+00')
);

CREATE TABLE review_records (
    review_id TEXT PRIMARY KEY NOT NULL,
    event_id TEXT NOT NULL,
    query_json JSONB NOT NULL,
    decision TEXT NOT NULL,
    note TEXT,
    reviewer_id TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_review_event FOREIGN KEY (event_id) REFERENCES events (event_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_review_user FOREIGN KEY (reviewer_id) REFERENCES users (user_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_review_query CHECK (jsonb_typeof(query_json) = 'object'),
    CONSTRAINT ck_review_decision CHECK (decision IN ('confirmed', 'dismissed', 'uncertain')),
    CONSTRAINT ck_review_reviewer CHECK (length(trim(reviewer_id)) > 0),
    CONSTRAINT ck_review_created CHECK (isfinite(created_at) AND created_at >= TIMESTAMPTZ '1970-01-01 00:00:00+00')
);

CREATE TABLE conversations (
    conversation_id TEXT PRIMARY KEY NOT NULL,
    user_id TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_conversation_user FOREIGN KEY (user_id) REFERENCES users (user_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_conversation_id CHECK (length(trim(conversation_id)) > 0),
    CONSTRAINT ck_conversation_created CHECK (isfinite(created_at) AND created_at >= TIMESTAMPTZ '1970-01-01 00:00:00+00')
);

-- 先插入 run_id=NULL 的 user 消息，再创建引用它的运行；循环外键无须先插入助手消息。
CREATE TABLE messages (
    message_id TEXT PRIMARY KEY NOT NULL,
    conversation_id TEXT NOT NULL,
    role TEXT NOT NULL,
    content TEXT NOT NULL,
    run_id TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_message_conversation FOREIGN KEY (conversation_id) REFERENCES conversations (conversation_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_message_content CHECK (length(trim(message_id)) > 0 AND length(trim(content)) > 0),
    CONSTRAINT ck_message_role_run CHECK ((role = 'user' AND run_id IS NULL) OR (role = 'assistant' AND run_id IS NOT NULL)),
    CONSTRAINT ck_message_created CHECK (isfinite(created_at) AND created_at >= TIMESTAMPTZ '1970-01-01 00:00:00+00')
);

CREATE TABLE runs (
    run_id TEXT PRIMARY KEY NOT NULL,
    conversation_id TEXT NOT NULL,
    input_message_id TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'running',
    plan_json JSONB NOT NULL,
    references_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    cards_json JSONB,
    model_key TEXT NOT NULL,
    error_json JSONB,
    created_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    CONSTRAINT fk_run_conversation FOREIGN KEY (conversation_id) REFERENCES conversations (conversation_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_run_input_message FOREIGN KEY (input_message_id) REFERENCES messages (message_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_run_identity CHECK (length(trim(run_id)) > 0 AND length(trim(model_key)) > 0),
    CONSTRAINT ck_run_status CHECK (status IN ('running', 'completed', 'stopped', 'failed')),
    CONSTRAINT ck_run_plan CHECK (
        CASE WHEN jsonb_typeof(plan_json) = 'object'
            AND jsonb_typeof(plan_json -> 'query') = 'object'
            AND jsonb_typeof(plan_json -> 'query_text') = 'string'
            AND length(trim(plan_json ->> 'query_text')) > 0
            AND jsonb_typeof(plan_json -> 'steps') = 'array'
            AND jsonb_typeof(plan_json #> '{query,recording_id}') = 'string'
            AND length(trim(plan_json #>> '{query,recording_id}')) > 0
            AND jsonb_typeof(plan_json #> '{query,camera_id}') = 'string'
            AND length(trim(plan_json #>> '{query,camera_id}')) > 0
            AND jsonb_typeof(plan_json #> '{query,zone_id}') = 'string'
            AND length(trim(plan_json #>> '{query,zone_id}')) > 0
            AND jsonb_typeof(plan_json #> '{query,start_offset_ms}') = 'number'
            AND jsonb_typeof(plan_json #> '{query,end_offset_ms}') = 'number'
        THEN COALESCE(
            (plan_json #>> '{query,start_offset_ms}') ~ '^[0-9]+$'
            AND (plan_json #>> '{query,end_offset_ms}') ~ '^[0-9]+$'
            AND (plan_json #>> '{query,start_offset_ms}')::numeric BETWEEN 0 AND 9223372036854775807
            AND (plan_json #>> '{query,end_offset_ms}')::numeric <= 9223372036854775807
            AND (plan_json #>> '{query,end_offset_ms}')::numeric > (plan_json #>> '{query,start_offset_ms}')::numeric,
            FALSE
        )
        ELSE FALSE END
    ),
    CONSTRAINT ck_run_references CHECK (jsonb_typeof(references_json) = 'array'),
    CONSTRAINT ck_run_cards CHECK (
        cards_json IS NULL OR (
            jsonb_typeof(cards_json) = 'object'
            AND cards_json ->> 'version' = '1'
            AND jsonb_typeof(cards_json -> 'cards') = 'array'
            AND jsonb_typeof(cards_json -> 'query') = 'object'
        )
    ),
    CONSTRAINT ck_run_time CHECK (isfinite(created_at) AND created_at >= TIMESTAMPTZ '1970-01-01 00:00:00+00' AND (finished_at IS NULL OR (isfinite(finished_at) AND finished_at >= created_at))),
    CONSTRAINT ck_run_terminal CHECK (
        (status = 'running' AND finished_at IS NULL AND error_json IS NULL AND cards_json IS NULL)
        OR (status = 'completed' AND finished_at IS NOT NULL AND error_json IS NULL AND cards_json IS NOT NULL)
        OR (status = 'stopped' AND finished_at IS NOT NULL AND error_json IS NULL AND cards_json IS NULL)
        OR (status = 'failed' AND finished_at IS NOT NULL AND error_json IS NOT NULL AND cards_json IS NULL)
    ),
    CONSTRAINT ck_run_error CHECK (error_json IS NULL OR jsonb_typeof(error_json) = 'object')
);

CREATE TABLE parcels (
    parcel_id TEXT PRIMARY KEY NOT NULL,
    parcel_no TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_parcel_no UNIQUE (parcel_no),
    CONSTRAINT ck_parcel_identity CHECK (length(trim(parcel_id)) > 0 AND length(trim(parcel_no)) > 0),
    CONSTRAINT ck_parcel_created CHECK (isfinite(created_at) AND created_at >= TIMESTAMPTZ '1970-01-01 00:00:00+00')
);

CREATE TABLE parcel_records (
    record_id TEXT PRIMARY KEY NOT NULL,
    parcel_id TEXT NOT NULL,
    sequence_no BIGINT NOT NULL,
    action TEXT NOT NULL,
    zone_id TEXT,
    operator_id TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_parcel_record_sequence UNIQUE (parcel_id, sequence_no),
    CONSTRAINT fk_parcel_record_parcel FOREIGN KEY (parcel_id) REFERENCES parcels (parcel_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_parcel_record_operator FOREIGN KEY (operator_id) REFERENCES users (user_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_parcel_record_identity CHECK (length(trim(record_id)) > 0 AND length(trim(parcel_id)) > 0 AND length(trim(operator_id)) > 0),
    CONSTRAINT ck_parcel_record_sequence CHECK (sequence_no > 0),
    CONSTRAINT ck_parcel_record_action CHECK (action IN ('inbound', 'outbound')),
    CONSTRAINT ck_parcel_record_zone CHECK ((zone_id IS NULL OR length(trim(zone_id)) > 0) AND (action <> 'inbound' OR zone_id IS NOT NULL)),
    CONSTRAINT ck_parcel_record_created CHECK (isfinite(created_at) AND created_at >= TIMESTAMPTZ '1970-01-01 00:00:00+00')
);

-- messages 与 runs 互相引用；两表创建后补外键，写入仍按 user 消息 → 运行 → completed 答复执行。
ALTER TABLE messages ADD CONSTRAINT fk_message_run
    FOREIGN KEY (run_id) REFERENCES runs (run_id) ON DELETE RESTRICT ON UPDATE RESTRICT;

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
CREATE INDEX idx_runs_conversation_created ON runs (conversation_id, created_at);
CREATE INDEX idx_runs_status_created ON runs (status, created_at);
-- uq_parcel_no 支持扫码精确查询；uq_parcel_record_sequence 同时支持包裹流水顺序及最新记录查询。
CREATE INDEX idx_parcel_records_operator_created ON parcel_records (operator_id, created_at);

-- PostgreSQL 触发器保留首版边界；真实时间校准、权限、配置与文件存在性仍由服务验证。
CREATE FUNCTION partrace_run_input_and_scope() RETURNS trigger
LANGUAGE plpgsql AS $body$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM messages
        WHERE message_id = NEW.input_message_id AND conversation_id = NEW.conversation_id AND role = 'user'
    ) THEN
        RAISE EXCEPTION 'run input must be a user message in the same conversation' USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM recordings
        WHERE recording_id = NEW.plan_json #>> '{query,recording_id}'
    ) THEN
        RAISE EXCEPTION 'run query must refer to an existing recording' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$body$;
CREATE TRIGGER run_input_and_scope BEFORE INSERT ON runs
    FOR EACH ROW EXECUTE FUNCTION partrace_run_input_and_scope();

CREATE FUNCTION partrace_assistant_completed_run() RETURNS trigger
LANGUAGE plpgsql AS $body$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM runs
        WHERE run_id = NEW.run_id AND conversation_id = NEW.conversation_id AND status = 'completed'
    ) THEN
        RAISE EXCEPTION 'assistant reply requires a completed run in the same conversation' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$body$;
CREATE TRIGGER assistant_message_completed_run BEFORE INSERT ON messages
    FOR EACH ROW WHEN (NEW.role = 'assistant') EXECUTE FUNCTION partrace_assistant_completed_run();

CREATE FUNCTION partrace_event_offset_in_recording() RETURNS trigger
LANGUAGE plpgsql AS $body$
BEGIN
    IF NEW.end_offset_ms > (SELECT duration_ms FROM recordings WHERE recording_id = NEW.recording_id) THEN
        RAISE EXCEPTION 'event offsets exceed recording duration' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$body$;
CREATE TRIGGER event_offset_in_recording BEFORE INSERT ON events
    FOR EACH ROW EXECUTE FUNCTION partrace_event_offset_in_recording();

CREATE FUNCTION partrace_evidence_offset_in_recording() RETURNS trigger
LANGUAGE plpgsql AS $body$
BEGIN
    IF NEW.offset_ms > (
        SELECT r.duration_ms FROM recordings r JOIN events e ON e.recording_id = r.recording_id
        WHERE e.event_id = NEW.event_id
    ) THEN
        RAISE EXCEPTION 'evidence offset exceeds recording duration' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$body$;
CREATE TRIGGER evidence_offset_in_recording BEFORE INSERT ON evidence
    FOR EACH ROW EXECUTE FUNCTION partrace_evidence_offset_in_recording();

-- 只允许更新媒体状态；录制来源/时间/配置与关键帧定位均不可覆盖。
CREATE FUNCTION partrace_recording_facts_immutable() RETURNS trigger
LANGUAGE plpgsql AS $body$
BEGIN
    IF ROW(NEW.recording_id, NEW.camera_id, NEW.config_version, NEW.source_mode, NEW.started_at, NEW.duration_ms,
        NEW.manifest_sha256, NEW.storage_key, NEW.created_at)
        IS DISTINCT FROM ROW(OLD.recording_id, OLD.camera_id, OLD.config_version, OLD.source_mode, OLD.started_at, OLD.duration_ms,
        OLD.manifest_sha256, OLD.storage_key, OLD.created_at) THEN
        RAISE EXCEPTION 'recording facts are immutable; create a new recording' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$body$;
CREATE TRIGGER recording_facts_immutable BEFORE UPDATE ON recordings
    FOR EACH ROW EXECUTE FUNCTION partrace_recording_facts_immutable();

CREATE FUNCTION partrace_evidence_location_immutable() RETURNS trigger
LANGUAGE plpgsql AS $body$
BEGIN
    IF ROW(NEW.evidence_id, NEW.event_id, NEW.offset_ms, NEW.bbox_json, NEW.storage_key)
        IS DISTINCT FROM ROW(OLD.evidence_id, OLD.event_id, OLD.offset_ms, OLD.bbox_json, OLD.storage_key) THEN
        RAISE EXCEPTION 'evidence location is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$body$;
CREATE TRIGGER evidence_location_immutable BEFORE UPDATE ON evidence
    FOR EACH ROW EXECUTE FUNCTION partrace_evidence_location_immutable();

CREATE FUNCTION partrace_run_facts_immutable() RETURNS trigger
LANGUAGE plpgsql AS $body$
BEGIN
    IF ROW(NEW.run_id, NEW.conversation_id, NEW.input_message_id, NEW.plan_json, NEW.model_key, NEW.created_at)
        IS DISTINCT FROM ROW(OLD.run_id, OLD.conversation_id, OLD.input_message_id, OLD.plan_json, OLD.model_key, OLD.created_at) THEN
        RAISE EXCEPTION 'run input, confirmed plan and model configuration are immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$body$;
CREATE TRIGGER run_facts_immutable BEFORE UPDATE ON runs
    FOR EACH ROW EXECUTE FUNCTION partrace_run_facts_immutable();

-- 此通用函数总是拒绝变更；消息、向量、核查、包裹流水和已完成运行不做静默改写或删除。
CREATE FUNCTION partrace_reject_change() RETURNS trigger
LANGUAGE plpgsql AS $body$
BEGIN
    RAISE EXCEPTION '%', TG_ARGV[0] USING ERRCODE = '23514';
END;
$body$;
CREATE TRIGGER event_facts_immutable BEFORE UPDATE ON events
    FOR EACH ROW EXECUTE FUNCTION partrace_reject_change('event facts are immutable');
CREATE TRIGGER review_append_only BEFORE UPDATE ON review_records
    FOR EACH ROW EXECUTE FUNCTION partrace_reject_change('review records are append-only');
CREATE TRIGGER embedding_facts_immutable BEFORE UPDATE ON event_embeddings
    FOR EACH ROW EXECUTE FUNCTION partrace_reject_change('embeddings are immutable; reuse an existing row or create a new embedding');
CREATE TRIGGER conversation_ownership_immutable BEFORE UPDATE ON conversations
    FOR EACH ROW EXECUTE FUNCTION partrace_reject_change('conversation identity, ownership and creation time are immutable');
CREATE TRIGGER message_append_only BEFORE UPDATE ON messages
    FOR EACH ROW EXECUTE FUNCTION partrace_reject_change('messages are append-only');
CREATE TRIGGER run_terminal_immutable BEFORE UPDATE ON runs
    FOR EACH ROW WHEN (OLD.status <> 'running') EXECUTE FUNCTION partrace_reject_change('terminal runs are immutable');
CREATE TRIGGER parcel_facts_immutable BEFORE UPDATE ON parcels
    FOR EACH ROW EXECUTE FUNCTION partrace_reject_change('parcel identity and creation time are immutable');
CREATE TRIGGER parcel_record_append_only BEFORE UPDATE ON parcel_records
    FOR EACH ROW EXECUTE FUNCTION partrace_reject_change('parcel records are append-only');

-- 媒体丢失仍保留全部索引、聊天和人工记录；首版没有删除历史的接口。
CREATE TRIGGER recording_keep_history BEFORE DELETE ON recordings
    FOR EACH ROW EXECUTE FUNCTION partrace_reject_change('keep recording history');
CREATE TRIGGER event_keep_history BEFORE DELETE ON events
    FOR EACH ROW EXECUTE FUNCTION partrace_reject_change('keep event history');
CREATE TRIGGER evidence_keep_history BEFORE DELETE ON evidence
    FOR EACH ROW EXECUTE FUNCTION partrace_reject_change('keep evidence history');
CREATE TRIGGER review_keep_history BEFORE DELETE ON review_records
    FOR EACH ROW EXECUTE FUNCTION partrace_reject_change('keep review history');
CREATE TRIGGER embedding_keep_history BEFORE DELETE ON event_embeddings
    FOR EACH ROW EXECUTE FUNCTION partrace_reject_change('keep embedding history');
CREATE TRIGGER conversation_keep_history BEFORE DELETE ON conversations
    FOR EACH ROW EXECUTE FUNCTION partrace_reject_change('keep conversation history');
CREATE TRIGGER message_keep_history BEFORE DELETE ON messages
    FOR EACH ROW EXECUTE FUNCTION partrace_reject_change('keep message history');
CREATE TRIGGER run_keep_history BEFORE DELETE ON runs
    FOR EACH ROW EXECUTE FUNCTION partrace_reject_change('keep run history');
CREATE TRIGGER parcel_keep_history BEFORE DELETE ON parcels
    FOR EACH ROW EXECUTE FUNCTION partrace_reject_change('keep parcel history');
CREATE TRIGGER parcel_record_keep_history BEFORE DELETE ON parcel_records
    FOR EACH ROW EXECUTE FUNCTION partrace_reject_change('keep parcel record history');

COMMIT;
