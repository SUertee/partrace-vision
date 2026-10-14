# 功能、API 与表关联评审

**0.3-review · 功能关联评审。** 本地 0.3 草案按 2026-10-13 用户要求加入事件文本向量、AI聊天/分析历史及二维码包裹入出库，当前为 11 表、77 字段；现有 19 个后端与 8 个 Agent 操作共 27 个，覆盖监控事件闭环与包裹登记 / 管理员确认流水。接口均未实现；首页等 5 个建议仍另列。

一个接口可以联合读取多张表，不必一接口一表。下表及 [映射 JSON](feature-data-api-map.json) 记录业务读写与归属校验；认证统一核对用户身份，未在每个读取项重复列 `users`。`current_draft` 表示已写入 OpenAPI 草案，`proposed` 表示待讨论；建议接口尚未定义参数和返回结构。

完整的开发模块、主责、依赖与交付见 [模块设计](../modules/README.md)，本页侧重功能的数据覆盖与待评审缺项。

| 功能 | 当前接口 / 建议入口 | 数据来源与缺项 |
|---|---|---|
| 用户资料 | 当前 `GET /user` | `users`；仍由认证主体映射当前用户，不接收客户端 user_id 读取其他人，也不新增登录、注册或用户管理 |
| 镜头 / 区域 | 当前 `GET /configs/{config_version}`；建议 `GET /cameras`、`GET /zones` | 读取固定版本配置即可，当前没有设备或区域表 |
| 视频入库 / 预览 | 当前 `POST /recordings`、`GET /recordings`、`GET /recordings/{recording_id}`；建议 `GET /recordings/{recording_id}/media` | `recordings → events → evidence`；完整包原子写入，源视频读取复用存储键，不需媒体表 |
| 区域 / 时间检索 | 当前 `GET /events`，跨录像入口待评审 | 联查 `recordings + events + evidence`；当前限定单视频、镜头和区域，偏移／UTC 窗口二选一，UTC 要求已验证 `started_at`；尚未定义全站或跨录像查询 |
| 事件 / 片段过程 | 当前 `GET /events/{event_id}`；建议 `GET /recordings/{recording_id}/tracks/{track_key}` | 联查同视频、同 `track_key` 的事件与关键帧，不需要轨迹表；只展示实际离散点 |
| 关键帧 | 当前 `GET /evidence/{evidence_id}`、`GET /evidence/{evidence_id}/media` | `evidence`，经 `events → recordings` 校验归属；只有真实可用才返回字节 |
| 事件描述文本向量 | 当前 `POST /event-embeddings`、`POST /events/search` | `event_embeddings → events`，可引用同事件 `evidence`；后端先元数据授权过滤，再以 pgvector 同空间精确检索，返回候选供回看 |
| 人工留档 | 当前 `POST /review-records`、`GET /review-records`、`GET /review-records/{review_id}` | `review_records → events → evidence`，操作人引用 `users`；当前一条核查只针对一个事件 |
| 首页概况 / 简报输入 | 建议 `GET /overview` | 联查录制、事件、关键帧和最新核查决定；先派生汇总，无需 summary 表 |
| AI会话 / 计划 / 分析 | 当前 `/api/v1/agent` 会话、消息、计划、运行与停止8操作 | `conversations → messages / runs` 保存本人会话、问题、计划、状态和答复；会话不固定录像，每次单视频分析按 run 范围验权；消息页含任一无权助手答复则整页 403 |
| 包裹建档 / 二维码 | 当前 `POST /parcels` | `parcels` 保存稳定 ID、TEXT 唯一单号与服务器时间 3 列；可指定单号，省略时服务器生成；重试读取既有包裹及流水派生状态。Android 根据返回单号生成二维码，不需二维码表 |
| 扫码查询 / 包裹详情 | 当前 `GET /parcels?parcel_no=...`、`GET /parcels/{parcel_id}` | `parcels + parcel_records`，文本精确匹配保留前导零；状态和当前位置按最大 `sequence_no` 派生，扫码本身不写记录，未建档明确返回 |
| 管理员入出库 / 历史 | 当前 `GET/POST /parcels/{parcel_id}/records` | `parcel_records → parcels`，操作人引用 `users`；后端锁包裹、校验状态、递增序号并只追加。入库区域必填，出库保留最近入库货架；Agent 仅 GET 读取 |

全部外部路径统一使用 `/api/v1`；Agent 命名空间为 `/api/v1/agent`，提供方仍为 `agent/`。表内其余简写路径按后端 `/api/v1` 补全，统一前缀不表示网关已经部署。建议优先补首页汇总、区域 UTC 检索、单镜片段展开和源视频读取，先复用现有表，除已明确要求的向量、AI历史与包裹表外，不按页面或接口数量扩张 schema。未验证 UTC 的输入只做视频偏移查询；没有已导入视频不等于没有采集，点间过程也不能补成连续轨迹。

## 包裹已纳入当前草案

`parcels / parcel_records` 已进入 SQL、OpenAPI、映射 JSON 和现有 API 工作台的 27 操作计数。两表共 10 列，不复制当前状态、当前位置或二维码图片。建档后为 `pending`；最后入库为 `in_stock`，最后出库为 `outbound`，出库后当前位置为空而历史位置保留。单号保留前导零，二维码生成 / 扫码不自动写流水。

