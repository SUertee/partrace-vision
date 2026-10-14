# 最小数据库设计

**PostgreSQL + pgvector · 0.3-mvp-draft · 11 张表、77 个字段。** 当前设计包含「视频 → 事件与关键帧 → 结构化／语义检索 → 人工核查保存」、AI 会话与分析历史，以及包裹建档与管理员确认的入出库流水，尚未部署业务数据库或实现服务。

关系图按五个数据模块分组：**视频素材**（`recordings`）、**事件与检索**（`events / evidence / event_embeddings`）、**AI 会话与运行**（`conversations / messages / runs`）、**账号与核查**（`users / review_records`）、**包裹与入出库**（`parcels / parcel_records`）。这是业务数据的分组；接口可跨模块读写，团队分工见开发计划。

| 表 | 字段数 | 保存什么 | 主要开发模块 |
| --- | ---: | --- | --- |
| `users` | 5 | App 用户名称、角色、启用状态和创建时间 | [M02 账号](../modules/README.md#m02)；AI／核查／入出库身份归属 |
| `recordings` | 10 | 视频来源、摄像头配置、时间起点、时长与文件状态 | [M03 入库](../modules/README.md#m03)、[M04 查询](../modules/README.md#m04) |
| `events` | 10 | 区域事件、视频内匿名片段、发生时间与候选说明 | [M03 产出／入库](../modules/README.md#m03)、[M04 查询](../modules/README.md#m04) |
| `evidence` | 6 | 事件的关键帧、视频偏移、可选检测框与文件状态 | [M03 入库](../modules/README.md#m03)、[M04 证据回看](../modules/README.md#m04) |
| `event_embeddings` | 10 | 事件描述文本向量、模型版本和关联关键帧 | [M05 语义检索](../modules/README.md#m05) |
| `review_records` | 7 | 原查询、所查事件、人工结论、说明、操作人与保存时间 | [M07 人工核查](../modules/README.md#m07)、[M08 首页](../modules/README.md#m08) |
| `conversations` | 3 | 用户的 AI 会话，不固定视频 | [M06 AI 会话](../modules/README.md#m06) |
| `messages` | 6 | 会话中的用户问题与完整助手答复 | [M06 聊天历史](../modules/README.md#m06) |
| `runs` | 10 | 一次分析的执行计划、状态、模型配置版本、证据引用与错误 | [M06 分析运行](../modules/README.md#m06) |
| `parcels` | 3 | 内部包裹 ID、二维码使用的唯一文本单号与建档时间 | [M10 P1 包裹](../modules/README.md#m10) |
| `parcel_records` | 7 | 包裹内序号、入出库动作、区域、操作人与实际登记时间 | [M10 P1 入出库](../modules/README.md#m10) |

模块列标出主要开发归属；事实表还被语义检索、AI、人工核查和首页联读。全部开发内容、接口及依赖见 [模块设计](../modules/README.md)，逐 API 的完整读写表见 [API 映射](../api/api-module-map.json)。

一段视频有多个事件，一个事件有多张关键帧，也可有多条独立核查记录。关键帧属于一个事件，暂不做多对多共享。一个用户可保存多条核查记录，`review_records.reviewer_id` 外键引用 `users.user_id`。一个包裹有多条入出库记录，`parcel_records.parcel_id` 引用 `parcels.parcel_id`，`operator_id` 引用 `users.user_id`。

[打开关系图](schema-canvas.html) · [在工作台查看](../workbench/index.html#database) · [SQL 字段定义](schema.sql) · [最小 API 草案](../api/API_DESIGN.md)

点表展开字段时，整张关系图等比例缩小，保持模块、表和连线的原有位置；关闭后恢复原倍率。字段区不缩小，手动放大和“适配宽度”仍可使用。

[功能、表与 API 对照](../api/FEATURE_DATA_API_MAP.md) 说明了首页、区域查询和片段展开如何复用现有表。既有视频核查与 AI 会话仍由原 9 表覆盖；包裹两表已加入当前 SQL 和同一关系图。关系图页尾“还需要哪些表？”保留其余扩展的增表条件；右侧“相关 API”仍区分当前草案与建议新增接口。

2026-10-13 用户确认二维码、包裹单号与入出库设计，`parcels + parcel_records` 已加入当前 SQL、真实 PostgreSQL 目录与现有数据库设计页，共 11 表。二维码由单号生成，扫码先查询、管理员确认后才写流水；包裹五个接口已加入当前 OpenAPI 草案，Android、后端与 Agent 业务实现待开发。

下一轮更优先评审按会话查询运行历史的接口，复用 `runs` 找回失败、停止或未结束的分析。当前消息历史只保留用户问题与完成答复，不能列出所有运行；候选 `GET /api/v1/agent/runs?conversation_id=...` 尚未加入 OpenAPI。

## 包裹与入出库

`parcels` 只存 `parcel_id`、唯一且非空的文本 `parcel_no` 与 `created_at`。单号保留前导零，二维码由单号生成，不另建二维码表或保存二维码图片。先建档并查询，再由管理员确认操作；扫码本身不追加记录。

`parcel_records` 只存 `record_id`、`parcel_id`、`sequence_no`、`action`、`zone_id`、`operator_id` 与 `created_at`。`action` 为 `inbound / outbound`；入库必须填写非空区域，出库由后端保留最近入库位置。区域沿用版本化配置，暂不是数据库外键。启用的管理员身份与登记时间由后端填写，Agent 只能读。

没有流水时状态为 `pending`；最大 `sequence_no` 的动作是 `inbound` 时为 `in_stock`，是 `outbound` 时为 `outbound`。当前位置从最新入库流水派生，出库后当前位置为空，历史区域仍可查看。包裹表不重复存储状态或位置。

SQL 保证单号唯一、真实包裹与用户外键、正序号及同包裹序号唯一、入库区域必填和有限时间；触发器禁止包裹身份与建档时间更新，并禁止流水更新或删除，保留历史。`uq_parcel_no` 支持单号精确查询，`uq_parcel_record_sequence` 支持按包裹读有序历史及最新记录，操作人索引用于审计。

**跨记录业务规则由服务事务保证。** 后端锁定包裹行（如 `SELECT ... FOR UPDATE`），先比较稳定 `record_id` 的规范化确认内容，再读最后一条流水、校验状态、分配序号并追加。首次必须入库，在库重复入库或出库后重复出库返回冲突；出库后可再次入库。同 ID 同内容重试返回原记录，同 ID 异内容返回冲突。SQL 的 CHECK 与唯一约束不保证序号连续、动作交替或出库区域正确；服务尚未实现这些事务规则。

包裹操作不自动写入或改变视觉 `events`。按时间、摄像头和区域只能找候选画面，录像 UTC 起点未校准时不能按实际登记时间自动关联；当前没有包裹—事件关联外键。

## 事件描述向量

按用户 2026-10-13 补充，新增 `event_embeddings`；旧技术方案中向量后置的约定在本地草案中调整，尚未修改飞书原文或部署服务。

| 字段 | 用途 |
| --- | --- |
| `embedding_id` | 向量记录编号 |
| `event_id` | 所属事件，真实外键 |
| `evidence_id` | 可选，描述依据的同事件关键帧；复合外键防止错配 |
| `source_text` | 实际编码的描述快照，包含可观测的外观、动作与不确定说明 |
| `embedding` | pgvector 原生 `vector`；API 仍交接数值数组，按相同模型版本与维度检索 |
| `model_name` | Embedding 模型名称 |
| `model_version` | 模型及编码/预处理版本 |
| `dimensions` | 向量维度，与数组长度一致 |
| `content_sha256` | 后端从规范化描述和来源定位生成的内容摘要 |
| `created_at` | 后端生成的入库时间 |

流程为：**视觉产出事件与帧 → 生成有依据的描述 → Embedding 模型编码 → 关联事件入库 → Agent 只读检索并回查画面。** 例如描述已包含“蓝衣人员靠近 E3”，才能用这类语言检索；未提取的外观信息不会被文本向量自动补出。描述与相似度用于候选查找，不改变原事件的观测/候选状态，也不编码人工核查结论作为视觉事实。

- 先筛选授权录像、区域和时间，再使用同 `model_name + model_version + dimensions` 的向量比较。版本包含输入处理规则；不同模型空间不混排。
- 同事件可有事件级或基于某关键帧的文本描述。新描述追加新 ID；查询按每个事件/可选关键帧/模型空间选最新成功描述，再按事件去重候选。
- 只保存生成成功、有限且非零的数值数组。未生成向量要报告未索引；部分索引显示覆盖数量，故障返回错误，不能伪装为没有匹配。
- 关键帧若关联必须属于同一事件；文件失效后保留向量与 ID，但返回当前画面状态。只关联事件时 `evidence_id` 可空，须继续回查关键帧。
- 新增草案接口 `POST /event-embeddings`（算法/后端写入）和 `POST /events/search`（只读检索），统一前缀 `/api/v1`。Agent 仅获读取权限。

首版做**描述文本向量**。图文直接互查需要支持图文对齐的模型，可参考 [CLIP 原论文](https://arxiv.org/abs/2103.00020)；普通 VLM 生成文字描述不等于已提供这类向量。存储已选 PostgreSQL + pgvector，模型与维度待定。当前 `vector` 不硬编码维数，检查 `vector_dims(embedding) = dimensions` 且非零，最多 16000 维；先做授权范围和相同模型空间内的精确余弦检索。HNSW/IVFFlat 等 ANN 索引待固定模型空间并测过召回和延迟后再加。

## AI 会话与分析

按用户 2026-10-13 补充，AI 聊天与分析历史进入本地草案：

| 表 | 最小字段 |
| --- | --- |
| `conversations` | `conversation_id`、`user_id`、`created_at` |
| `messages` | `message_id`、`conversation_id`、`role`、`content`、`run_id`、`created_at` |
| `runs` | `run_id`、`conversation_id`、`input_message_id`、`status`、`plan_json`、`references_json`、`model_key`、`error_json`、`created_at`、`finished_at` |

一位用户可以开启多个会话；会话不固定录像。同一会话有多条消息和多次运行，每次运行仍查询一段录像，不同运行可以选择不同录像。用户消息的 `run_id` 为空；每次完成的运行最多写入一条助手答复，答复正文只存 `messages.content`。

SQL 校验运行输入是同会话的用户消息、本次查询录像存在；摄像头／区域对应关系、当前权限和 JSON 证据引用仍由服务验证。

`runs` 表示一次确认计划后的录像检索分析；可针对同一条用户消息创建新的运行，每次使用独立 `run_id`。

`plan_json` 保存真正执行的 `{query,query_text,steps}`，包括用户最终确认的范围与语义检索文本，不另建计划表。`references_json` 只保存 Event/Evidence ID；这两类 JSON 引用是逻辑关联，不是 SQL 外键。服务必须验证关键帧属于所引用事件，事件属于本次运行查询的录像及用户当前可访问的范围，回看时重新读取当前媒体状态。

运行状态沿用 `running / completed / stopped / failed`。只保存完成的助手正文；流式中间文字不落库，停止或失败展示运行状态。`model_key` 指向固定的模型与运行配置版本，不保存凭据。重启后历史可回读，旧的未结束运行须明确记为 `failed`，错误为 `service_restarted`；不表示已实现自动续跑。

会话及用户问题只允许启用的本人访问，不要求选择录像。计划与运行按本次查询录像核对当前权限；助手消息按关联运行核对。历史消息待返回页若含任一无权读取的助手答复，整页返回 403，不返回正文或部分结果；会话列表仍可查看。Agent 服务可以持久化自己的 AI 历史；其监控事实、向量和人工核查工具继续只读。AI 答复不是视觉事实，也不会自动写入 `review_records`。

### AI 项目存储对照

2026-10-13 核对源码后，表名简化为 `conversations / messages / runs`，仍保留会话、消息正文、每次分析执行三种数据；`run_id` 和 HTTP `/api/v1/agent/runs` 不变。

| 项目与核对版本 | 实际结构 | 与本项目的区别 |
|---|---|---|
| Dify · `69b9b48` | [conversations / messages](https://github.com/langgenius/dify/blob/69b9b486402b3482866163db72a6c943f4906c1f/api/models/model.py#L1440) 与 [workflow_runs](https://github.com/langgenius/dify/blob/69b9b486402b3482866163db72a6c943f4906c1f/api/models/workflow.py#L757) | 一行消息含问题与答复；运行记录服务于配置化工作流。我们按角色分行，只记录录像检索分析。 |
| Open WebUI · `f8ae8a6` | [chat](https://github.com/open-webui/open-webui/blob/f8ae8a6c328946dc62cd0712961a0632574581bf/backend/open_webui/models/chats.py#L131) 保留 JSON 历史，另有 [chat_message](https://github.com/open-webui/open-webui/blob/f8ae8a6c328946dc62cd0712961a0632574581bf/backend/open_webui/models/chat_messages.py#L130) 行模型 | 支持消息父子关系、完成状态及用量；当前写入路径同步历史与消息行。首版暂不做消息分支或双份历史。 |
| LangGraph · `afbe7e4` | [PostgresSaver 的 checkpoints / checkpoint_blobs / checkpoint_writes 与迁移表](https://github.com/langchain-ai/langgraph/blob/afbe7e48b17f62e139b5c6337ab934549e6dc73b/libs/checkpoint-postgres/langgraph/checkpoint/postgres/base.py#L44) | 保存可恢复的执行状态，不能代替应用层聊天与分析历史。当前没有自动续跑要求。 |
| OpsClaw · 本机 `2047d03` | `tasks / runs / interaction_logs`，`chat_conversations` 绑定 Run/OpenCode Session | Run 带任务、工作目录和工具权限等执行上下文，可在聊天中多轮复用。我们一行 Run 是一次分析，引用录像事件与证据。 |

OpsClaw 依据本机该版本的 `opsclaw/db/models.py` 与 `opsclaw/core/chat/service.py`，未读取实际记录或凭据；同名不代表生命周期相同。表的粒度和字段由本项目的查询、停止、引用及历史回读需求决定，不加入工作流节点、执行工作目录或任务平台表。

## 命名与用户

表名统一用复数、小写 `snake_case`，包括 `users`、`events`、`conversations`、`messages`、`runs` 等。`evidence` 是不可数名词，保持原名；主键/外键仍用单数，如 `user_id`、`event_id`。这是本项目约定，不是 SQL 的硬性要求。

时间字段统一命名为 `created_at`（创建）、`started_at`（录像开始）和 `finished_at`（运行结束）。PostgreSQL 使用 `TIMESTAMPTZ`，检查时间有限且不早于 Unix 起点；API 的同名字段仍用 ISO 8601 UTC 字符串。`offset_ms`、`duration_ms` 等视频偏移与时长继续保留 `_ms`。

`users` 表示使用 App 的账号，与监控中的匿名 `track_key` 无关联。首版字段：`user_id`、`display_name`、`role`（`admin / viewer`）、`status`（`active / disabled`）、`created_at`。鉴权方案待定，不预置密码或 SDK 设备凭据。服务端将可信身份映射到已有用户；启用的管理员才可保存核查或确认入出库，操作人由服务端填写。账号停用后保留历史，已有核查或入出库记录引用的用户不能直接删除。

## 首版规则

- **输入先收窄：** 只导入连续视频的完整分析包；失败整包回滚。摄像头、区域与 ROI 放不可覆盖的配置文件，录像保存 `camera_id + config_version`。SDK 原生事件与离散帧包先留在验证支线。
- **时间只存一套：** 录像有已验证的 UTC 起点时才填 `started_at`；事件和帧只存视频偏移毫秒。未知 UTC 仍可按视频偏移回看，不参与确定的日期查询。
- **结果不覆盖：** 每个 `recording_id` 对应一份最终分析。同 ID、同清单内容重试返回原结果；不同内容返回冲突。重算或重新校时新增 `recording_id`，旧事件和关键帧保持不变。
- **路线来自实际点：** `track_key` 只在一段视频中有效；跟踪丢失后换 key。按关键帧的时间与检测框显示离散观测点，点间保持未知，不当作连续路径或人物身份。
- **核查只追加：** 一条记录只查一个事件，自动引用该事件的全部固定关键帧；`query_json` 仅保存录像、摄像头、区域和起止偏移五项条件。结论为 `confirmed / dismissed / uncertain`，人工结论不改变原始候选事件。重试沿用 `review_id`，服务端比较已保存的规范化请求；不同内容返回冲突。
- **文件与记录分开：** 数据库存服务端 `storage_key`，不存图片、视频或临时 URL。媒体为 `available / missing`；文件丢失仍保留事件和核查记录。可用状态须经真实文件验证。
- **状态如实返回：** 完整分析中没有匹配事件，是「未检出」；查询超出录像范围，是「无此范围覆盖」。导入失败返回错误，不能当作零活动或没有采集。

首版不单独建 `cameras`、`zone_configs`、`index_imports`、`track_segments`、`coverage_intervals`、`event_evidence`、`journeys`、`journey_items`、`review_actions` 或清理日志表。索引多版本、复杂动作、电商订单关联、多事件分组、复核编辑与定时清理按后续需求再加。

## 建表、检索与验证

业务数据与向量分表保存在同一个 PostgreSQL 中；`*_json` 使用 `JSONB`，`embedding` 使用 pgvector `vector`，视频偏移/时长使用 `BIGINT`。原 SQLite 稿保存在 [历史归档](archive/schema-sqlite-0.3.sql)，不作为当前建表文件。

[schema.sql](schema.sql) 用于新建空库，不是现有生产库迁移。它启用 `vector` 扩展并一次性创建表、索引与约束函数；不执行删除或覆盖已有表。开发环境需有安装 pgvector 的 PostgreSQL，本次验证采用 PostgreSQL 17 / pgvector 0.8.7。

[semantic-search.sql](semantic-search.sql) 是只读 `PREPARE` 示例：后端先检查用户身份、录像权限、范围和模型配置；`MATERIALIZED` CTE 限定候选录像与模型空间，再做精确余弦排序，选每个来源最新描述并按事件去重。它不代替鉴权、向量生成或 `matched/no_matches/unindexed` 统计，尚无运行 API 服务。

可在自己创建的隔离开发容器中验证（`partrace-check` 是示例容器名）：

```sh
docker exec -i partrace-check psql -X -U postgres -d partrace_check -v ON_ERROR_STOP=1 < docs/database/schema.sql
python3 scripts/export-schema-catalog.py --container partrace-check --database partrace_check
python3 scripts/export-schema-catalog.py --container partrace-check --database partrace_check --check
python3 scripts/build-schema-canvas.py
python3 scripts/build-schema-canvas.py --check
python3 scripts/sync-workbench-schema.py
python3 scripts/sync-workbench-schema.py --check
```

导出器读取真实 PostgreSQL 系统目录，包含字段、默认值、外键、索引、CHECK、触发器及其函数；不读取业务记录。SQL 改动后必须重新加载空库并导出。离线 `export-schema-catalog.py --check` 只检查已保存目录的来源哈希、定义完整性及注释一致；带数据库连接的 `--check` 才比较实际数据库目录。HTML 和工作台的检查验证生成文件与目录/API 的一致性。

本地建表与约束验证不能替代生产部署、真实媒体、模型编码、性能或业务闭环验收；当前没有运行后端、真实模型或 ANN 索引。
