# ParTrace Vision API 设计草案 · 0.2-draft

**状态：未实现、未冻结。** 此文件和 [openapi.json](openapi.json) 是 D01 的协作草案，不代表服务、认证、存储、算法或 SDK 已运行。主端按用户确认采用 **Android / Kotlin + Compose**；接口供 Android、后端、视觉和独立 Agent 服务共用。

## 端点与 P0 顺序

后端由 `backend/` 实现，前缀 `/api/v1`。媒体访问只在真实文件可用时开放。

| 方法与路径 | 用途 |
|---|---|
| `GET /cameras`、`GET /zones` | 摄像头、区域及 ROI/标定配置版本清单 |
| `GET /recordings`、`GET /recordings/{recording_id}` | 原输入、时钟映射、索引与保留状态；有可用视频及已校准时间时提供源窗口回看 |
| `POST /index-imports` | 接收单份 Recording 的版本化索引与真实媒体上传，返回导入回执 |
| `GET /events` | 区域/摄像头/时间/类型查询，返回片段与证据引用、固定索引版本和覆盖摘要 |
| `GET /tracks/{track_segment_id}` | 单镜匿名片段、事件顺序、已观测点与缺口 |
| `GET /evidence/{evidence_id}` | 证据元数据及当前可用状态 |
| `GET /evidence/{evidence_id}/media` | 经过身份检查后读取真实关键帧或短片字节 |
| `GET /coverage` | 分开查询采集覆盖、索引状态和媒体保留状态 |
| `POST /journeys`、`GET /journeys`、`GET /journeys/{journey_id}` | 显式保存、列出及回读复现快照和当前人工选择 |
| `POST /review-actions` | 幂等、带版本检查地追加人工确认/拒绝/拆分/说明 |

P0 按以下顺序交付：

1. 共同确认目录、ID、时间基准、来源与失败样例，固定真实预录素材和人工时间对照。
2. 实现真实图片上传与索引导入，验证回执、稳定 ID、重复导入及重启回读。
3. 接通同一版本的事件、片段、证据和覆盖查询；Android 打开真实帧，区分无命中、无覆盖和未索引。
4. 保存服务端构建的 Journey 快照，再实现人工复核、并发冲突与重启恢复。
5. 独立 `agent/` 服务接只读工具和可核对引用。SDK 实测独立推进，不阻塞预录闭环。

## 依据与范围

依据为飞书《技术方案与开发规划》 **r51**、《PRD》 **r230**，以及用户确认的 Android 优先和四模块职责。原文仍有 Web 首版口径，本草案采用用户确认的主端，保留端无关的数据协议。原文读取快照保存在被忽略的 `.local-state/`；本目录不收录原文中的私人数据。

首轮是固定视角、固定区域、单镜可见片段与真实关键帧。两路样本分别查询；自动跨镜、CandidateAssociation、外观/向量检索和复杂动作识别后置。API 中的候选标签只是预留协议，不能据此声称增强能力已实现。数据库、服务框架、模型、对象存储及部署引擎均待确认；[数据库草案](../database/schema.sql) 使用 SQLite 验证约束，不锁定生产引擎。

## 共同协议

### ID、版本和来源

API 与 SQL 使用相同的 `camera_id`、`recording_id`、`track_segment_id`、`event_id`、`evidence_id`、`import_id` 和 `journey_id`。`zone_configs` 以 `zone_config_id` 为主键；`zone_id` 是 E3 这样的业务区域，与 `camera_id + config_version` 共同表达配置版本。视角/ROI/标定改变时新增配置，不覆盖历史。

每次索引由独立 `import_id` 与 `(recording_id, index_version)` 标识。完整批次封存，旧片段、事件和证据 ID 不改指向；重跑生成新版本与新事实 ID。相同输入媒体的原始来源标识保留，不把原摄像头临时 Track ID 当作永久人物 ID。

| 维度 | 允许值与含义 |
|---|---|
| `source_mode` | `prerecorded / sdk / mock`：输入来源 |
| `detection_source` | `automatic / manual / sdk_event / mock`：索引产生方式 |
| `observation_status` | `observed / candidate`：观测与候选判断 |
| Recording `media_kind` | `video / frame_set / event_metadata`：实际输入形态 |
| Evidence `media_kind` | `keyframe / video_clip`：可核对证据形态 |
| `capture_kind` | `continuous / frame_sample / gap`：连续采集、离散采样、采集缺口 |
| `index_status` | `pending / partial / complete / failed`：处理状态 |
| `availability` | `available / missing / expired / deleted / unverified`：媒体状态 |

