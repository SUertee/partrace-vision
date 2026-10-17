# Backend 与 Vision 联动设计

**SDK People 事件触发后，App 先通过 API 保存原片并写入 `recordings(pending)`；独立 Vision worker 解析、发布结果，App 收到完成状态回调。** 既有离线完整包导入仍保留。 Backend 使用 FastAPI，负责权限、校验、媒体保存和数据库事务；Vision 不直连业务数据库，首版可用批处理程序与导入客户端，无需额外部署 HTTP 服务。

关联 [M03 视觉产物与视频入库](README.md#m03)、[M04 事件与证据](README.md#m04)、[M05 语义检索](README.md#m05)，对应 [D03 素材与人工基准](../DEVELOPMENT_PLAN.md#d03--固定素材与人工基准)、[D04 事件与证据包](../DEVELOPMENT_PLAN.md#d04--算法事件与真实证据包) 和 [D05 共用服务](../DEVELOPMENT_PLAN.md#d05--共用事件与证据服务)。本页也收录 [M10 · P1 Parcel 扫码与入出库](#parcel) 的流程、分工和与视觉事件的交接边界。字段、必填项和返回结构以 [OpenAPI](../api/openapi.json) 为准，表与约束以 [SQL](../database/schema.sql) 为准。本页保留既有完整包交接，并新增 [People 录像任务](#people-pipeline) 的表、API 与调度协议。

截至本次本地核对，Backend 开发工作区已有完整包导入、事件／关键帧读取及两个向量操作，对应实现与验证记录随各自开发提交另行交付；最新 dev 已包含 [Vision A/B 算法](../../vision/README_hua.md) 及模型；新增 API worker 将真实原片交给既有算法。本次发布交接文档，不代表真实视觉效果、外部编码器或 Android D10 已验收。

## 谁负责什么

| 交付 | Vision · 徐泽华 | Backend · Harry Xu |
| --- | --- | --- |
| 固定素材与基准 | 连续预录视频、ROI 对照、人工事件时间表；标明多人、遮挡和未知区间 | 提供版本化镜头／区域配置；需要 UTC 时准备独立可核验的时间基准 |
| 检测与匿名跟踪 | 检测、同视频匿名片段、区域事件；输出视频偏移、来源和观测／候选状态 | 检查配置归属、时间范围、ID 与跨对象引用，保存原始事件 |
| 关键帧 | 从同一源视频导出真实 PNG／JPEG；提供偏移、可选实际观测框 | 验证媒体文件，分配私有存储键，提供元数据与图片字节读取 |
| 完整结果包 | 本地完成分析后，交接 `RecordingManifest`、视频和帧文件；重试复用 ID 与原内容 | 校验后事务写入 `recordings / events / evidence`；发布失败回滚并按结果清理媒体 |
| 可检索描述与向量 | 输出可核对描述，按共同确认的模型／预处理版本生成文本向量 | 校验 Event／Evidence 归属、向量与模型空间，写入 `event_embeddings`；用同空间编码查询 |
| 联调与效果 | 逐样本记录误检、漏检、时间偏差与 Track 切换，说明自动／人工来源 | 核对导入、重试、回读、媒体缺失与检索缺口；向 Android／Agent 提供稳定事实接口 |

模型、维度和描述生成方式由算法与后端共同确认；当前没有“只交文本、Backend 自动批量编码”的独立 API。Agent 通过 Backend 的只读工具检索与回查，人工核查决定由管理员在 Android 显式保存，不写回算法事件。

## 视觉模块涉及哪些表

以下读写均由 Backend 执行；Vision 交付 API 输入。媒体文件保存在受限文件存储，数据库保存引用。

| 表／配置 | Vision 交接内容 | Backend 读写与关系 | 服务端派生或控制 |
| --- | --- | --- | --- |
| 版本化配置文件 | 使用已发布的 `config_version / camera_id / zone_id`，按其 ROI 处理 | 读取配置并校验镜头、区域归属；不新增 `cameras / zones` 表 | 已发布配置不可覆盖，ROI 调整使用新版本 |
| `users` | 使用授权导入作业的 Bearer 凭据，不在清单自报操作人 | 读取当前用户状态、管理员角色及权限；导入不写用户表 | 身份与资源授权由 Backend 验证 |
| `recordings` | 稳定 `recording_id`、镜头／配置、来源、起始时间或 `null`、时长、视频文件名 | 原片先写 pending，完整解析后一次发布；仅 published 用于事件/覆盖 | `publication_status / manifest_sha256 / storage_key / media_status / created_at`；pending 摘要为 null，视频字节保存在私有媒体存储 |
| `events` | `event_id / zone_id / track_key / person_id`、事件类型、观测状态、起止偏移、产生方式与摘要 | 完整包写入，`recording_id` 从清单父记录关联；查询时联读录像和帧 | 已发布事实不可覆盖；无需单独事件写入 API |
| `evidence` | `evidence_id / event_id / offset_ms / bbox_json / file_name` | 完整包写入，外键关联同包事件 | 私有 `storage_key` 与当前 `media_status`；`media_url` 从读取接口派生 |
| `event_embeddings` | 向量 ID、事件／可空帧 ID、描述、数值数组、模型／版本／维度 | 事件入库后单条写入；非空 `evidence_id` 必须属于该事件 | `content_sha256 / created_at`；API 数组映射为 pgvector `embedding vector` |

新增 `recording_jobs` 保存原片任务、租约与解析状态；仍没有独立 `tracks` 或全局覆盖表。同录像的 `track_key` 与实际关键帧派生离散观测点；覆盖只从已完整导入的连续视频范围推导。`review_records` 和 AI 历史表由各自模块维护，Vision 不写入。

## Backend 提供、Vision 使用的 API

所有路径统一以 `/api/v1` 开头。下表“调用方”包含算法成员运行的授权导入／验收客户端，不表示模型自行持有写入凭据。读取事实需要有效身份、`data:read` 及目标资源授权；两项写入还需要管理员角色与 `data:ingest`，不能仅凭管理员角色绕过 scope 或资源限制。

| 方法与路径 | 调用方／时机 | 请求与结果要点 | 表／数据 |
| --- | --- | --- | --- |
| `GET /api/v1/configs/{config_version}` | Vision，分析前 | 获取固定镜头、区域与 ROI；未知版本明确报错 | 读版本化配置文件 |
| `POST /api/v1/recordings` | Android 原片入库；Vision 离线完整包导入 | `mode=source`：任务 manifest + 一个 files/source.mp4，新建202，重放200；缺省 result：完整清单 + files，新建201，重放200 | source 同事务写 pending `recordings / recording_jobs`；result 原子发布 `recordings / events / evidence` |
| `POST /api/v1/event-embeddings` | Vision 授权编码／导入作业，事件发布后 | JSON，一次一条描述文本向量；首次 201，相同规范请求重放 200 | 写 `event_embeddings`，读事件／帧／录像验证归属 |

以下复用 Android／Agent 已使用的只读 API，供真实产物回读验收，不增加视觉专用路由。

| 方法与路径 | 验收用途 | 表／数据 |
| --- | --- | --- |
| `GET /api/v1/recordings` | 可按 `camera_id` 筛选确认已发布录像可发现；当前无分页，超结果限额须缩小范围 | 读 `recordings` |
| `GET /api/v1/recordings/{recording_id}` | 核对来源、配置、时长与起始时间 | 读 `recordings` |
| `GET /api/v1/events` | 用单录像、镜头、区域和时间窗口核对事件与范围状态 | 联读 `recordings / events / evidence` |
| `GET /api/v1/events/{event_id}` | 核对同一事件、全部关键帧及实际离散位置点 | 联读 `events / recordings / evidence` |
| `GET /api/v1/evidence/{evidence_id}` | 核对帧偏移、框、事件归属和当前媒体状态 | 读 `evidence` 并校验所属事件／录像 |
| `GET /api/v1/evidence/{evidence_id}/media` | 打开实际图片字节，与源视频同时间帧对照 | 读授权媒体文件；缺失返回 404 `media_missing` |
| `POST /api/v1/events/search` | 用描述相关问题核对候选、引用及向量索引缺口 | 联读 `event_embeddings / events / evidence / recordings` |

查询条件固定一份 `recording_id + camera_id + zone_id`，再选视频偏移 `start_offset_ms / end_offset_ms` 或经过校准的 UTC `start / end`；两组时间不能混用，规范偏移窗口为 `0 <= start < end` 的半开区间 `[start,end)`。语义搜索提交 `query_text + filter + top_k`（1–50），可选 `min_score`（-1–1），Backend 选择并回显模型空间，不接客户端查询向量。

## 从源视频到可查询事实

```mermaid
sequenceDiagram
  participant V as Vision独立进程
  participant B as Backend API
  participant S as 私有媒体存储
  participant D as PostgreSQL与pgvector
  participant C as Android或Agent
  V->>B: GET configs/{config_version}
  B-->>V: 固定镜头、区域与ROI
  V->>V: 分析视频，生成最终清单和真实帧
  V->>B: POST recordings（manifest + files）
  B->>B: 验权、配置、引用、媒体和时间校验
  B->>S: 暂存并发布媒体
  B->>D: 单事务写录像、事件、关键帧
  B-->>V: 201首次发布／200相同内容重放
  V->>B: POST event-embeddings（逐条）
  B->>D: 校验并写描述文本向量
  B-->>V: 201首次写入／200相同请求重放
  C->>B: 查询事件／语义检索／回查关键帧
  B-->>C: 稳定ID、范围与媒体状态、实际图片
```

上图描述兼容的离线完整包模式（省略 mode 或 mode=result）。SDK People 原片采用下文 source 模式：先入库待解析录像，再交给独立 worker；API 请求线程不执行模型推理。任务租约与完成回报继续复用 `recording-jobs` 七操作。向量单独写入，不与录像包处于同一事务；向量导入失败时保留已发布事件，重试对应向量并检查索引缺口。

## 完整包与媒体怎么交接

清单顶层为 `schema_version + recording + events + evidence`，当前 `schema_version=0.3-mvp-draft`。具体必填字段使用 OpenAPI 的 `RecordingManifest / RecordingInput / EventInput / EvidenceInput`；可空字段仍按契约显式传 `null`，不自行省略必填项。

| 约定 | Vision 需要做到 | Backend 如何处理 |
| --- | --- | --- |
| 来源 | 真实预录使用 `source_mode=prerecorded`；事件标 `automatic` 或 `manual`；模拟用 `mock + mock` | 不允许真实／模拟来源混用；`sdk` 仅接已适配的连续视频，不接官方原生事件或离散缩略图集 |
| 文件 | 真实视频为 MP4，帧为 PNG／JPEG；每个非空 `file_name` 是唯一 basename，与上传 part 一一对应 | 校验格式、解码、时长、大小与文件集合；拒绝本地路径、URL 和客户端存储键 |
| 时间 | `duration_ms`、事件／帧偏移均为毫秒；未知真实起始时间用 `started_at=null` | 校验 `0 <= event.start <= event.end <= duration`、`0 <= frame.offset <= duration`；非空 UTC 起点需匹配服务端独立校准记录 |
| 事件 | 仅 `enter_zone / dwell / shelf_interaction / leave_zone`，另标 `observed / candidate`；不清楚的动作保留候选 | 保存来源与状态，不把货架交互升级为拿货、身份、运单或责任判断 |
| 匿名片段与帧 | `track_key` 仅在同一录像有效，断裂后换 key；`bbox_json` 为可空归一化 `[x,y,w,h]` | 检查框的数值范围；对象对应与动作真实性仍需素材验收，媒体可解码不等于已验证语义 |
| 上下文 | 关键帧可在事件前后，但必须来自同一视频且关联同包事件 | 校验引用与偏移范围；不自动验证帧确由源视频抽取，这一点由 Vision／人工核对；不补出帧间连续路线或跨镜身份 |
| 零事件 | 只有完成分析、确认没有检出时才交 `events=[] / evidence=[]`，真实视频仍上传 | 发布录像范围；后续查询可返回 `no_detections`；处理失败不得提交空包冒充成功 |
| 重试与重算 | 网络重试复用整包 ID、数组顺序及实际文件字节；重新分析使用新录像／事件／帧 ID | 相同录像 ID 与规范清单／实际文件摘要重放 200，不同内容 409 `idempotency_conflict`，不覆盖旧事实 |

真实上传的命令形状如下；`manifest.json` 必须引用对应文件名，以下文件是本地交接示意，仓库未附真实素材：

```sh
curl --fail-with-body "$PARTRACE_API_BASE/api/v1/recordings" \
  -H "Authorization: Bearer $PARTRACE_INGEST_TOKEN" \
  -F 'manifest=@manifest.json;type=application/json' \
  -F 'files=@clip.mp4;filename=clip.mp4;type=video/mp4' \
  -F 'files=@frame-001.jpg;filename=frame-001.jpg;type=image/jpeg'
```

环境变量由授权操作者在私有环境设置；不在仓库或日志记录凭据。无媒体 `application/json` 仅用于模拟包，见 [mock 导入请求](../api/examples/v0.3/recording-import-mock-request.json)，其视频／帧 `file_name` 和 `started_at` 为 `null`，媒体状态为 `missing`。不能用该样例证明真实帧上传成功。

本地默认限额为清单 2 MiB、媒体合计 64 MiB、事件与关键帧各 5000 项，实际以 Backend 部署配置为准，超限明确报错。真实上传依赖 ffprobe／ffmpeg；工具准备、受限媒体目录和 非空 UTC 按导入方声明保存，不证明拍摄起点；SDK People 保留 null，具体边界见 [Backend README](../../backend/README.md)。文件系统与数据库不能形成同一事务：明确失败时补偿清理；提交结果不明时保留可能关联的媒体，由相同 ID／内容重试核对，不能盲目换 ID 重复导入。

## 描述与向量怎么交接

| 交接项 | 当前约定 |
| --- | --- |
| 请求 | `EventEmbeddingCreateRequest`：稳定 `embedding_id`、已发布 `event_id`、可空 `evidence_id`、`source_text / embedding / model_name / model_version / dimensions`；一次一条 JSON |
| 描述来源 | 描述可由算法、人工或 VLM 生成，但必须能回到对应事件／帧核对，不能补出未观测事实；当前请求无独立描述来源字段，生成方式与版本留在交付／评估记录 |
| 编码空间 | 要被当前查询检索，编码器、预处理和版本须与 Backend 的 active 查询空间一致；写入不限定必须是 active 模型，但同一模型版本固定维度，维度为 1–16000，数组长度匹配；不预设真实模型或维数 |
| 数值 | 交接 JSON 数值数组；Backend 规范化为 float32，转换前后均须有限且非全零；返回允许 float32 舍入 |
| 派生字段 | `content_sha256 / created_at` 由服务端生成，不由 Vision 请求传入；摘要绑定规范化描述与实际事件／帧定位 |
| 重试与更新 | 相同 ID 比较全部规范请求，包含向量、模型与引用，相同 200、不同 409；不同 ID 的同源重复返回 409 `duplicate_embedding_source`；新描述／模型采用新 ID 追加 |
| 读回验证 | 搜索返回候选 Event／Evidence／Embedding ID、描述、媒体状态和索引缺口；`score` 为文本相似度，不是动作置信度 |

请求结构可看 [向量写入模拟样例](../api/examples/v0.3/event-embedding-create-request.json)，其中 mock 编码器与三维数组仅用于协议联调。当前向量是**描述文本向量**；即使使用 VLM 生成描述，也不表示图片与文本已处于共同向量空间，描述遗漏的画面属性不能由文本检索自动恢复。真实编码器尚待配置与效果验收。

## 空结果、失败与验收

| 场景 | 必须保留的含义／处理 |
| --- | --- |
| 录像范围内无命中 | `query_status=no_detections`；仅表示已导入范围未检出，不证明无人经过 |
| 超出这份视频范围 | `query_status=outside_recording`；部分相交看 `recording_range.relation=partial`，范围外保持未知 |
| 未发布或无效输入 | 缺记录返回 404，校验失败返回 422；不能解释为无采集覆盖；未校准 UTC 返回 422 `clock_unverified` |
| 文件缺失 | 元数据保留 ID、定位与 `media_status=missing`，图片字节读取返回 404 `media_missing` |
| 向量未齐或无匹配 | 有待索引事件但全部未索引时为 `search_status=unindexed`；部分索引仍可 `matched / no_matches`，须如实报告 `coverage` 中的事件总数、已索引数和 `missing_event_count`；不把缺向量解释为无事件 |
| 权限／服务错误 | 无有效身份 401，无权限 403；工具或模型不可用可返回 503，数据库等故障明确报错，不转换成空结果 |
| 内容冲突／超限 | 409 保留原记录并核对 ID 与内容；上传超限 413、内容类型错误 415，按错误修正，不重复生成新 ID 掩盖问题 |

| 顺序 | 主责 | 交付与验收 |
| --- | --- | --- |
| 1 · D03 素材 | 徐泽华 | 连续视频、固定配置／ROI、时间基准、人工对照；经过、交互／放回、携物离开画面的样本及多人／遮挡可定位 |
| 2 · D04 算法 | 徐泽华 | 可重复运行程序、最终清单、真实帧、描述与编码交接；评估表逐样本记录误检、漏检、时间偏差、Track 切换和未知区间 |
| 3 · D04 → D05 导入 | 徐泽华＋Harry Xu | 对同一包验证首次 201、原内容重放 200、变更内容 409；非法引用／坏媒体不发布半包；按 ID 读回并打开真实帧 |
| 4 · M05 检索 | 徐泽华＋Harry Xu | 真实同空间向量写入与重复请求可核对；正向问题、无匹配、部分／完全未索引、编码器失败分别记录；失败后可补写缺项 |
| 5 · D10 闭环 | 四人共同 | 同一真实事件完成 Android 查询／开帧、Agent 引用、人工保存、App 与服务重启回读；自动结果与人工标注分别报告 |

算法程序、运行参数、模型／依赖版本和检查命令在实现后维护到 [Vision README](../../vision/README.md)。算法与 worker 检查命令见 Vision README；协议样例、后端生成媒体测试和文档核对不算真实算法效果验收。源视频回放、跨镜身份关联和持续直播分析另行评审。

<a id="parcel"></a>
## P1 · Parcel 扫码与入出库

对应 [M10 包裹模块](README.md#m10)，在 **D10 主闭环验收后**实施。当前 `parcels / parcel_records` 两表和五个包裹 API 已纳入设计，扫码业务及五接口仍待实现。这里集中维护扫码流程、模块分工与联调验收；字段和状态规则参见 [包裹数据设计](../database/PARCEL_DESIGN.md) 与 [API 约定](../api/API_DESIGN.md#二维码包裹与入出库)。

**管理员建档 → Backend 返回单号 → Android 生成二维码 → 扫码精确查询 → 管理员确认入库／出库 → Backend 保存流水 → Android／Agent 回读历史。**

二维码只包含文本单号 `parcel_no`，保留前导零和大小写。重复扫码只查询，不自动建档或写流水；未建档、无效单号和接口故障分别反馈。

### 分工与数据

| 模块／主责 | 开发内容与交接 |
| --- | --- |
| Android · 李果钦 | 二维码生成、相机权限与扫码解码；按原文本单号查询，展示状态与历史；管理员显式确认后提交建档或入出库请求 |
| Backend · Harry Xu | 提供五个包裹 API；读写 `parcels / parcel_records`，校验权限与状态，在事务中分配序号、追加流水并处理重试；操作人关联 `users` |
| Agent · 韩广宁 | 通过三个 GET 只读查询包裹与历史；不提供建档、入库或出库写工具 |
| Vision · 徐泽华 | 提供既有区域事件与真实关键帧，供管理员核查候选画面；视觉算法不登记包裹或修改入出库流水 |

二维码由单号生成，不新增二维码表或保存二维码图片。包裹状态与当前位置从最大 `sequence_no` 的流水派生：无流水为 `pending`，入库后为 `in_stock`，出库后为 `outbound` 且当前位置为空，历史货架保留。

### 五个既有包裹 API

以下接口均由 Backend 提供。两项 POST 需要 `active admin + parcels:write`；三个 GET 需要 `data:read` 与资源授权。这里使用包裹权限约定，视觉结果导入的 `data:ingest` 不适用于包裹写入。

| 方法与路径 | 调用方／时机 | 数据与结果 |
| --- | --- | --- |
| `POST /api/v1/parcels` | Android，管理员确认建档 | 写 `parcels`；接稳定 `parcel_id` 和可选单号，省略单号由后端生成；建档不写入库流水 |
| `GET /api/v1/parcels?parcel_no=...` | Android 扫码／手输；Agent 只读查询 | 文本精确匹配，返回包裹与派生状态；未建档返回 `not_registered`，不自动创建 |
| `GET /api/v1/parcels/{parcel_id}` | Android／Agent，读取详情 | 读取包裹、当前状态与位置 |
| `GET /api/v1/parcels/{parcel_id}/records` | Android／Agent，读取历史 | 按 `sequence_no` 递增分页读取 `parcel_records` |
| `POST /api/v1/parcels/{parcel_id}/records` | Android，管理员确认入／出库 | 接稳定 `record_id + action`；入库另传有效且已授权的 `zone_id`，出库不传位置，由后端保留最近入库区域 |

操作人、登记 UTC 时间和流水序号由 Backend 设置。首次必须入库；在库再次入库、未入库或已出库再次出库返回状态冲突，出库后可再次入库。同一确认请求重试优先返回原记录，不因状态已改变而重复执行；同 ID 不同内容或操作人返回冲突。首版流水只追加，不支持移库、历史编辑或离线补录。

### 与视觉事件怎样交接

扫码确认的是包裹登记信息。管理员可按货架／区域和经校准的时间查询既有事件与关键帧；录像 UTC 起点未知时使用视频偏移人工核对，不能自动按登记时间关联。同一时间或同货架不能证明画面中的动作属于该包裹。

`parcel_records` 与原始视觉事件独立保存，不在 `events` 增加 `parcel_id` 或自动建立对应关系。后续若需保存管理员确认的包裹—事件关联，再评审关联表及确认依据。

### 开发顺序与验收

按 **建档／查询 → 二维码生成／扫码 → 管理员确认／流水 → Agent 只读历史** 推进，不作为 D03–D10 的前置。

- 同一单号生成与扫描一致，前导零和大小写保留；无效单号、未建档、权限不足与服务错误可区分。
- 重复扫码不写流水；稳定 ID 的请求重试不重复建档或追加，内容冲突明确返回。
- 入库 → 出库 → 再次入库的状态、货架和递增序号可核对；并发操作、非法状态和无效区域有实际验证。
- App 与服务重启后可回读同一包裹及有序历史；Agent 只读，候选画面不被当作自动包裹关联。

以上为待开发验收要求，文档归类与模拟样例不代表扫码业务已完成。


<a id="people-pipeline"></a>
## SDK People 原片 → Vision → App 状态

对应 [D06 People 管线提案](../../openspec/changes/d06-people-recording-pipeline/proposal.md)。用户所称 `recoding` 指 **`recordings`**。2026-10-17 按用户确认改为：原片先写入该表的 pending 行，并同事务排队 `recording_jobs`；Vision 完成后同事务写事件/证据、填最终摘要并将录像改为 published、任务改为 completed。pending 不代表已分析、零事件或有效覆盖。

```mermaid
sequenceDiagram
  participant C as 摄像头与Android
  participant B as Backend API
  participant D as PostgreSQL与私有媒体
  participant V as Vision worker
  C->>C: People事件结束，下载并验证真实录像
  C->>B: POST recordings (mode=source)
  B->>D: 原片引用 + recordings pending + queued任务
  B-->>C: 202 recording + job（重放200）
  V->>B: 领取任务、续租、下载原片
  V->>V: A人物建档 / B货架交互解析
  V->>B: complete (最终manifest + 真实帧)
  B->>D: 单事务发布facts + published + completed
  B-->>V: 已提交的完成结果
  C->>B: GET recording-jobs/{job_id}
  B-->>C: completed / failed 持久状态
  C->>C: 保存状态并触发App完成回调
```

原片提交示例（source.json 为 `RecordingJobInput`，文件是实际 SDK 连续 MP4）：

```sh
curl --fail-with-body "$PARTRACE_API_BASE/api/v1/recordings" \
  -H "Authorization: Bearer $PARTRACE_INGEST_TOKEN" \
  -F 'mode=source' \
  -F 'manifest=@source.json;type=application/json' \
  -F 'files=@clip.mp4;filename=source.mp4;type=video/mp4'
```

`GET /recordings/{id}` 可回读 pending 元数据；默认 `GET /recordings` 仅列 published。事件、语义与 Agent 事实查询遇到 pending 返回 `409 recording_not_ready`，优先于区域、UTC 和覆盖判断。旧 result 请求仍可直接发布完整包，但不能抢占任务保留的 pending ID。

Android 前台从所有已映射的真实设备发现 HUMAN/People 原生事件，初次启用建立时间基线，不全量上传历史。摄像头还在录制或文件下载失败时保留下载阶段，只有 SDK 完成回调且文件/时长验证通过才上传。设备和原生事件组合生成稳定任务 ID，重启和重试沿用；FACE 单独检测不当作 People。任务保存在 App 私有不备份目录。后台持续监听和系统推送不属于当前前台实现。

| API | 调用方 | 含义 |
| --- | --- | --- |
| `POST /api/v1/recordings` | Android | multipart `mode=source + manifest + files`，files 恰好一个 source.mp4；原子保存 pending 录像和 queued 任务，新建202，同内容200，异内容409；返回 recording + job |
| `POST /api/v1/recording-jobs` | 旧客户端 | 兼容 manifest + video；调用同一原片服务，同事务写 pending 录像，保留 job 返回包 |
| `GET /api/v1/recording-jobs/{job_id}` | Android | 回读 `queued / processing / completed / failed`；上传完成与解析完成分开 |
| `POST /api/v1/recording-jobs/claim` | Vision worker | 按权限领取排队任务或过期租约；并发 worker 不重复领取同一有效租约 |
| `GET /api/v1/recording-jobs/{job_id}/media` | Vision worker | 使用 Bearer 及当前 `X-Lease-Token` 下载真实 MP4 |
| `POST /api/v1/recording-jobs/{job_id}/heartbeat` | Vision worker | 推理期间续期；失去租约停止提交结果 |
| `POST /api/v1/recording-jobs/{job_id}/complete` | Vision worker | 上传既有结果 manifest 和真实关键帧；复用原片，事实发布与任务 completed 同事务 |
| `POST /api/v1/recording-jobs/{job_id}/fail` | Vision worker | 回报受限错误码；失败不发布半包或伪造成功 |

录入需要 active 管理员与 `data:ingest`；状态读取需要 `data:read`；worker 需要 `vision:work`，完整发布还需 `data:ingest`，全部按镜头及可选录像 ID 授权。租约只向领取者返回随机值，数据库只存摘要。SDK 时间保留为 `sdk_timestamp_ms`；不是独立校准的录像起点，最终 `recordings.started_at=null`。

现有 A/B 算法由 [worker](../../vision/README.md) 显式适配。A 建档不伪造区域动作；B 必须配置该机位的货架标定/区域映射，只发布原算法候选。原公开六字段事件格式保持不变，后端适配使用真实视频偏移与原始帧，匿名轨迹不升级为自然人身份。算法依赖、模型、机位角色或标定缺失须明确报错。

已有数据库使用 [增量迁移](../database/migrations/)；先有任务迁移，再执行 `migrate-source-recordings` 为既有未发布任务补 pending 行，保留已发布录像与任务状态/尝试次数；禁止重新 init-db 覆盖数据。媒体只存私有文件/对象存储，PostgreSQL 保存元数据、任务状态和引用，不存录像字节。最终状态由 App 回读持久任务后写入 StateFlow 并触发完成事件，运行中的 UI 可据此通知业务代码。此完成表示 PostgreSQL 事实事务已提交；A 本地图库 CURRENT 随后推广，不能据此承诺 B 已读取新档案。最新 dev 的 Agent run SSE 只通知 Agent 运行终态，不用于 Vision 完成。


2026-10-17 本地实测：门铃 A 的一条真实历史 People 录像已完整通过 App 下载、API 上传、真实 CPU 算法、最终 recordings 发布及 App 完成回调；球机 B 的五条自动发现 People 原片已上传排队，现场标定尚缺。A 内部选帧与人物建档不等同于业务区域事件/证据。B 的 v1 空区域配置保持不可变；新标定须发布新版本，旧 v1 原片不能声称会自动跟随新版本解析，需要显式迁移策略。完整凭据和边界见提案验证记录。

### A图库 G 编号与 B 局部 P 轨迹

B adapter保留track_key=局部P编号；新增events.person_id只采用identity.status=matched的有效A图库profile_id（G编号）。未匹配、冲突、无图库关联或复核人物不一致保持null，不用P编号冒充G。A建档不产生区域动作事件，G档案与内部选帧仍由图库管理。G编号只表示匿名外观关联，不代表人脸/自然人身份，也不关联App用户。Backend校验格式、持久化和返回，迁移保留旧行null；详见[字段约定](../database/README.md#eventsperson_ida-管理的匿名-g-编号)。
