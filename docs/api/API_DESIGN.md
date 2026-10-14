# ParTrace Vision 最小 API · 0.3-mvp-draft

**设计草案，未实现、未冻结。** Android 主端；固定视频的事件查询、关键帧、事件描述文本向量、单事件人工核查、AI聊天/分析回读及二维码包裹入出库。数据库为 [11 表 / 77 字段](../database/README.md)，27 个接口操作与本版 [OpenAPI](openapi.json) 保持一致；包裹的五个接口已合入现有 API 页面。

需要开发的业务模块、主责、表读写与 API 交接见 [模块设计](../modules/README.md)。本页维护字段语义、范围与返回约定。

已选 **PostgreSQL + pgvector**，业务表与 `event_embeddings` 在同一个 PostgreSQL 中分表保存。SQL 时间字段用 `TIMESTAMPTZ`，JSON 字段用 `JSONB`（保留 `*_json` 名称），向量列为 `embedding vector`；API 仍是 UTC 字符串、JSON 对象/数组和 `embedding` 数组。首版在后端授权、元数据范围和同一模型空间内做精确余弦排序，之后再评估 ANN；Agent 通过后端工具访问，不直连数据库。

## 首版交接

**视觉交最终结果包 → 后端原子入库 → Android / Agent 读取 → 管理员保存 → 重启回读。**

| 数据 | 最少保存什么 |
|---|---|
| `users` | 用户 ID、显示名、管理员/查看者角色、启用状态和创建时间 |
| `recordings` | 录制 ID、镜头/配置版本、来源、视频长度、经验证的起始时间、内容哈希与存储状态 |
| `events` | 录制/区域、匿名片段标识、事件类型、观测/候选、视频起止偏移与产生方式 |
| `evidence` | 事件、关键帧偏移、可选位置框和媒体状态 |
| `review_records` | 单个事件、原查询、人工决定、说明、服务端操作人与时间 |
| `event_embeddings` | 事件/可选关键帧、可检索描述、文本向量、模型空间与服务端内容摘要 |
| `conversations` | 本人会话 ID、用户归属与创建时间，不固定录像 |
| `messages` | 用户问题、completed 完整助手答复与所属运行 |
| `runs` | 最终计划、引用、状态、模型配置标识、错误与时间 |
| `parcels` | 稳定包裹 ID、TEXT 唯一单号、服务器建档时间，共 3 字段 |
| `parcel_records` | 稳定记录 ID、包裹、递增序号、入出库动作、区域、服务端操作人与时间，共 7 字段 |

摄像头与 ROI 使用不可变配置文件；一份录制只有一套已完成的结果。索引批次、轨迹表、事件—证据中间表、Journey 分组、复核版本、清理日志均不进入首版。Agent 独立服务持久化聊天与运行历史；计划草案不单独建表，完整助手回复只在 completed 时产生。

## 接口清单

`v1` 是外部路由主版本；`0.3-mvp-draft` 是未冻结设计资料与示例版本，响应的 `api_version` 保持后者。统一公开地址设计为 `https://api.example.invalid`，Android 只配置一个 API base URL；后端与 Agent 可分模块实现，内部接入仍待实现。占位地址不代表真实服务、统一入口或网关已部署。

全部外部 API 使用 `/api/v1`；其中后端业务接口共 19 个操作，Agent 8 个，共 27 个当前草案。认证方案仍待实现；Bearer subject 由服务端映射到 `users.user_id`，不接收客户端自报身份。`admin` 可执行已授权操作，`viewer` 只读已授权监控与包裹资源，可在自己的AI会话提交问题；角色不自动扩大镜头、视频、核查或包裹记录的可见范围。首页等 5 个建议接口不计入当前契约。