原生 SDK 事件使用 `event_type=device_detection`，保留 `metadata.raw_sdk_type`，`track_segment_id` 和未确定的区域为 `null`；不得映射成货架交互、拿取或轨迹。取得 SDK 视频/帧后由团队视觉处理，则可使用 `source_mode=sdk + detection_source=automatic`，仍需真实视觉和时间证据。

首轮视觉事件是 `enter_zone / dwell / shelf_interaction / leave_zone`；`doorway_crossing` 必须附方向证据。`pickup_candidate / return_candidate / carrying_candidate` 放在 `enhancement_tags`，不替代基础事件或变成已证实动作。普通区域事件必须引用非空、匹配摄像头的区域配置。

### UTC、原始时钟和播放偏移

查询使用 ISO 8601 UTC、`Z` 后缀、最多毫秒精度，区间为 `[start,end)`。SQL 存 epoch 毫秒；API 与 SQL 转换不能经过本地时区。事件区间相交使用 `event.start < query.end && event.end > query.start`；零时长观测点使用 `query.start <= t < query.end`。

保留 `raw_start_epoch_ms / raw_end_epoch_ms`、`raw_captured_epoch_ms`、原始帧 PTS、`time_base_num / time_base_den`、`raw_time_origin_pts` 和映射版本。方向约定为：

```text
aligned_epoch_ms = raw_epoch_ms + clock_offset_ms
offset_ms = aligned_epoch_ms - aligned_recording_start_epoch_ms
```

帧 PTS 先按已记录的有理数 time base 转换到媒体相对时间，再结合原始起点和偏移换算 UTC；毫秒量化规则须在实现中统一，并保留原始 PTS 供误差核对。偏移只应用一次，不能因为字段已有 UTC 外观而再次叠加。

`alignment_status=unknown` 时保留原始值，校准说明非空，`clock_offset_ms=null`，已对齐的 UTC 字段为 `null`。不默认偏移为 0，不让未校准记录参与确定的 UTC 命中集；查询用 `clock_unknown`/缺口说明它们。导入的 `clock` 是校准上下文；服务端检验及保存映射快照，不能仅因客户端声明 aligned 就判定实测已完成。

Recording 原始基准保留；每个 Import 保存 `time_mapping_version / time_mapping_snapshot_json`。后续校准生成新映射和新索引，不改旧事实定位。API Recording 的 `clock/start/end` 按选中的完整 Import 映射解析；显式索引查询及保存快照使用同一映射。`GET /recordings` 可传 `index_import_ids` 并返回固定版本的 snapshot；`GET /recordings/{id}` 可传单个 `import_id`，回看历史 Journey 时必须沿用它的版本。视频窗口回看须以该映射计算偏移，错误或未知映射不能发出假播放位置。

### 查询和稳定分页

`GET /events` 与 `GET /coverage` 接受 `start/end`、可选 `camera_id/zone_id/event_types/source_modes`；至少给出摄像头或区域。数组参数以逗号分隔。`limit` 默认 50、最大 200；服务端另配置时间跨度/复杂度上限并明确拒绝超限请求。

默认逐 Recording 选择 **最新 complete Import**。首个响应的 `snapshot.index_import_ids` 固定所用版本；后续 opaque cursor 绑定规范化条件、排序、版本和身份范围。事件排序为 `(start ASC,event_id ASC)`，不得在翻页时悄悄换成新索引。主动重查才重新选择最新完整批次。调用方也可传 `index_import_ids` 固定旧版本，每份 Recording 只选一个版本。

覆盖状态仍报告采集但尚未完成索引的输入、最新失败尝试和缺口，不因它们缺少 complete Import 而消失。若已有旧的 complete Import，查询明确返回它的 ID，不能把较新失败尝试的空集合当作新的完整结果。

| `query_status` | 解释 |
|---|---|
| `matched` | 当前选择版本有命中；仍必须显示覆盖/证据缺口 |
| `no_detections` | 已索引部分未检出匹配，不证明无人经过或事件未发生 |
| `no_capture` | 请求范围没有采集覆盖，不能称作“零活动” |
| `unindexed` | 有输入/采集信息，尚无可用索引 |
| `index_failed` | 索引处理失败且没有可用于该结果的完整版本 |
| `partial` | 混合/部分处理，不能把未处理部分合并为完整空结果 |
| `clock_unknown` | 存在相关输入，但时钟未校准，不能定位到该 UTC 窗口 |

这些正常数据状态均可返回 HTTP 200。`coverage_summary` 分别给出采集状态、索引状态、实际覆盖区间、未知区间和媒体可用性数量；原始 coverage rows 可通过 `GET /coverage` 查看。连续录像保留状态与检测完成度互不替代。`frame_sample` 是单个采样时点，可使用相同起止时间；采样点之间仍是未知，不能连成连续覆盖。未知不能改写为事实，Track 结束也不等于离站。

