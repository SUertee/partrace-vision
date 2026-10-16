# 最小接口与数据约定

当前草案覆盖 **单视频 → 事件与关键帧 → 描述文本检索 → AI会话 / 分析回读 → 人工核查**，并已纳入 **包裹建档 → 二维码生成 / 扫码查询 → 管理员确认入出库 → 流水回读**；实际实现放在 Android、后端、Agent 和视觉四个目录。

| 资料 | 用途 |
|---|---|
| [全部业务模块与 DB／API 关联](../modules/README.md) | 从需要开发的功能查看实现主责、表读写、接口与工作包 |
| [按模块查看 API](api-canvas.html) · [工作台 API 页面](../workbench/index.html#api) | 四模块交接、提供方/调用方、开发顺序及请求/返回样例 |
| [API 说明](API_DESIGN.md) | 0.3 最小范围、查询、导入、人工保存、包裹入出库和顺序 |
| [功能 → 表 → API 对照](FEATURE_DATA_API_MAP.md) | 现有功能覆盖、待补接口与新增表的条件 |
| [OpenAPI](openapi.json) | `0.3-mvp-draft`：19 个后端操作 + 9 个独立 Agent 操作，共 28 个 |
| [Agent 接口对接文档](AGENT_API.md) | 前端对接：9 个 Agent 接口的鉴权、参数、返回、错误与 SSE 协议 |
| [v0.3 模拟样例](examples/v0.3/) | 完整/范围外/缺失媒体/重试/错误交接；包裹样例内联于 OpenAPI；无真实业务执行 |
| [数据库](../database/README.md) · [离线关系图](../database/schema-canvas.html) | 11 表、77 字段；HTML 双击可打开 |

## 按开发模块交接

| 模块 | 主责 | API 工作 |
|---|---|---|
| Android | 李果钦 | 调用后端与 Agent，接查询、证据、会话、人工保存、二维码及入出库确认页面 |
| 视觉算法 | 徐泽华 | 产出事件包、真实关键帧和描述/向量，交给授权导入作业 |
| 后端 | Harry Xu | 提供 19 个数据接口，校验权限并持久化业务、向量、媒体引用与包裹流水 |
| Agent | 韩广宁 | 显式启用 9 个 AI 接口，转计划、只读事件检索、会话、运行状态与终态推送；包裹工具后补 |

先用共享样例并行开工；监控真实交接按 **事件与证据 → 人工留档 → 语义检索 → AI 会话运行 → 首页补齐** 验收。语义检索依赖事件/证据，可与留档并行；AI 服务入口和 App 骨架也可先准备。包裹业务另按 **建档 / 查询 → Android 二维码生成 / 扫码 → 管理员确认 / 流水 → Agent 只读历史** 推进。当前契约仍为 28 个操作。development 默认实现原 12 个基础数据操作及两个向量操作，共 14 个；显式 `PARTRACE_AGENT_ENABLED=true` 挂载九个 Agent 操作后共 23／28。剩余 P1 包裹 5 个尚未实现；首页等 5 个建议接口另列，不算已有契约。运行见 [Backend README](../../backend/README.md)，工具见 [Agent README](../../agent/README.md)，当前验证见 [D07 记录](../../openspec/changes/d07-create-agent/verification.md)。官方摄像头 SDK 仍由 Harry Xu 独立验证。

Android 不直接访问数据库；视觉算法可用批处理与导入客户端，不要求单独部署 HTTP 服务。Agent 的 AI 历史通过 Backend 的 `AIHistory` 事务服务保存；独立 `agent/` 包使用 `create_agent` 与五个只读工具，经固定 Query 的 Gateway 调用 Backend，不直连数据库。内部联动见 [实现说明](../modules/BACKEND_AGENT.md)；图中的表关联表示数据归属，不表示调用方直连表。

[模块映射](api-module-map.json) 记录分工和阶段，字段与样例继续以 OpenAPI 为准。修改后运行 `python3 scripts/build-api-canvas.py` 和 `python3 scripts/sync-workbench-api.py` 更新离线页面；两者都支持 `--check`，不启动或调用真实服务。

全部 API 使用 `/api/v1`，当前用户为 `GET /api/v1/user`，Agent 接口为 `/api/v1/agent/...`。`v1` 是路由主版本，`0.3-mvp-draft` 是本次草案/样例版本；统一公开地址为 `https://api.example.invalid`，Android 只配置一个 base URL；模块在同一 FastAPI 应用组合，Agent 路由需显式启用；占位地址不是部署地址。配置见 [私有环境模板](../../backend/.env.example)，外部模型缺配置时返回 503，不以模拟模型回退。

**接口草案仍待确认；已有本地运行实现，没有据此声明生产部署。** 按 2026-10-12 最小版本收敛，并按 2026-10-13 用户要求加入事件关联文本向量、AI聊天/分析持久化及二维码包裹入出库，依据仍为[技术方案 r51](https://q0ef12ao819.feishu.cn/wiki/RFLWwHFplicxpIk10bbcTBr6nqr) 与 [PRD r230](https://q0ef12ao819.feishu.cn/wiki/QHVvwxixBiJspmkoBD1cuU6InQc)，主端按已确认的 Android。

`users` 只保存用户资料、角色和状态；认证主体由服务端映射，不保存摄像头 SDK 凭据。摄像头/ROI 放版本配置文件；视频偏移为定位基准，只有验证过的起始时间才支持 UTC 查询。事实发布后不覆盖，管理员显式保存；`event_embeddings` 绑定事件及可选关键帧，Agent 只读语义检索后回看证据。`conversations/messages/runs` 保存本人会话、问题、已完成答复和运行状态；会话不固定录像，元数据和用户问题仅 Bearer + active owner。每次分析仍使用单视频 Query，计划/运行及助手答复按本次录像的当前授权与引用归属校验；历史消息先确定页，再逐助手消息经 run 验权，任一无权则整页 403，无正文或部分结果，不静默过滤；会话列表不因录像撤权消失。Agent 保存自己的 AI 历史，监控事实工具仍只读。已选 PostgreSQL + pgvector；SQL 时间为 TIMESTAMPTZ、JSON 为 JSONB、向量为 `embedding vector`，API 同名时间保持 UTC 字符串、向量保持数组。后端在授权范围和同模型空间内精确检索，Agent 不直连数据库；查询编码器采用服务端 OpenAI 兼容配置，真实模型/维度与外部调用仍待验证，首版没有 ANN 索引。电商订单关联、跨镜、原生 SDK 检测事件、离散帧集和复核分组后置。

P1 包裹的五个接口仅在当前草案／API 页面中，尚未实现：`POST /parcels`、`GET /parcels?parcel_no=...`、`GET /parcels/{parcel_id}`、`GET/POST /parcels/{parcel_id}/records`。`parcels` 保存 3 列，`parcel_records` 保存 7 列；单号可由后端生成，用 TEXT 保留前导零，二维码不另建表，重复扫码不写流水。状态按 `sequence_no` 派生为 `pending / in_stock / outbound`；入库必须有效 `zone_id`，出库保留最近入库货架但当前位置为空。两项写入要求管理员显式确认与 `parcels:write`，操作人 / 时间 / 序号由后端设置；Agent 只调用三个 GET。完整的幂等、状态冲突、分页与模拟样例见 [API 说明](API_DESIGN.md#二维码包裹与入出库) 和 OpenAPI。

[0.2 完整设计](archive/0.2.md) 与 [0.1 demo-bundle](examples/demo-bundle.json) 保留为历史资料，不能作为当前导入请求。表名使用复数 `snake_case`；`evidence` 是不可数名词，保持原名。示例通过不代表真实闭环验收或 D01 已冻结。
