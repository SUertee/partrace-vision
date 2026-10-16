# Agent 接口对接文档（Android 前端专用）

本文档面向前端开发人员，描述 ParTrace Vision 后端 Agent 模块对外提供的全部 HTTP 接口，可直接用于对接调试。

- **权威契约**：字段级定义以 [openapi.json](openapi.json)（`0.3-mvp-draft`）为准，本文档为对接说明，与实现逐项核对。
- **生效前提**：服务端设置 `PARTRACE_AGENT_ENABLED=true` 才会挂载本组路由；未启用时以下路径均返回 404。
- **模块边界**：Agent 只读检索并保存自己的 AI 历史，不提供任何写业务事实（事件/证据/包裹）的接口；取图等数据接口见后端数据 API。

---

## 1. 通用约定

### 1.1 基础信息

| 项 | 约定 |
| --- | --- |
| Base URL | `https://api.example.invalid`（占位，部署时替换；Android 只配置一个 base URL） |
| 路径前缀 | 全部接口位于 `/api/v1/agent/` 下 |
| 传输协议 | HTTPS（开发环境 HTTP）；请求与响应体均为 JSON，`UTF-8` 编码 |
| 接口版本 | 路由主版本 `v1`；响应体 `api_version` 固定为 `0.3-mvp-draft` |
| 数据格式 | `application/json`；服务端使用严格 JSON 解析，**禁止重复字段与非有限数值** |

### 1.2 统一鉴权规则

所有接口都必须携带 Bearer 凭据，**每次请求（含重试、分页、SSE 重连）都重新鉴权**，不缓存授权结果。

```http
Authorization: Bearer <token>
```

- **开发环境**：凭据由 `scripts/setup-local.py` 生成的本地凭据文件映射（token → 用户/权限/摄像头与录像授权）；集成测试示例凭据为 `admin-test-secret`。**生产认证尚未配置**，非 development 环境请求返回 `503 auth_unavailable`。
- **三级校验**（任一不过即拒绝，不返回部分数据）：
  1. **有效凭据**：token 存在且未禁用，否则 `401`；
  2. **会话归属（owner）**：只能访问本人的会话/消息/运行，否则 `403`；
  3. **数据权限**：计划、运行、运行读取/停止/订阅、消息列表中的助手答复，要求凭据含 `data:read` scope **且**该次运行 `plan.query.recording_id` 对应录像当前仍被授权；录像撤权后立即 `403`。创建会话/发消息/会话列表仅要求 owner，不要求 `data:read`。
- 凭据还绑定摄像头/录像白名单；访问范围外录像返回 `403`。

### 1.3 统一请求规则

| 规则 | 说明 |
| --- | --- |
| Content-Type | 带请求体的 POST 必须为 `application/json`（可带参数如 `; charset=utf-8`），否则 `415` |
| 请求体大小 | 单请求体不超过部署限额（默认 2 MB，`PARTRACE_MAX_MANIFEST_BYTES`），超限 `413` |
| 未知/重复查询参数 | GET 接口只接受文档列出的查询参数，出现未知或重复参数返回 `422` |
| 资源标识 `Id` | 字符串，1–100 字符，正则 `^[A-Za-z0-9][A-Za-z0-9_.:-]*$`；由客户端生成稳定 ID 用于幂等 |
| 时间字段 | ISO 8601 UTC 字符串，最多毫秒精度，如 `2026-10-16T08:30:00.000Z`；由服务端产生，客户端不可指定 |
| 视频相对时间 | `*_offset_ms` 为整数毫秒（≥0），相对录像起点；窗口必须 `start_offset_ms < end_offset_ms` |

### 1.4 统一成功返回结构

所有 JSON 成功响应都带 `api_version` 顶层字段，业务字段平铺其后（详见各接口）：

```json
{
  "api_version": "0.3-mvp-draft",
  "...": "业务字段"
}
```

### 1.5 统一错误返回结构

所有错误响应（含 SSE 建连前错误）结构固定，并携带响应头 `X-Request-Id` 用于排查：