| 方法 / 路径 | 用途 |
|---|---|
| `GET /user` | 认证主体映射的当前用户资料，不按客户端 user_id 读取其他人，不提供注册、登录或用户管理 |
| `GET /configs/{config_version}` | 读取这版镜头、区域与 ROI |
| `POST /recordings` | 上传视频和关键帧，原子发布最终事件包 |
| `GET /recordings`、`GET /recordings/{id}` | 录制列表、来源和时间基准 |
| `GET /events`、`GET /events/{id}` | 单视频、区域、时间查询；详情含关键帧和离散位置点 |
| `GET /evidence/{id}`、`GET /evidence/{id}/media` | 元数据与真实关键帧字节 |
| `POST /event-embeddings` | 写入一条事件描述文本向量；仅管理员 + `data:ingest` |
| `POST /events/search` | 只读语义检索；管理员/查看者 + `data:read` |
| `POST /review-records` | 管理员显式保存单事件核查 |
| `GET /review-records`、`GET /review-records/{id}` | 核查列表与重启回读 |
| `POST /parcels` | 管理员创建包裹；可指定已有单号，省略时服务器生成唯一单号 |
| `GET /parcels?parcel_no=...` | 扫码或输入单号后精确查询，明确返回匹配或未建档 |
| `GET /parcels/{parcel_id}` | 读取包裹及按最后流水派生的状态与当前位置 |
| `GET /parcels/{parcel_id}/records` | 按 `sequence_no` 递增、稳定分页读取入出库历史 |
| `POST /parcels/{parcel_id}/records` | 管理员显式确认入库或出库，记录只追加 |

Agent 仍由独立 `agent/` 模块提供，统一命名空间为 `/api/v1/agent`，共 8 个操作：

| 方法 / 路径 | 用途 |
|---|---|
| `GET /conversations`、`POST /conversations` | 本人会话列表；创建不固定录像的会话 |
| `GET /conversations/{conversation_id}/messages`、`POST /conversations/{conversation_id}/messages` | 回读消息；提交自己的问题 |
| `POST /plans` | 绑定用户消息，生成可修改 `query/query_text/steps` |
| `POST /runs` | 稳定 run ID 执行最终确认计划，保存运行状态 |
| `GET /runs/{run_id}`、`POST /runs/{run_id}/stop` | 回读分析 / 停止，终态不再追加 |

会话元数据与用户问题仅按有效 Bearer 身份及当前 active owner 验证，不要求 `data:read` 或录像授权；录像撤权不隐藏会话列表。计划、运行（含状态读取、停止及重试）需要 `data:read`，并按本次 `Query.recording_id` 验证当前录像授权与 Event/Evidence 引用归属，不以其他轮次或会话替代。助手消息通过关联 run 验证同一范围；消息分页含任一无权助手消息时整页 403，无正文或部分结果，不静默过滤。管理员也不能越过 owner。Agent 可保存自己的 AI 历史，调用监控事实工具仍只读，不导入事件、写向量或保存人工决定。

## ID、来源与时间

`users` 只保存应用用户资料，不保存密码、令牌、SDK 账号或设备凭据。首版账号如何认证/准备仍待确认，不新增注册、登录或管理 CRUD。用户角色和 token 权限分开检查：监控事实、包裹读取以及 AI 计划/运行需要 `data:read`；会话元数据与用户问题只需 Bearer + active owner，`x-required-scope=none` 表示不要求额外事实 scope，仍必须认证。消息列表通过 `x-conditional-required-scope` 在待返回页含助手消息时要求 `data:read` 与该次录像授权，任一无权则整页 403。导入需要 `data:ingest`，人工保存需要 `reviews:write`，包裹建档与入出库需要 `parcels:write`；同时满足用户角色与目标资源授权。Agent 的后端凭据只给 `data:read`，即使代表管理员执行也不能调用写接口；这些权限边界仍待实现，不新增机器用户角色。`GET /user` 返回当前已映射用户的 5 项公开资料；SQL `created_at`（TIMESTAMPTZ）转成 API 同名 `created_at`（ISO 8601 UTC 字符串）。禁用身份返回 403。

