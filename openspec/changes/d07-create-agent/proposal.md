# D07 · create_agent 与只读工具

用户授权在已有最小后端上实现 Agent，复用既有八个 `/api/v1/agent` 操作、会话／消息／runs 表以及事件和文本向量契约。关联 D07／Issue #7，协作 D05／Issue #5 与 D08／Issue #8；模块 M04、M05、M06。上游约定见 [联动设计](../../../docs/modules/BACKEND_AGENT.md)、[OpenAPI](../../../docs/api/openapi.json) 与 [SQL](../../../docs/database/schema.sql)。

新增独立可安装 Python Agent 包，用 LangChain `create_agent`（LangGraph runtime）生成计划、调用受限工具并输出经校验的答复。Backend 提供动态鉴权、AI历史事务及只读事实／pgvector服务。应用显式启用后统一挂载，不启动自调用HTTP服务。

不新增业务表、公开路由或状态。补齐既有两项向量操作，为语义工具提供真实存储和可配置编码器；模型未配置／故障明确失败，不用mock补齐真实请求。首版不实现包裹写入、SQL／shell工具、多实例续跑、实时流、3D或跨镜关联。