## 索引导入与媒体

### `POST /index-imports`

必须携带 `Idempotency-Key`。JSON schema 详见 OpenAPI 的 `IndexManifest / JSONIndexManifest / MultipartIndexImport`。

- **真实媒体：** 使用 `multipart/form-data`。`manifest` 是 JSON part，`files` 是重复二进制 part。清单内 `media.file_name` 是 basename，与 part 的 `Content-Disposition` filename 对应，附 SHA-256、字节数和 MIME。文件名、文件大小、内容类型和真实字节由服务端检验。图片可以作为关键帧上传，录像/短片在输入可用时上传。
- **没有上传媒体：** 允许 `application/json`，但 Recording/Evidence 只能使用 `MediaUnavailable` 声明缺失/到期/删除/未验证原因；不能宣称 available。mock 样例走此路径，媒体 URL 为 `null`。
- **禁止存储路径输入：** 不接受本机绝对路径、裸 `storage_key`、任意下载 URL、目录穿越或未知额外字段。客户端上传 bytes，服务端分配存储对象；存储对象与元数据分开管理。

文件 part 的 basename 必须唯一，且与清单引用的文件集合一致；同一真实文件被 Recording 和 Evidence 引用时，声明的哈希、大小与 MIME 必须一致。不接受缺少文件的可用媒体，也不留下无人引用的上传文件。图像/视频 magic bytes、解码可用性和配置的上传限额在服务端验证，不能只信 Content-Type。

服务端检查每个对象的摄像头、Recording、Import、来源、区域配置、时间范围和引用；普通视觉事件和片段来自同一导入来源，SDK 原生元数据不生成片段。先暂存及校验文件，在同一索引发布事务内写入事实与关联并完成封存；失败时清理暂存文件，不发布一半事实。对象存储与数据库没有天然跨系统事务，实际实现须采取可补偿的暂存/发布策略并记录失败。

返回 201 导入回执，含 `import_id/recording_id/index_version/index_status`、规范化清单哈希、接收时间和实际计数。回执证明接收/持久化过程，不证明自动视觉准确率或 SDK 可用。partial/pending/failed 批次不加入默认完整索引；failed 必须有原因。生产者名称/版本是说明信息；可信 `producer_id` 来自服务端认证身份与 namespace，不能用客户端换名称绕过幂等。

### 元数据与字节读取

| 状态 | `GET /evidence/{id}` | `GET /evidence/{id}/media` |
|---|---|---|
| `available` | 200，可用元数据 | 200，真实字节 |
| `missing` | 200，说明缺失 | 404 `media_missing` |
| `expired` | 200，保留原引用 | 410 `media_expired` |
| `deleted` | 200，保留原引用 | 410 `media_deleted` |
| `unverified` | 200，说明未验证 | 409 `media_unverified` |

不存在的 Evidence ID 返回 404 `not_found`。图片占位图不能作为此媒体端点的成功响应。API 可临时签发访问 URL，也可由客户端调用受认证的二进制端点；URL 不持久化到数据库或历史快照。不可用状态的 `media_url/media_url_expires_at` 必须为 `null`。Evidence 元数据读取可传 `import_id` 指定已关联的索引上下文；未传时按最早已关联 complete Import 的创建时间、ID 确定性解析并回显。不关联的上下文返回 409 `selection_conflict`，不能借新版 Import 重写 Evidence 固有的时间映射和位置。

`GET /recordings/{id}` 的元数据同样在原视频到期后可读取。只有 `media_kind=video`、真实可用且时间映射已验证时才返回 `playback`；可选择给出成对的 `window_start/window_end`。帧集或 metadata-only 输入明确没有录像回放。零命中时，有保留的视频仍可回看源窗口；视频已失效而关键帧仍在时，继续如实展示关键帧。

## 保存、复核和并发

### Journey 保存与回读

`POST /journeys` 请求只包含查询条件、所选 `index_import_ids`、选中的 Track/Event/Evidence ID、未知区间确认引用和用户说明。**不接受任意客户端事实快照**，也不接受客户端声称的人物身份、连续路径或新事件。

服务端验证选择确实来自固定版本和授权范围，再构建完整 `server_snapshot`：原查询、摄像头/区域版本、原始与映射时钟、Recording、片段、事件、证据定位与保存时状态、覆盖及未知原因。证据基线保留 raw 时钟/PTS、映射版本、定位与成对上下文偏移，不能只保存 Evidence ID 后依赖当前状态重算。snapshot 不存临时 URL；保存时间与操作人由服务端产生。初始 `review_version=1`。

