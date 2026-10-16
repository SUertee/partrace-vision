## Purpose

本能力为 API、Agent 与 Vision 定义可关联且不泄露业务内容的诊断行为，使运维人员能区分请求失败、异步分析失败、视频任务完成与图库发布异常，并通过有界日志、低基数指标和明确的告警缺数规则定位问题。依据为[本次提案](../../proposal.md)、[Backend 与 Agent 联动](../../../../../docs/modules/BACKEND_AGENT.md)、[Backend 与 Vision 联动](../../../../../docs/modules/BACKEND_VISION.md)及[既有 API 契约](../../../../../docs/api/openapi.json)。这些要求是待实施设计，不证明 CloudWatch 或其他 AWS 服务已经部署或验收。

## ADDED Requirements

### Requirement: Correlated diagnostic identifiers

系统 MUST 为 HTTP 请求生成服务端诊断标识，并沿用既有响应头及错误字段；异步 Agent 运行和视频任务的诊断事件 MUST 可由 run_id、job_id 及 attempt 关联其发起请求与后续阶段。异步终态错误中返回的 request_id MUST 能定位对应诊断事件；关联不得要求增加公开业务字段，也不得将客户端提供的标识作为可信服务端标识。

#### Scenario: HTTP failure can be located

- **WHEN** 请求因校验、鉴权或服务异常返回失败
- **THEN** 响应中的服务端 request_id 能定位受控错误事件，客户端伪造的关联标识不会替换该标识

#### Scenario: Asynchronous failure remains traceable

- **WHEN** HTTP 已返回后，Agent 运行失败或视频任务在后续 attempt 失败
- **THEN** 运维人员能使用 run_id 或 job_id 与 attempt 定位各阶段，并使用 Agent 终态错误的 request_id 定位对应失败事件

### Requirement: Allowlisted diagnostic content

日志 MUST 使用结构化允许字段，仅记录 UTC 时间、级别、服务、环境、版本、受控事件名、诊断标识、任务标识、阶段、结果、受控错误码、耗时、计数及非秘密的供应方请求标识。日志 MUST 使用路由模板，不记录原始 URL、查询串或请求体；MUST NOT 记录凭据、租约、DSN、签名 URL、提示词、用户问题、工具结果、事件描述、媒体、向量、人物档案、原始异常文本或本地私有路径。

#### Scenario: Sensitive input never reaches logs

- **WHEN** 请求、模型错误或工具结果包含诱饵密钥、签名 URL、个人信息或本地私有路径
- **THEN** 应用日志及采集后的日志均不含这些原文，排障仍可使用受控错误码与诊断标识

#### Scenario: Access logging uses a route template

- **WHEN** 对某个具体视频任务发起媒体读取或完成请求
- **THEN** 访问事件记录带参数占位符的路由模板，不记录含实际任务标识的 URL、查询串或鉴权头

### Requirement: Bounded execution stage events

系统 SHALL 为实际发生的模型、白名单 skill 与工具调用记录受控名称、开始或结束、耗时、结果及调用计数，并为视频任务区分下载、推理、后端完成、图库推广与恢复阶段。事件 MUST 有单条大小及每次执行数量上限；正常容量内失败、停止和图库待发布事件 MUST 不被采样丢弃，极端采集故障的缺口按有界故障规则披露。后端已完成但图库推广失败 MUST 明示待发布，不得记录为图库成功或推断模型已看图。

#### Scenario: Backend completion does not imply gallery publication

- **WHEN** 视频任务已获后端 completed 确认，但图库推广失败
- **THEN** 诊断事件分别显示后端完成和图库待发布，并保留 job_id 与 attempt；受控模型、skill 和工具阶段事件不包含调用参数或结果正文

### Requirement: Low cardinality metrics and truthful usage

指标 MUST 仅使用受控的环境、服务、路由模板、状态类别、provider、模型别名、操作、worker 角色、阶段和错误码等低基数维度；MUST NOT 以请求、run、job、用户、镜头、worker 实例或原始模型 ARN 作为维度。token 用量 MUST 来自供应方实际返回；缺失时标记 unknown，不得记为零、估算成实际用量或将运维指标充当精确账单。