```json
{
  "api_version": "0.3-mvp-draft",
  "error": {
    "code": "run_not_found",
    "message": "资源不存在。",
    "request_id": "9f1c2a..."
  }
}
```

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `error.code` | string | 机器可读错误码，前端按 code 分支处理 |
| `error.message` | string | 面向用户的中文提示，可直接展示 |
| `error.request_id` | string | 与响应头 `X-Request-Id` 一致 |

### 1.6 幂等与重试规则

创建类接口由**客户端提供稳定 ID**（`conversation_id` / `message_id` / `run_id`），网络重试安全：

- 相同 ID + 相同内容重试：返回原记录，HTTP `200`，且响应含 `"replayed": true`（运行重试无 `replayed` 字段，直接返回原运行）；
- 相同 ID + **不同**内容：`409 idempotency_conflict`；
- `GET` / `stop` / `events` 天然幂等，可任意重试。

### 1.7 分页规则

| 项 | 约定 |
| --- | --- |
| `limit` | 查询参数，1–100 整数，默认 `20` |
| `cursor` | 查询参数，服务端下发的opaque游标（带签名，不可拼装）；响应 `next_cursor` 为 `null` 表示最后一页 |
| 排序 | 会话列表：按创建时间**倒序**（新→旧）；消息列表：按创建时间**正序**（旧→新） |
| 越权处理 | 消息列表先确定页再逐条验权：页内任一助手消息的录像授权失效，**整页 403**，不静默过滤 |

### 1.8 频率与配额规则

后端**没有 QPS 级限流**；配额约束如下：