- 表名统一用复数 `snake_case`：`users / recordings / events / review_records`；`evidence` 为不可数名词保持原名。主键/外键用单数 `user_id / reviewer_id / event_id`。
- 使用稳定 `recording_id / event_id / evidence_id / review_id`。`track_key` 只在一份录制内有效，不是永久人物身份。配置文件封存后不覆盖，ROI 改变用新 `config_version`。
- 包裹建档请求复用稳定 `parcel_id`，确认入出库复用稳定 `record_id`；业务单号 `parcel_no` 使用 TEXT，`000123456789` 不得转成数字或去掉前导零。
- 来源为 `prerecorded / sdk / mock`；事件产生方式为 `automatic / manual / mock`。基础事件仅 `enter_zone / dwell / shelf_interaction / leave_zone`，另标 `observed / candidate`。货架交互不直接等于拿货、偷取或订单归属。
- `source_mode=sdk` 仅指已取得、适配成连续视频且由团队视觉处理的输入。官方原生检测事件、缩略图/离散帧集不进入这版录制包，也不能充当动作或连续轨迹。
- **视频偏移毫秒是首版定位基准。** SQL `created_at / started_at / finished_at` 统一存 TIMESTAMPTZ；API 同名字段用 ISO 8601 UTC 字符串、`Z` 后缀、最多毫秒精度。`offset_ms / duration_ms` 仍是数值毫秒，不能与绝对时间字段混用。未验证真实起始时间则 `started_at=null`，不默认偏移为 0。
- 服务端校验配置归属、ID 唯一、`0 <= event.start <= event.end <= duration` 和 `0 <= evidence.offset <= duration`。关键帧允许事件前后的上下文，但必须来自同一视频；框为归一化 `[x,y,w,h]`，且须属于对应匿名片段的实际观测。

## 查询：先限定一份视频

`GET /events` 必须传 `recording_id + camera_id + zone_id`，再选一种成对时间：

| 输入 | 处理 |
|---|---|
| `start_offset_ms / end_offset_ms` | 视频窗口 `[start,end)`，`0 <= start < end` |
| UTC `start / end` | 仅在经验证的 `started_at` 下换算偏移；未校准返回 422 `clock_unverified`，负偏移或无效范围返回 422 |

两种时间不能混用。响应始终回显规范化视频偏移；事件区间按相交判断，零时长观测按 `start <= t < end` 判断。按 `(start_offset_ms,event_id)` 排序完整返回；超部署配置的输入/结果限额须明确拒绝，不能悄悄截断结果。

| `query_status` | 含义 |
|---|---|
| `matched` | 这份视频中有命中，仍需看范围和媒体缺口 |
| `no_detections` | 请求与已导入视频有交集，但未命中；不证明无人经过 |
| `outside_recording` | 请求在这份视频范围外；不代表驿站没有采集 |

`recording_range` 仅由这份已完整导入的连续视频推导，报告 `full / partial / outside` 及实际交集。视频以外保持未知；缺少记录返回 404，导入失败返回错误，不解释为“没有采集”。首版没有全局采集台账、待处理索引或覆盖查询表。

详情中的 `evidence_ids`、`media_url` 和 `observed_points` 从外键和真实关键帧派生，不增加数据库列。离散点只展示已验证的位置，mock 不生成事实点；不把两点之间补成连续路线，不跨片段或镜头自动连线。

## 导入与媒体

`POST /recordings` 的 `manifest` 包含录制、事件、关键帧；**只接收已处理完成的整包**。处理/验证未完成、失败或媒体不合法，均不发布部分记录。

- 真实输入用 `multipart/form-data`：JSON `manifest` part + 重复二进制 `files` part；`file_name` 是唯一 basename，须与文件 part 一一对应。服务端检查字节、解码、类型、大小、配置和引用，分配私有 `storage_key`。不接受客户端路径、URL、SQL 存储键或可信哈希声明。
- 无媒体的 `application/json` 只用于 `source_mode=mock + detection_source=mock`，视频和关键帧 `file_name=null`。模拟起始 UTC 也为 `null`，所有样例媒体均为 `missing`。
- 发布前暂存媒体，服务端按规范化清单和实际文件摘要计算 `manifest_sha256`，将事实一次事务写入；媒体与数据库发布失败须补偿清理。失败不能留下看似成功的半包。
- 相同 `recording_id` 和规范化内容哈希返回原结果 200；首次成功 201；同 ID 内容不同返回 409 `idempotency_conflict`。新一轮分析用新的录制 ID，旧事件和关键帧不覆盖。

