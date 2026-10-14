-- PostgreSQL + pgvector · 首版只读检索示例，尚无后端实现。
-- 执行前由后端检查当前用户身份、录像访问权限和查询范围；此 SQL 不代替鉴权/RLS。
-- 模型名称、编码版本、维度由服务端配置，不信任客户端任意指定的模型空间。
-- 查询向量须由相同配置生成并验证：有限、非零，vector_dims = 第 8 个参数。
-- PREPARE 只创建当前连接的语句，不创建表、持久行或 ANN 索引。
-- 参数：授权录像、可选摄像头、可选区域、起止偏移、模型/版本/维度、查询向量、top_k、可选最低分。
-- top_k 1..50；偏移在录像范围内；最低分为空或 -1..1，由后端先验证。
PREPARE partrace_semantic_search (
    TEXT, TEXT, TEXT, BIGINT, BIGINT, TEXT, TEXT, INTEGER, VECTOR, INTEGER, DOUBLE PRECISION
) AS
WITH scoped_events AS MATERIALIZED (
    SELECT e.event_id
    FROM events AS e
    JOIN recordings AS r ON r.recording_id = e.recording_id
    WHERE r.recording_id = $1
      AND ($2 IS NULL OR r.camera_id = $2)
      AND ($3 IS NULL OR e.zone_id = $3)
      AND e.start_offset_ms <= $5
      AND e.end_offset_ms >= $4
), space_vectors AS MATERIALIZED (
    SELECT v.*
    FROM event_embeddings AS v
    JOIN scoped_events AS e ON e.event_id = v.event_id
    WHERE v.model_name = $6
      AND v.model_version = $7
      AND v.dimensions = $8
), latest_sources AS MATERIALIZED (
    SELECT DISTINCT ON (event_id, evidence_id) *
    FROM space_vectors
    ORDER BY event_id, evidence_id, created_at DESC, embedding_id DESC
), scored AS MATERIALIZED (
    -- MATERIALIZED 先确定相同维度的候选，再执行 <=>，避免其他模型维度参与计算。
    SELECT embedding_id, event_id, evidence_id, source_text,
           1 - (embedding <=> $9) AS score
    FROM latest_sources
), ranked AS (
    SELECT *, row_number() OVER (
        PARTITION BY event_id ORDER BY score DESC, embedding_id DESC
    ) AS event_rank
    FROM scored
)
SELECT hit.embedding_id, hit.event_id, hit.evidence_id, hit.source_text, hit.score,
       frame.media_status
FROM ranked AS hit
LEFT JOIN evidence AS frame
       ON frame.evidence_id = hit.evidence_id AND frame.event_id = hit.event_id
WHERE hit.event_rank = 1
  AND ($11 IS NULL OR hit.score >= $11)
ORDER BY hit.score DESC, hit.event_id
LIMIT $10;

-- 没有返回行时，后端仍需独立统计授权范围内 eligible/indexed/missing 数量，
-- 区分 no_matches 与 unindexed；向量生成或查询失败不能冒充零命中。