| 规则 | 默认 | 触发后果 |
| --- | --- | --- |
| 并发运行上限 `PARTRACE_AGENT_MAX_CONCURRENT_RUNS` | 2（可配 1–8） | 超额启动运行返回 `503 agent_busy`，稍后重试 |
| 单执行器 | 全局单进程执行 | 多实例部署时 `503 executor_in_use` |
| 模型调用预算 | 单次运行 8 次模型调用 / 12 次工具调用 / 60s 超时 | 超预算不返回 HTTP 错误，运行进入 `failed` 终态 |
| SSE 心跳 | 每 15 秒一行注释帧 | 见 [§5](#5-sse-事件订阅协议get-apiv1agentrunsrun_idevents) |

---

## 2. 接口清单

| # | 方法与路径 | 功能 | 成功状态码 |
| --- | --- | --- | --- |
| 1 | `POST /api/v1/agent/conversations` | 创建会话（幂等） | 201 / 200 |
| 2 | `GET /api/v1/agent/conversations` | 分页列出本人会话 | 200 |
| 3 | `POST /api/v1/agent/conversations/{conversation_id}/messages` | 发送用户问题（幂等） | 201 / 200 |
| 4 | `GET /api/v1/agent/conversations/{conversation_id}/messages` | 分页读取会话消息 | 200 |
| 5 | `POST /api/v1/agent/plans` | 由用户问题生成分析计划草稿 | 200 |
| 6 | `POST /api/v1/agent/runs` | 按确认计划启动分析运行（幂等） | 202 / 200 |
| 7 | `GET /api/v1/agent/runs/{run_id}` | 读取运行状态、答复、引用与卡片 | 200 |
| 8 | `POST /api/v1/agent/runs/{run_id}/stop` | 停止运行中的分析 | 200 |
| 9 | `GET /api/v1/agent/runs/{run_id}/events` | SSE 订阅运行终态推送 | 200（SSE） |

典型顺序：**1 → 3 → 5 → 6 →（9 或轮询 7）→（可选 8）→ 4 回读历史**。

---

## 3. 接口详情

### 3.1 创建会话 `POST /api/v1/agent/conversations`

创建当前用户自己的会话；不绑定录像，不要求 `data:read`。

**请求头**：`Authorization`（必填）、`Content-Type: application/json`（必填）

**请求体**：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `conversation_id` | Id | 是 | 客户端生成的稳定会话 ID；重试原样返回 |

```json
{ "conversation_id": "conv_001" }
```

**成功响应**：

| 状态码 | 场景 |
| --- | --- |
| `201` | 新建成功，`replayed=false` |
| `200` | 同 ID 重试，`replayed=true` |

```json
{
  "api_version": "0.3-mvp-draft",
  "conversation": {
    "conversation_id": "conv_001",
    "user_id": "user_admin",
    "created_at": "2026-10-16T08:30:00.000Z"
  },
  "replayed": false
}
```

**异常场景**：

| 状态码 | code | 触发条件 |
| --- | --- | --- |
| 400 | invalid_json | 请求体非法 JSON 或含重复字段 |
| 401 | unauthorized | 凭据缺失/无效 |
| 403 | forbidden | 该 ID 已被其他用户占用 |
| 413 | payload_too_large | 请求体超限 |
| 415 | unsupported_media_type | Content-Type 非 JSON |
| 422 | validation_error | `conversation_id` 格式非法 |
| 500 | internal_error | 服务端处理失败 |
| 503 | database_unavailable / history_unavailable / auth_unavailable | 依赖暂不可用，可稍后重试 |

### 3.2 会话列表 `GET /api/v1/agent/conversations`

仅按 owner 读取，录像撤权不隐藏会话。

**请求头**：`Authorization`（必填）

**查询参数**：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `limit` | string(数字) | 否 | 1–100，默认 `20` |
| `cursor` | string | 否 | 上一页响应的 `next_cursor` |

**成功响应 `200`**：

```json
{
  "api_version": "0.3-mvp-draft",
  "conversations": [
    { "conversation_id": "conv_002", "user_id": "user_admin", "created_at": "2026-10-16T09:00:00.000Z" },
    { "conversation_id": "conv_001", "user_id": "user_admin", "created_at": "2026-10-16T08:30:00.000Z" }
  ],
  "next_cursor": "eyJ2Ijox...==.9f1c2a"
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `conversations[]` | array | 按创建时间倒序 |
| `next_cursor` | string \| null | 下一页游标；`null` 为最后一页 |

**异常场景**：401 / 422（limit 越界、cursor 无效或不属于本查询）/ 500 / 503，同 3.1。

### 3.3 发送用户问题 `POST /api/v1/agent/conversations/{conversation_id}/messages`

客户端只能提交用户问题；`role=user`、`run_id=null`、时间由服务端填写，**禁止伪造助手回复**。

**请求头**：`Authorization`（必填）、`Content-Type: application/json`（必填）

**路径参数**：`conversation_id`（Id，必填，须为本人会话）

**请求体**：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `message_id` | Id | 是 | 客户端稳定消息 ID |
| `content` | string | 是 | 问题正文，1–2000 字符，须含非空白字符 |

```json
{ "message_id": "msg_001", "content": "昨天下午 E3 货架有没有包裹被移动？" }
```

**成功响应**：

| 状态码 | 场景 |
| --- | --- |
| `201` | 新建成功，`replayed=false` |
| `200` | 同 ID+同内容重试，`replayed=true` |

```json
{
  "api_version": "0.3-mvp-draft",
  "message": {
    "message_id": "msg_001",
    "conversation_id": "conv_001",
    "role": "user",
    "content": "昨天下午 E3 货架有没有包裹被移动？",
    "run_id": null,
    "created_at": "2026-10-16T09:05:00.000Z"
  },
  "replayed": false
}
```

**异常场景**：

| 状态码 | code | 触发条件 |
| --- | --- | --- |
| 400 | invalid_json | JSON 非法/重复字段 |
| 401 | unauthorized | 凭据无效 |
| 403 | forbidden | 会话属于其他用户 |
| 404 | conversation_not_found | 会话不存在 |
| 409 | idempotency_conflict | 同 `message_id` 已保存不同内容/不同会话 |
| 413 / 415 / 422 / 500 / 503 | — | 同 3.1 |

### 3.4 消息列表 `GET /api/v1/agent/conversations/{conversation_id}/messages`

按时间正序返回用户问题与**已完成**运行的助手答复。页内任一助手消息的录像授权失效则整页 403。

**请求头**：`Authorization`（必填）

**路径参数**：`conversation_id`（Id，必填）

**查询参数**：同 3.2（`limit`、`cursor`）

**成功响应 `200`**：

```json
{
  "api_version": "0.3-mvp-draft",
  "messages": [
    {
      "message_id": "msg_001", "conversation_id": "conv_001", "role": "user",
      "content": "昨天下午 E3 货架有没有包裹被移动？", "run_id": null,
      "created_at": "2026-10-16T09:05:00.000Z"
    },
    {
      "message_id": "reply_a1b2c3", "conversation_id": "conv_001", "role": "assistant",
      "content": "范围内检出 1 个货架互动事件……", "run_id": "run_001",
      "created_at": "2026-10-16T09:05:08.000Z"
    }
  ],
  "next_cursor": null
}
```

| 字段 | 说明 |
| --- | --- |
| `role` | `user` 用户问题；`assistant` 已完成运行的答复，`run_id` 关联运行 |
| `run_id` | 仅 assistant 消息非空，可用于 `GET /runs/{run_id}` 取引用与卡片 |

**异常场景**：401 / 403（非 owner、或页内存在无权的助手消息）/ 404 conversation_not_found / 422 / 500 / 503（history_unavailable：历史答复不完整）。

### 3.5 生成计划草稿 `POST /api/v1/agent/plans`

绑定本人会话中的一条 user 消息，按其正文生成**可修改**的计划草稿；草稿不落表，需用户确认后经 `POST /runs` 启动。

**请求头**：`Authorization`（必填）、`Content-Type: application/json`（必填）

**请求体**：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `input_message_id` | Id | 是 | 本人会话中的 user 消息 |
| `scope` | Query | 是 | 本次分析的单视频窗口，5 个字段全必填 |

`Query` 对象（贯穿计划/运行/卡片回声的固定范围）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `recording_id` | Id | 录像 ID，须在当前凭据授权范围内 |
| `camera_id` | Id | 摄像头 ID，须与录像配置一致 |
| `zone_id` | Id | 区域 ID |
| `start_offset_ms` | int ≥0 | 窗口起点（相对录像毫秒） |
| `end_offset_ms` | int ≥0 | 窗口终点，必须大于起点 |

```json
{
  "input_message_id": "msg_001",
  "scope": {
    "recording_id": "rec_20261015",
    "camera_id": "cam_01",
    "zone_id": "E3",
    "start_offset_ms": 0,
    "end_offset_ms": 120000
  }
}
```

**成功响应 `200`**：

```json
{
  "api_version": "0.3-mvp-draft",
  "query": {
    "recording_id": "rec_20261015", "camera_id": "cam_01", "zone_id": "E3",
    "start_offset_ms": 0, "end_offset_ms": 120000
  },
  "query_text": "昨天下午 E3 货架有没有包裹被移动？",
  "steps": ["检索范围内包裹移动相关事件", "回读命中事件的关键帧证据"]
}
```

| 字段 | 说明 |
| --- | --- |
| `query` | 服务端回声的规范化范围；**不会偏离客户端提交的 scope**，若模型偏离返回 503 |
| `query_text` | 实际交给语义检索的问题文本，1–2000 字符 |
| `steps` | 可见步骤描述数组，供确认页展示 |

**异常场景**：

| 状态码 | code | 触发条件 |
| --- | --- | --- |
| 401 / 403 | unauthorized / forbidden | 凭据无效；非本人消息；无 `data:read`；录像未授权 |
| 404 | message_not_found | 输入消息不存在 |
| 422 | validation_error | scope 字段非法、窗口起点≥终点、输入非 user 问题 |
| 503 | model_unavailable | 模型未配置 |
| 503 | invalid_plan | 模型未遵守固定查询范围 |
| 503 | provider_unavailable 等 | 模型调用失败，未执行检索，可重试 |

### 3.6 启动分析运行 `POST /api/v1/agent/runs`

按用户最终确认的计划启动异步运行；服务端原子建运行后立即返回 `202`，结果通过 [SSE（§5）](#5-sse-事件订阅协议get-apiv1agentrunsrun_idevents) 或轮询 `GET /runs/{run_id}` 获取。

**请求头**：`Authorization`（必填）、`Content-Type: application/json`（必填）

**请求体**：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `run_id` | Id | 是 | 客户端稳定运行 ID |
| `input_message_id` | Id | 是 | 与计划绑定的同一条 user 消息 |
| `plan` | AgentPlan | 是 | 用户确认后的计划（可来自 3.5 的修改版） |

`plan` 对象：`query`（Query，同上）、`query_text`（string，1–2000）、`steps`（string[]，每项非空）。

```json
{
  "run_id": "run_001",
  "input_message_id": "msg_001",
  "plan": {
    "query": {
      "recording_id": "rec_20261015", "camera_id": "cam_01", "zone_id": "E3",
      "start_offset_ms": 0, "end_offset_ms": 120000
    },
    "query_text": "昨天下午 E3 货架有没有包裹被移动？",
    "steps": ["检索范围内包裹移动相关事件", "回读命中事件的关键帧证据"]
  }
}
```

**成功响应**：

| 状态码 | 场景 |
| --- | --- |
| `202` | 已受理并开始执行，`status=running` |
| `200` | 同 `run_id`+同消息+同计划重试，返回原运行当前状态 |

响应体即 [AgentRunResponse](#44-运行对象-agentrunresponse-字段)（见 §4.4），示例：

```json
{
  "api_version": "0.3-mvp-draft",
  "run_id": "run_001",
  "conversation_id": "conv_001",
  "input_message_id": "msg_001",
  "status": "running",
  "plan": { "query": { "...": "..." }, "query_text": "……", "steps": ["……"] },
  "summary": null,
  "references": [],
  "cards": null,
  "model_key": "qwen3-max@v1",
  "error": null,
  "created_at": "2026-10-16T09:06:00.000Z",
  "finished_at": null
}
```

**异常场景**：

| 状态码 | code | 触发条件 |
| --- | --- | --- |
| 401 / 403 | unauthorized / forbidden | 同 3.5 三级校验 |
| 404 | message_not_found | 输入消息不存在 |
| 409 | idempotency_conflict | 同 `run_id` 已绑定不同消息或计划 |
| 422 | validation_error | plan 字段非法、窗口非法 |
| 503 | model_unavailable | 模型未配置 |
| 503 | agent_busy | 并发运行达上限，稍后重试 |
| 503 | agent_execution_failed | 任务无法启动，未保存答复 |
| 503 | executor_in_use / executor_unavailable / database_unavailable | 执行器或数据库暂不可用 |

### 3.7 读取运行 `GET /api/v1/agent/runs/{run_id}`

读取运行当前状态；每次请求都按该运行 `plan.query` 的录像**当前**授权与引用归属重新校验，历史引用媒体状态实时回读。

**请求头**：`Authorization`（必填）

**路径参数**：`run_id`（Id，必填）

**查询参数**：无（携带任意参数返回 422）

**成功响应 `200`**：[AgentRunResponse](#44-运行对象-agentrunresponse-字段)，completed 示例：

```json
{
  "api_version": "0.3-mvp-draft",
  "run_id": "run_001",
  "conversation_id": "conv_001",
  "input_message_id": "msg_001",
  "status": "completed",
  "plan": { "query": { "...": "..." }, "query_text": "……", "steps": ["……"] },
  "summary": "范围内检出 1 个货架互动事件，候选状态，关键帧 2 张。",
  "references": [ { "event_id": "evt_101", "evidence_id": "evd_101_2" } ],
  "cards": {
    "version": 1,
    "run_id": "run_001",
    "query": { "recording_id": "rec_20261015", "camera_id": "cam_01", "zone_id": "E3", "start_offset_ms": 0, "end_offset_ms": 120000 },
    "cards": [
      { "kind": "run_summary", "title": "分析结论", "summary": "范围内检出 1 个货架互动事件……",
        "references": [ { "event_id": "evt_101", "evidence_id": "evd_101_2", "offset_ms": 35000, "media_status": "available" } ] },
      { "kind": "event_evidence", "title": "事件 evt_101 证据", "event_id": "evt_101",
        "event_type": "shelf_interaction", "observation_status": "candidate", "detection_source": "automatic",
        "start_offset_ms": 32000, "end_offset_ms": 48000,
        "evidence": [
          { "event_id": "evt_101", "evidence_id": "evd_101_1", "offset_ms": 33000, "media_status": "available" },
          { "event_id": "evt_101", "evidence_id": "evd_101_2", "offset_ms": 35000, "media_status": "available" }
        ] }
    ]
  },
  "model_key": "qwen3-max@v1",
  "error": null,
  "created_at": "2026-10-16T09:06:00.000Z",
  "finished_at": "2026-10-16T09:06:09.000Z"
}
```

**异常场景**：401 / 403（非 owner、无 `data:read`、录像撤权）/ 404 run_not_found / 422 / 500 / 503（history_unavailable：已完成答复不完整）。

### 3.8 停止运行 `POST /api/v1/agent/runs/{run_id}/stop`

原子停止 running 状态的运行；终态重放返回当前状态，**始终 200**。停止后服务端保证不再追加任何结果。

**请求头**：`Authorization`（必填）、`Content-Type: application/json`（必填）

**路径参数**：`run_id`（Id，必填）

**请求体**：空对象 `{}`（不接受任何字段）

**成功响应 `200`**：[AgentRunResponse](#44-运行对象-agentrunresponse-字段)。停止成功时 `status=stopped`、`summary=null`、`references=[]`、`cards=null`、`finished_at` 已填。

**异常场景**：401 / 403 / 404 run_not_found / 415 / 422（body 含字段）/ 500 / 503。

---

## 4. 数据结构与状态机

### 4.1 运行状态机

```
running ──完成──▶ completed
   ├──停止──▶ stopped
   └──失败/重启恢复──▶ failed
```

- 终态（completed/stopped/failed）**不可再变**；对终态运行调用 stop/再次完成都是重放语义，返回当前状态。
- 服务重启会把遗留 running 置为 `failed`（error.code=`service_restarted`），**不自动续跑**。

### 4.2 状态与字段联动（前端渲染依据）

| status | summary | references | cards | error | finished_at |
| --- | --- | --- | --- | --- | --- |
| running | null | [] | null | null | null |
| completed | 助手答复 | 引用数组 | **AgentCardBundle（已落库）** | null | 已填 |
| stopped | null | [] | null（SSE 推送合成状态卡） | null | 已填 |
| failed | null | [] | null（SSE 推送合成状态卡） | `{code,message,request_id}` | 已填 |

### 4.3 引用对象 AgentReference

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `event_id` | Id | 命中本次查询窗口的事件 |
| `evidence_id` | Id | 属于该事件的关键帧；媒体缺失帧不会出现在 completed 引用中 |

关键帧图片经后端数据接口获取：`GET /api/v1/evidence/{evidence_id}/media`（同样需 Bearer 鉴权，返回图片字节）。

### 4.4 运行对象 AgentRunResponse 字段

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `run_id` / `conversation_id` / `input_message_id` | Id | 运行及其归属 |
| `status` | enum | `running` / `completed` / `stopped` / `failed` |
| `plan` | AgentPlan | 启动时确认的规范化计划 |
| `summary` | string \| null | 助手答复（1–4000 字符），仅 completed |
| `references` | AgentReference[] | 仅 completed 非空，≤5000（部署限额） |
| `cards` | AgentCardBundle \| null | 见 §4.5 |
| `model_key` | string | `模型@版本`，来源可追溯 |
| `error` | object \| null | 仅 failed：`{code, message, request_id}` |
| `created_at` / `finished_at` | UtcTime \| null | 创建/终态时间 |

### 4.5 卡片包 AgentCardBundle

`version` 固定 `1`；`run_id`、`query` 回声本次运行与固定范围；`cards` 数组（≤60），按 `kind` 判别四种：

**① `run_summary` 答复卡**（每个 completed 包恰好一张，且为第一张）

| 字段 | 说明 |
| --- | --- |
| `summary` | 与运行 summary 一致 |
| `references[]` | ≤50 个 EvidenceItem（`event_id`/`evidence_id`/`offset_ms`/`media_status`） |

**② `event_evidence` 事件证据卡**（每个被引用事件至多一张，按引用首现序）

| 字段 | 说明 |
| --- | --- |
| `event_id` / `event_type` | 事件标识与类型（如 `shelf_interaction`） |
| `observation_status` | `candidate` 候选 / `observed` 已观察 |
| `detection_source` | `automatic` / `manual` / `mock`（mock 为明示的模拟来源） |
| `start_offset_ms` / `end_offset_ms` | 事件窗口 |
| `evidence[]` | ≤200 个 EvidenceItem，按 `offset_ms` 升序；取图见 §4.3 |

**③ `coverage_gap` 覆盖缺口卡**（检索/媒体存在缺口时显式给出，不隐藏）

| 字段 | 说明 |
| --- | --- |
| `gap` | `unindexed` 部分事件未建语义索引 / `no_matches` 无匹配候选 / `media_missing` 关键帧媒体缺失 |
| `detail` | 中文说明文案 |
| `eligible_event_count` / `indexed_event_count` | 范围事件数 / 已索引数；`media_missing` 时为 `null` |

**④ `run_status` 状态卡**（仅出现在 SSE 的 stopped/failed 终态事件，不落库）

| 字段 | 说明 |
| --- | --- |
| `status` | `stopped` / `failed` |
| `detail` | 停止固定文案；失败取 `error.message` |

> 卡片事实由服务端按实际检索回读确定性装配并在落库前回查，前端可放心渲染；`completed` 包中不会出现 `run_status` 卡。

---

## 5. SSE 事件订阅协议（`GET /api/v1/agent/runs/{run_id}/events`）

用于在运行期间接收**终态推送**，替代高频轮询。协议为标准 Server-Sent Events。

**请求头**：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `Authorization` | 是 | 同通用规则 |
| `Last-Event-ID` | 否 | 重连对齐，见下 |
| `Accept: text/event-stream` | 建议 | 服务端不强制校验 |

**路径参数**：`run_id`（Id，必填）；**不允许查询参数**。

**响应**：`200`，`Content-Type: text/event-stream`，`Cache-Control: no-cache`。

### 事件序列

1. **快照先行**：连接即鉴权并回读当前运行。
   - 若 `running`：先发送一次快照事件（`id: 1`），`cards=null`；
   - 若已终态：直接发送终态事件（`id: 2`）后服务端关闭。
2. **终态推送**：运行到达终态并**事务提交后**，推送一次终态事件（`id: 2`）并关闭流。
3. **心跳**：等待期间每 15 秒发送一行注释帧 `: keep-alive`，客户端应忽略。

### 事件格式

```text
id: 2
event: run
data: {"run_id":"run_001","status":"completed","cards":{"version":1,...},"error":null,"finished_at":"2026-10-16T09:06:09.000Z"}
```

`data` 为单行紧凑 JSON：

| 字段 | 说明 |
| --- | --- |
| `run_id` / `status` / `error` / `finished_at` | 同 AgentRunResponse |
| `cards` | completed：与 `GET /runs/{run_id}` 一致的落库包；**stopped/failed：服务端即时合成的 `run_status` 状态卡包**；running 快照为 `null` |

### Last-Event-ID 对齐与重连

- 事件 id 在同一 run 内稳定：快照=`1`，终态=`2`。
- 重连携带 `Last-Event-ID: 1`：跳过运行中快照，直接等终态；
- 重连携带 `Last-Event-ID: 2` 且运行已终态：**不发送任何事件**直接关闭（客户端已持有终态）；
- 服务重启后 id 语义不变；重启遗留的 running 会转为 `failed`（`service_restarted`），重连可订阅到该终态。

### 异常与关闭约定

- **建连前**鉴权/归属/录像校验失败：按常规 JSON 错误返回（401/403/404/422…），同 3.7。
- **建连后**若凭据被撤或数据变化：服务端**静默关闭流**（不再发送事件）；客户端收到非正常关闭后应调用 `GET /runs/{run_id}` 获取当前状态或错误体，再决定是否重连。
- 推送只是"已提交"信号：订阅端收到事件即拥有最终数据，无需也不应再推断中间过程。

---

## 6. 错误码总表

| 状态码 | code | 含义 / 典型触发 |
| --- | --- | --- |
| 400 | invalid_json | JSON 非法或含重复字段 |
| 400 | invalid_request | HTTP 层请求无法处理 |
| 401 | unauthorized | 凭据缺失、格式错误或已禁用 |
| 403 | forbidden | 非本人资源 / 缺 `data:read` / 录像或摄像头授权失效 |
| 404 | conversation_not_found / message_not_found / run_not_found | 资源不存在 |
| 409 | idempotency_conflict | 稳定 ID 已绑定不同内容 |
| 413 | payload_too_large | 请求体超部署限额 |
| 415 | unsupported_media_type | Content-Type 非 application/json |
| 422 | validation_error | 字段、类型、范围、关联或游标无效 |
| 500 | internal_error | 服务端处理失败，未确认结果 |
| 503 | model_unavailable | 模型未配置 |
| 503 | agent_busy | 并发运行达上限 |
| 503 | agent_execution_failed | 运行无法启动 |
| 503 | executor_in_use / executor_unavailable | 已有执行进程 / 执行连接中断 |
| 503 | database_unavailable | 数据库暂不可用 |
| 503 | history_unavailable | 历史签名配置异常 / 已完成答复不完整 |
| 503 | auth_unavailable | 非 development 环境（正式认证未配置） |
| 503 | invalid_plan | 模型未遵守固定查询范围 |
| 503 | provider_unavailable 等模型侧 code | 计划生成失败，未执行检索 |

> 5xx 均可安全重试（创建类沿用原稳定 ID）；503 建议在客户端做指数退避。

运行中的预算耗尽经既有 `error.code` 返回：`agent_model_limit`（模型调用上限）、`agent_tool_limit`（含 skill 加载的工具上限）、`agent_recursion_limit`（图步数上限）；超时为 `agent_timeout`。读取运行本身仍返回 200，需检查 `status=failed`；计划阶段的这些错误使用既有 503 响应结构。失败运行重做使用新 run_id，旧 ID 只回读原结果。

执行图可通过内置 skill 指引使用现有业务工具；语义编码明确不可用时，重新验权后可改用普通事件查询，completed 的 summary／摘要卡会包含语义不可用说明。直接语义检索 HTTP 接口的 503 行为不变；此机制没有增加公开路由或错误／卡片 schema 字段。

---

## 7. 推荐对接流程

```text
POST /conversations            创建会话（conv_id 稳定）
POST /conversations/{id}/messages   发送问题（msg_id 稳定）
POST /plans                    生成计划草稿 → 用户确认/修改
POST /runs                     启动运行（run_id 稳定）→ 202
GET  /runs/{run_id}/events     订阅终态推送（优先）
  或 GET /runs/{run_id}        轮询兜底（建议 ≥2s 间隔）
[可选] POST /runs/{run_id}/stop 用户取消
渲染：status=completed → summary + references + cards（§4.5）
取图：GET /api/v1/evidence/{evidence_id}/media（后端数据接口）
回读：GET /conversations/{id}/messages 恢复完整对话
```

取消订阅或页面销毁时请关闭 SSE 连接以释放服务端订阅资源；停止运行后服务端保证不再追加任何结果。