`media_status` 首版只有 `available / missing`。元数据始终保留；真实文件缺失后只更新可用状态，不改事实定位。关键帧字节端点只有实际可用才 200，缺失返回 404 `media_missing`；禁止用占位图充当成功。视频的私有存储键保留供后续回放，首版不新增回放/下载控制接口。

## 文本向量与 Agent 检索

**事件 / 帧 → 算法或 VLM 产出可查描述 → 文本编码 → 元数据过滤后语义检索 → 回读事件与关键帧。** 技术方案 r51 原先把向量后置，本地草案按用户 2026-10-13 要求加入；飞书原文未改；存储选定 PostgreSQL + pgvector，模型 provider 和维度仍待确认，未部署服务。首版全部是描述文本向量，VLM 写描述不意味着图像和文字已经在共同向量空间；描述未写“蓝衣”，文本检索不能从原图补出颜色事实。

- `POST /event-embeddings` 一次一条：`embedding_id/event_id/evidence_id/source_text/embedding/model_name/model_version/dimensions`。关键帧可空；非空须属于该事件。服务端检查有限、非全零数组、长度与 `dimensions`（1–16000），再规范化为同一 float32 编码；转换后仍须有限且非全零，响应允许 float32 舍入。版本包含编码/预处理配置。`content_sha256` 按规范化描述与真实 Event/Evidence 定位生成，时间也由服务端填；SQL `embedding vector` 对应 API `embedding` 数组，`dimensions` 保留用于校验；尚未选定真实模型，不硬编码向量维度。新描述用新 ID 追加，旧向量不改写。
- 相同 ID 重试须比较**全部规范请求**，包含规范化后的 float32 向量、模型、维度及引用；相同返回原记录 200，首次 201，不同 409。内容摘要没有包含向量和模型，不能仅比较哈希；不同 ID 重复同事件/帧、模型版本和摘要的同源数据也返回 409 `duplicate_embedding_source`。同模型版本须固定编码配置和维度，否则 422。写入只允许已授权管理员 + `data:ingest`，Agent 的后端凭据仍只有 `data:read`。
- `POST /events/search` 接 `query_text + filter + top_k(1–50)`，可选 `min_score[-1,1]`；`filter` 沿用单视频区域/偏移或经验证 UTC。**不接受客户端查询向量。** 服务端选定并回显 active 模型名/版本/维度，先权限与区域时间过滤，再在同一编码空间做 pgvector 精确余弦排序；不同空间不混排，首版不建立 ANN 索引。SQL 示例用 `MATERIALIZED` CTE 固定符合范围与模型空间的候选后再计算 `<=>`，避免把普通 `WHERE` 当作物理执行顺序；CTE 不代替服务端鉴权，授权录像范围必须由后端确定。
- 同 `(event,evidence 或空,model,version,dimensions)` 先选最新成功描述（时间、ID 稳定破同值），再按事件取最高分、排序、阈值和 top_k。返回候选 Event/Evidence/Embedding ID、描述、当前媒体状态和来源；无绑定帧时媒体状态为 `null`。`score` 是文本相似度，**不是动作置信度或事实确认**。
- 阈值/top_k 前报告 `eligible_event_count/indexed_event_count/missing_event_count`，后者为前两者之差。符合范围但全未索引必须 `unindexed`；部分索引可 `matched / no_matches`，仍报告缺口。范围内无事件可 `no_matches` 并报告录像交集；未配置模型、编码/存储故障返回 503，不模拟成功或伪装零命中。
- Agent 只读工具为 `semantic_search_events → get_event / get_evidence`，需要时仍可用普通 `search_events`。真实事实引用须能回到有效事件与可打开帧；缺图只能说明候选/未知。运行保留确认计划与引用供历史回读，不把检索分数写成人工决定。