五个接口均由后端提供，Android 全部调用；Agent 只调用三个 GET。建档与确认入出库需 `active admin + parcels:write`，操作人、服务器登记时间和事务内递增序号不接受客户端值。同稳定 ID 重试不生成新单号 / 新记录；入库必须可用且已授权的配置区域，首次出库、重复入库 / 出库、同 ID 不同内容明确返回冲突。扫码仅确认包裹登记信息，时间相近或同货架不能自动证明画面属于该包裹，当前不建立包裹与事件外键。参数、返回和内联模拟样例见 [API 说明](API_DESIGN.md#二维码包裹与入出库) 与 [OpenAPI](openapi.json)。

包裹开发顺序为建档 / 查询、Android 二维码生成 / 扫码、管理员确认 / 流水、Agent 只读历史；前导零、重复扫码、重试不追加、入出库再入库、并发序号、权限 / 无效单号和重启回读仍需实际业务验收。

## 哪些表以后才需要

- **`cameras`、版本化 `zones`：** 只有需要在后台维护设备、区域和 ROI 时才加，届时从当前 11 表变为 13 表，并明确历史配置引用。固定摄像头 / ROI 的演示可以继续读配置文件；`camera_id/zone_id` 当前不是数据库外键，包裹入库也校验配置区域。
- **AI历史已设计：** `conversations/messages/runs` 已加入当前草案；重启保留聊天与分析状态，旧running明确failed/service_restarted，不自动续跑。尚未实现，不作为未来待加表。
- **`review_record_items`：** 恢复“整个片段 / 多事件一起保存”时再加，同时调整 `review_records` 的单事件目标与原查询范围。当前 `review_records.event_id` 外键只指向一个事件，不能直接表示整段多事件选择。
- **`tracks / track_points`：** 确实需要保存跟踪器输出的逐帧观测时再加。当前同录像 `track_key` 与关键帧 `bbox_json` 可聚合离散线索，不能证明点间连续过程、遮挡衔接或跨镜身份；新增表也不能补出未观测的数据。
- **`run_steps`：** 需要持久回读逐工具调用的输入、输出、状态与耗时时再评审。当前 `runs` 保存整体计划与终态，`plan_json.steps` 不等于执行日志；实时进度可在运行时展示，事件流接口仍需另行设计，当前 OpenAPI 未定义。新增表时应明确失败、重试与顺序记录，Agent 的监控工具仍只读。

如果下一步要保留 PRD 中的整段过程留档，优先评审 `review_record_items`：最小只需 `review_id + event_id` 两个外键，按事件时间派生顺序；同时把 `review_records` 的单事件目标调整为一次查询的人工决定。事件必须属于保存的查询范围，Agent 仍不能自动写入。这属于待确认的下一版，不直接改当前 SQL。

### 先补接口，无需新增表

固定录像、单事件核查与 AI 历史由原 9 表覆盖，新增包裹与流水后当前为 11 表。首页统计、最近核查和同录像片段展开可聚合现有表；未导入录像不能被解释为全站没有活动或没有采集覆盖。

运行历史有一个待评审的读取缺口：当前只有创建运行和按 `run_id` 读取运行，停止或失败不会写助手答复；App 重启丢失 `run_id` 时，单靠消息列表不能发现这些运行。建议下一轮评审 `GET /api/v1/agent/runs?conversation_id=...`，复用 `runs`，按本人会话、每个运行的本次 Query 录像当前权限和稳定分页读取状态。历史恢复只恢复展示，不自动续跑。此建议**尚未加入 OpenAPI、映射 JSON 或 API 工作台的当前 27 操作 / 5 建议**，不表示接口已实现。

首版仍是单驿站，不建 `stations` 或多租户成员关系；用户也不关联画面中的匿名人物。包裹扫码与入出库已纳入当前 11 表和 27 接口草案，尚未实现；电商订单关联、永久人物档案和独立摘要不在当前范围。`event_embeddings` 已加入本地草案；它存文本描述向量，PostgreSQL + pgvector 已选定；真实模型/维度未定，不代表已有检索服务或 ANN 索引。

摄像头官方 SDK / API 与团队 API 分开：设备连接、官方检测事件、直播、回放等可由 Android 按已获授权的官方能力调用；上述团队接口处理算法事件、关键帧索引和人工核查。原生设备检测不能直接当作团队的驿站动作判断。JSON 是设计映射，不是 SQL 定义或运行凭据。

技术 r51 的“向量后置”与原内存run已按用户 2026-10-13 要求在本地草案调整；未修改飞书原文。文本描述不包含的视觉特征不能靠文本向量补出；语义分数不是动作置信度，未索引或缺失媒体须如实显示。

AI 会话属于使用 App 的用户，不固定录像，也不关联监控中的匿名人物。会话元数据、用户问题及其重试只按 Bearer + active owner 验证，不要求事实 `data:read`；会话列表不因某次分析录像撤权而消失。每次计划/运行仍使用单视频 Query，状态读取、停止和重试均核对该次录像当前授权与 `data:read`，Event/Evidence 引用须属于该次范围。completed 完整答复与 run 终态原子提交，提交前重新核对授权及引用；running/stopped/failed 仅显示状态。历史消息先按消息序列确定页，再逐 assistant 经关联 run 验证同会话 completed 运行、该次录像当前授权、`data:read` 与引用归属；任一助手消息无权则整页 403，仅错误，无正文、部分结果或 next_cursor，不静默过滤。聊天历史写入权限不等于 Agent 可改监控事实、向量或人工核查。