回读包含原 snapshot、当前人工选择、复核历史和 `current_evidence_availability`。保存时 missing 的图不会在快照中变成 available；后续媒体到期不会删除历史事实引用。写入响应的幂等回执不持久化临时访问 URL；读取接口按当前身份、状态重新决定媒体访问。

### ReviewAction

`POST /review-actions` 包含 Journey ID、`expected_review_version`、`action`、目标 item ID 和非空理由。`split` 额外提供至少两组现有 item；分组必须是目标项的无重复完整划分。`comment` 可作用于整个 Journey，其余动作须有目标。

`confirm / reject / split / comment` 只改变人工选择、分组及决定，不改 Event 的 `event_type/observation_status`、视觉点或缺口；“确认选择”不证明身份、运单、责任或未拍到的过程。每次操作留存操作人、时间、前后版本及前后人工状态。

处理顺序：

1. 认证身份、解析/校验请求，并计算规范化 payload 哈希。
2. 按 **actor + operation + Idempotency-Key** 查询已有回执；相同 key/hash 重放原业务结果，先于当前版本判断。相同 key/不同 hash 返回 409 `idempotency_conflict`。
3. 首次请求校验 Journey 和目标引用，对 `expected_review_version` 做原子锁定/CAS。
4. 在同一事务追加 ReviewAction、替换当前人工项、将版本加一并保存幂等回执；失败全部回滚。

并发复核的旧版本返回 409 `review_version_conflict`，附 expected/current 版本；客户端重新读 Journey 后决定是否重新提交。网络重试携带原 key 和原 payload，不能因当前版本已经增加而把成功操作误判为冲突。同一 key 可以在不同 operation 使用，不能跨 actor 重放。

## 数据库映射与保留策略

拟议表为 `cameras / zone_configs / recordings / index_imports / track_segments / events / evidence / event_evidence / coverage_intervals / journeys / journey_items / review_actions / retention_cleanup_logs`。具体列与约束见数据库草案；以下是 API/SQL 有意存在的表示差异。

| API 字段 | SQL / 服务端映射 |
|---|---|
| UTC `start/end/captured_at` | 对应 `start_epoch_ms/end_epoch_ms/captured_epoch_ms`，保留 raw 值并按选定 Import 映射换算 |
| Recording `clock` | Recording 原始时钟 + 选定 `index_imports.time_mapping_snapshot_json`；不覆盖第一次原始基准 |
| Evidence `offset_ms`、上下文偏移 | 直接对应同名 `offset_ms/context_start_offset_ms/context_end_offset_ms` 列；上下文成对，且定位偏移落在区间内，不能导入后丢失 |
| Evidence `alignment_reason` | SQL 同名字段；unknown 时为非空说明。导入时根据经过校验的 Recording 校准上下文生成，不以缺失偏移冒充已校准 |
| `producer_name/producer_version` | 清单说明；认证/namespace 产生可信 SQL `producer_id`，用于幂等作用域 |
| Evidence 的 `import_id/index_version` | 通过所选 Event→`event_evidence`→Evidence 解析；Evidence 可被多个索引引用，不能在媒体行上伪造永久唯一 Import 归属 |
| `Journey.server_snapshot` | SQL `server_snapshot_json.baseline`，不可变事实基线 |
| `Journey.items` 的 `group_id/selected/decision` | SQL `server_snapshot_json.human_selection` 中的人工状态；`journey_items` 保存当前显式 FK、顺序及版本。group/decision 并非漏存，也不改索引事实 |
| ReviewAction 前后状态 | SQL 保存完整 `before_snapshot_json/after_snapshot_json`；基线不能变化，人工部分随版本变化 |
| `media_url/playback` | 按当前身份与真实状态临时生成，不映射为持久化 SQL URL 列 |

当前规范的 API 暴露 `item_kind=track_segment`，SQL 引用表的 `item_kind=segment` 由适配层明确映射；三类 ID 字段仍同名。Camera 状态直接对应 `camera_status`；Zone 清单中的 active/显示标签可由配置版本与业务区域派生，不修改历史配置行。

P0 保留策略先由服务端配置，不增加公开管理端点：至少记录策略版本、目标类型、保留期限/到期依据、清理任务计划和授权范围。没有正式环境数据时不预填真实保留天数。清理日志保存任务/重试、策略快照、目标、时间、结果和失败原因；只有实际删除/缺失检查后才更新媒体状态。失败须可重试；元数据、Journey 引用和复核历史继续保留。测试不仅看倒计时，还须核对字节访问失败与元数据回读。