摘要拟议规范为：描述首尾 `strip`；对按键排序的 UTF-8 紧凑 JSON 求 SHA256，内容为 `source_text`、事件的 ID/录像/区域/片段/起止偏移、录像镜头/配置版本，以及关键帧 ID/事件/偏移/框或 `null`；数组顺序保留，媒体当前状态不参与。

新增样例采用 `mock-text-encoder / mock-v1-preprocess-demo`、3 维 `[0.1,0.2,0.3]`，绑定 mock 事件和缺失媒体；相似度是模拟数值，没有真实模型或向量索引。真实查询不使用 mock 事件/模型补齐，模拟空间仅用于明确的 mock 录像评审。部分索引样例使用独立 `rec_mock_partial` 两事件包，仅第一事件有向量，不与单事件导入包混用。JSON Schema 检查不代替实际有限数值、模型空间、定位归属和检索效果验证。

## AI聊天与分析回读

`runs` 每行代表一次已确认计划的分析执行；同一会话可有多次运行，终态不复用。表名简化不改变 `/api/v1/agent/runs` 路由、`AgentRun` 数据格式或 `run_id`。

**创建会话 → 保存用户问题 → 为本次分析选择单视频范围 → 查看 / 修改计划 → 确认运行 → 回读状态或完整答复。** `POST /plans` 从 `input_message_id` 读取问题，`POST /runs` 保存 `plan_json={query,query_text,steps}`；真正执行的语义问题不再依赖上一轮内存。

- `conversations` 只存 ID、服务端用户和创建时间，不固定录像；同会话的不同分析可查询不同录像，每次 `Query` 仍是单视频。会话列表按 `(created_at DESC,conversation_id DESC)` 分页，仅 active owner 可读，某次录像撤权不隐藏会话；cursor 绑定 owner、排序和 limit。消息按 `(created_at ASC,message_id ASC)` 分页，cursor 绑定 owner、会话、排序和 limit。
- 客户端会话 / 用户消息 / 运行分别带稳定 `conversation_id/message_id/run_id`。同 ID、owner 和各自规范化请求重试回读原资源；会话创建仅比较自身请求，不比较录像，用户问题重试不要求录像权限。运行重试必须先校验该次 `plan.query` 的当前录像权限再回读，不启动重复分析；不同内容 409，非 owner 或禁用用户 403。
- 用户消息请求只含 ID 和正文，仅按 active owner 保存；服务端固定 `role=user,run_id=null`。客户端不能提交助手角色、引用、时间、模型配置或运行终态。助手消息只有 completed 才保存，必须同会话且一 run 一条；保存前重新验证该 run 的 `data:read`、本次录像当前授权与引用归属。
- 历史消息先按未经授权过滤的消息序列确定待返回页；user 问题只按 active owner 验证。页中每条 assistant 消息通过 `run_id` 验证同会话 completed 运行及其 `plan.query` 当前录像授权、`data:read` 与引用归属。任一助手消息无权则整页返回 403 `ErrorResponse`，不返回任何正文、部分消息、`next_cursor` 或运行信息；禁止静默过滤或跳过无权消息。全页通过后才返回结果。
- `runs` 保存输入消息、状态、最终计划、引用、`model_key`、错误、创建及结束时间。本次范围取自 `plan.query`，计划生成、运行创建、状态读取、停止和重试均核对 active owner、`data:read` 与该次录像当前授权。Event 必须来自该次录像并命中查询范围，Evidence 必须属于对应 Event，可保留事件前后的上下文帧；回看重新读取当前媒体状态，媒体缺失不等于权限撤销。`model_key` 是固定模型 + 运行配置版本标识，不含密钥；SQL `created_at / finished_at` 的 TIMESTAMPTZ 映射为 API 同名 UTC 字符串；计划与引用 JSON 在 SQL 中保存为 JSONB。`references_json` 是经过校验的 Event/Evidence 逻辑ID引用，不是真实SQL外键或临时媒体URL。
- completed终态与完整助手消息在同一事务提交；摘要从该消息正文读，不重复存run。running / stopped / failed只展示状态，`summary=null`，不保存伪完成答复；停止后晚到回调不能再写消息、引用或状态。
- 服务重启把遗留 running 标为 failed，错误码 `service_restarted`，不自动继续；已有消息、计划和状态可以回读。需要重试时提交新run ID，原失败历史保留。此处是持久化设计，尚未运行服务。