#### Scenario: Unreported usage remains unknown

- **WHEN** 多个不同 run 或 job 使用同一模型别名，且某次供应方响应未提供 token 用量
- **THEN** 指标维度集合不随这些标识增加，该次用量标记 unknown，已有真实用量与未知次数分别可见

### Requirement: Committed and replay safe outcome metrics

运行或任务终态指标 MUST 仅在数据库确认状态转换提交后计入；提交结果不确定时不得先宣称成功。相同 run、job 或完成请求的幂等重放 MUST NOT 再次计入同一业务完成；失败落库仍待重试时 SHALL 通过待终结指标体现。数据库 SHALL 继续作为业务终态与审计真源；采集重试可能重复的运维事件不得被描述为精确业务计数。

#### Scenario: Uncertain commit is not a reported success

- **WHEN** 完成事务提交期间连接中断且结果尚未确认
- **THEN** 不新增已完成指标，诊断显示结果待确认，后续依据数据库终态核对

#### Scenario: Idempotent replay does not count twice

- **WHEN** 相同内容的完成请求在已提交后重放，或失败终结因数据库短暂中断而重试
- **THEN** 同一业务终态只计入一次；待终结状态持续到数据库确认，并在确认后消除

### Requirement: Non blocking collection failure

日志或指标采集故障 MUST NOT 改变业务响应、授权结果、数据库终态或任务重试语义，也不得无限阻塞业务请求。采集链路 SHALL 使用有界缓冲和本地轮转；达到上限时按明确策略丢弃可丢弃诊断事件并记录缺失计数或恢复摘要，不得无限增长内存、磁盘或自动续跑分析。恢复后的可观测性记录 MUST 明示采集缺口，不补造未采集内容。

#### Scenario: Collector outage preserves business behavior

- **WHEN** CloudWatch 不可达且缓冲达到配置上限
- **THEN** 业务按既有成功、失败或取消语义继续，缓冲及轮转不超限；采集恢复后能识别缺口和丢弃情况，不出现虚构阶段记录

### Requirement: Explicit alarm absence behavior

告警 SHALL 覆盖数据库或执行器不可用、失败终结积压、队列超龄、图库待发布、模型错误或限流、资源耗尽及采集心跳缺失。每条告警 MUST 声明阈值、持续窗口、最小样本量、缺数据行为及通知目标参数；采集心跳持续缺失 SHALL 视为异常，低流量错误率缺数据 SHALL NOT 自动视为业务故障。告警不得代替数据库终态，通知目标未配置时不得声称通知已送达。

#### Scenario: Missing collection heartbeat raises an operational alarm

- **WHEN** 采集心跳在配置的持续窗口内缺失
- **THEN** 对应告警进入异常状态，并按已配置通知目标处理；不修改任何业务运行或视频任务状态

#### Scenario: Idle traffic does not create an error rate incident

- **WHEN** 错误率窗口没有请求或未达到最小样本量
- **THEN** 缺数据不自动触发业务错误率告警，告警配置中的缺数及样本规则可查验

### Requirement: Reviewable observability configuration

部署配置 SHALL 显式声明日志组、区域、保留期、单条大小、缓冲及轮转上限、采样、指标维度集合、告警参数与运行版本标识，并保留供回滚使用的上一版配置及版本对应关系。日志保留期到期后 SHALL 按配置清理；历史日志版本标识不得因新版本上线被重写。启用与验证记录 MUST 区分配置检查、模拟采集和真实云端验收，不得据规格或本地测试宣称已部署。

#### Scenario: Version rollback preserves diagnostic provenance

- **WHEN** 运维人员评审或回滚可观测性配置
- **THEN** 能核对当前和上一版的保留、容量、采样及告警参数，已有日志保留原运行版本，验收记录明确哪些项目尚未真实云端验证