## 服务身份与 Agent 边界

认证方案尚未实现，OpenAPI Bearer 描述是待落地的身份边界；角色由服务端身份确定，不接受客户端自行声明角色、actor 或 SQL。

| 角色 | 权限范围 |
|---|---|
| `admin` | 授权范围内读取、显式保存与复核；管理导入需实际授权 |
| `ingest` | 读取导入所需清单、上传并导入授权来源，不能人工复核 |
| `agent_read` | 受限读取事件、片段、证据、覆盖和授权 Journey，禁止后端写入 |

Agent 由 `agent/` 独立实现，前缀 `/agent/v1`；OpenAPI 各操作用独立 server 与 `x-service=agent` 标识，**不是后端工作包**。

| 方法与路径 | 独立 Agent 服务职责 |
|---|---|
| `POST /plans` | 问题转为可见、可修改、经校验的查询条件 |
| `POST /runs` | 执行用户确认后的条件，固定后端索引版本 |
| `GET /runs/{run_id}` | 返回运行状态、部分结果、候选/未知及有效引用 |
| `POST /runs/{run_id}/stop` | 终止运行；确认后不再追加晚到结果 |

只读工具映射为 `search_events / get_track_segments / get_evidence / get_coverage / get_journey`，共用后端事实和 ID。事实句须引用存在的 Event 与真实可打开 Evidence；不可用证据只能说明候选/未知，不计入事实验收。工具失败后保留 Android 直接筛选，不能用模拟数据冒充成功。停止、失败和完成为终态，工具/模型晚到回调不能继续增加已停止 run 的 `result_version`。

## 错误与 mock 样例

所有 JSON 成功响应附 `api_version=0.2-draft`；请求、成功响应和错误 body 均在 OpenAPI 定义严格字段及 `additionalProperties=false`。字段、引用和跨对象时间关系仍需服务端校验，JSON schema 通过不等于业务正确。

错误 envelope 统一为 `api_version + error{code,message,request_id,details}`。401/403 是身份与角色问题；400/422 是格式/字段/引用错误；409 是幂等、版本、选择或未验证媒体冲突；413/415 是上传大小/类型问题；429/500/503 分别是限流、服务错误和依赖不可用。不得把系统失败回成成功的空集合。

[v0.2 样例](examples/v0.2/) 全部是合成 mock；查询区域为 **E3**，UTC 范围为 **2026-10-08T06:00:00Z 至 08:00:00Z**。没有任何真实图片或可播放文件，媒体为 `missing`、URL 为 `null`；成功结构不能用于算法、SDK 或 Agent 事实验收。

| 样例组 | 文件 |
|---|---|
| 查询和状态 | `query-request.json`、`events-matched-response.json`、`events-no-detections-response.json`、`events-no-capture-response.json`、`events-unindexed-response.json`、`events-index-failed-response.json` |
| 元数据与定位 | `cameras-response.json`、`zones-response.json`、`recording-response.json`、`track-response.json`、`coverage-response.json`、`evidence-missing-response.json`、`evidence-expired-response.json` |
| JSON 无媒体导入 | `index-import-json-request.json`、`index-import-response.json` |
| SDK 原生形状模拟 | `mock-sdk-native-metadata-request.json`、`mock-sdk-native-recording-response.json`：source_mode=mock、detection_source=sdk_event，只检验 adapter 元数据形状，未校准、无媒体、无真实 SDK 实测 |
| 保存和回读 | `journey-create-request.json`、`journey-create-response.json`、`journey-get-response.json`、`journey-list-response.json` |
| 复核和冲突 | `review-request.json`、`review-response.json`、`review-conflict-response.json`、`idempotency-conflict-response.json` |
| 错误 | `validation-error-response.json`、`media-expired-error-response.json` |
| 独立 Agent 协作 | `agent-plan-request.json`、`agent-plan-response.json`、`agent-run-request.json`、`agent-run-response.json`、`agent-stop-request.json`、`agent-stopped-response.json` |

OpenAPI 的 `x-fixture-schemas` 给出每个样例对应的 schema，便于静态验证。原 [0.1 demo-bundle](examples/demo-bundle.json) 保留不动，不能直接当作本版可导入清单。

冻结前共同验收：实际 multipart 真帧上传与取回；UTC/PTS/偏移对照；来源与 SDK 元数据隔离；默认/固定版本分页；空命中、无覆盖、未索引、失败与离散采样；保存/重启回读；幂等重放先于版本判断；并发复核；媒体到期/清理后元数据仍可读；Agent 引用与停止行为。此草案的静态验证不代替上述运行验证。