## 人工保存：一次一个事件

`POST /review-records` 提交 `review_id + event_id + query + decision + note`。`decision` 为 `confirmed / dismissed / uncertain`；人工决定不修改原事件的类型、观测状态或位置。

服务端把查询转换为固定 5 字段 `{recording_id,camera_id,zone_id,start_offset_ms,end_offset_ms}`，验证区域/镜头一致、事件命中，保存窗口须满足 `0 <= start < end <= duration`。保存前要求当前用户存在、`status=active` 且 `role=admin`，并通过该事件的授权校验；`reviewer_id` 由服务端填入并以外键引用 `users.user_id`。操作人与时间由服务端填写；不接受客户端快照或身份字段。

一条核查自动引用该事件的全部不可变关键帧，回读时由外键取得事件和关键帧；媒体缺失也保留 ID。首版不编辑、不分组、不维护复核历史。相同 `review_id`、身份和规范化请求（排除服务端字段）重试返回原保存结果 200；同 ID 不同内容 409，其他身份不能借重试取得记录。首次保存 201；服务重启后原查询、决定和事实引用仍可回读。

## 二维码、包裹与入出库

**管理员建档 → 后端返回单号 → Android 生成二维码 → 扫码精确查询 → 管理员确认入库 / 出库 → 保存和回读流水。** 二维码仅包含 `parcel_no`，不保存二维码图片，不包含写入动作；重复扫码只查询。首版单号接受 1–100 字符的 ASCII 字母、数字及 `_ . : -`，按大小写敏感文本精确匹配，不自动去零、转数值或改大小写。任意外部网址或文本不能直接当作有效单号。

`POST /parcels` 只接 `parcel_id` 和可选 `parcel_no`。省略单号时后端生成系统内唯一单号；不能传 `null` 或空字符串。首次返回 201 和 `pending`；同 ID 重试返回 200 既有包裹，省略单号时复用原生成值，状态按当前流水派生。同 ID 指定不同单号返回 409 `idempotency_conflict`，另一 ID 占用同单号返回 409 `parcel_no_conflict`。建档不追加入库记录。

`GET /parcels` 必填 query 参数 `parcel_no`，唯一单号最多返回一个包裹：200 `lookup_status=matched` 带包裹；200 `not_registered`、`parcel=null` 明确未建档，不自动创建。`GET /parcels/{parcel_id}` 按稳定内部 ID 返回包裹，缺失为 404 `parcel_not_found`。依赖故障返回错误，不能伪装成未建档。

| 最近一条流水 | `status` | `current_zone_id` | `last_sequence_no` |
|---|---|---|---|
| 没有流水 | `pending` | `null` | `0` |
| `inbound` | `in_stock` | 最近入库区域 | 最近序号 |
| `outbound` | `outbound` | `null` | 最近序号 |

状态、当前位置和最后序号从同一读取快照中派生，`parcels` 只存三列；不再复制状态或位置。`GET /parcels/{parcel_id}/records` 按 `sequence_no ASC` 分页，`limit` 为 1–100、默认 20，已有包裹无流水返回空数组。`next_cursor` 绑定包裹、身份 / 授权、limit 与第一页的最大序号快照；后续追加记录不扰动这一轮分页，刷新第一页读取新记录。末页为 `null`；无效游标、未知参数或换包裹 / limit 返回 422。历史排序依据序号，同一毫秒的记录也有明确顺序。

`POST /parcels/{parcel_id}/records` 只接 `record_id + action`；入库另传 `zone_id`，出库禁止客户端传位置。入库区域必须属于当前单驿站已确认版本配置且可用、已授权，无效区域 422 `invalid_zone`；`zone_id` 暂不是数据库外键。出库由后端保留最近入库的区域，当前位置同时派生为 `null`，历史仍可回看原货架。服务端根据认证身份填 `operator_id`，使用实际登记 UTC 填 `created_at`，不接受自报身份、时间、序号、状态或离线补录。

后端在事务中锁定包裹，先检查已有 `record_id`，再验证状态、分配同包裹递增且唯一的序号并追加。首次必须入库；在库再次入库、未入库或已出库再次出库均返回 409 `parcel_state_conflict`；出库后允许再次入库。相同 `record_id + parcel_id + 当前操作人 + 确认内容` 重试返回 200 原记录，首次 201；同 ID 不同内容 / 操作人返回 409 `idempotency_conflict`。重试优先于状态转换校验，即使后来已有新操作，也返回原序号、原操作人、原时间和原货架，不能按当前状态再次执行。出库重试不重新计算货架。记录只追加，首版不支持移库、历史编辑或离线补录；事务失败不留下部分登记。

两项 POST 均要求 `active admin + parcels:write` 并由 Android 显式确认。三个 GET 要求 `data:read` 与当前资源授权，供 Android 和 Agent 只读工具调用；Agent 和视觉算法不能登记或确认入出库。首版单驿站授权来自部署配置，不新增 `stations` 或机器用户角色。扫码只确认登记信息；时间相近或同货架不能证明某视觉事件属于该包裹，当前不在 `events` 强加 `parcel_id`。

包裹请求、待入库 / 在库 / 已出库、未建档、空历史、入出库再入库、重试和冲突样例直接写在 [OpenAPI](openapi.json) 的这五个操作中，均为模拟契约样例，没有真实包裹业务执行凭据。

## 错误、样例与开发顺序

JSON 成功响应与错误均带 `api_version=0.3-mvp-draft`，严格限制未知字段。错误统一 `error{code,message,request_id}`：401/403 身份、404 缺失、409 ID 内容冲突、422 字段/范围/配置、413/415 上传、500/503 处理或依赖失败。系统失败不得返回成功空集合。

[v0.3 模拟样例](examples/v0.3/) 与 OpenAPI 的 `x-fixture-schemas` 一一对应，包裹另使用操作内联样例，只检验形状和交接。没有真实文件、真实设备、包裹操作或可核查 AI 事实；模拟 run 的事实引用为空。Agent 的事实句必须指向有效 Event 和真实可打开 Evidence，缺失媒体只能说明候选/未知。

开发依次做：**固定配置/真实视频 → 最终包导入和重复请求 → 事件/关键帧读取 → 人工保存及重启回读 → 描述向量入库 / 同空间检索 → Agent 只读引用。** 真帧上传/取回、时钟验证、导入失败补偿、跨对象校验、保存重试和 Agent 停止行为仍需实际服务验收。数据库与向量扩展已选 PostgreSQL + pgvector；媒体存储、模型和服务框架仍待确认，本次静态草案不代表已建生产库或业务完成。

包裹独立按 **建档 / 查询 → Android 二维码生成 / 扫码 → 管理员确认 / 流水 → Agent 只读历史** 推进，不依赖视觉自动登记。用同一单号验证前导零、生成 / 扫描一致、重复扫码不写入、重复请求不追加、入库后出库及再次入库顺序、无效单号、权限与状态冲突、并发序号和服务重启回读；这些均待真实实现验收。

[v0.2 完整方案及旧样例](archive/0.2.md) 仅作历史参考，不能混用；0.1 `demo-bundle.json` 同样不是当前导入格式。
