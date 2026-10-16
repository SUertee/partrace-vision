## Purpose

本待评审规格定义驿站追迹拟增加的 AWS 模型接入行为，使对话、查询编码与事件描述索引能够使用显式配置的 Amazon Bedrock，同时保留已确认计划、只读工具、权限、证据引用和模型空间约束。本规格不表示云端服务已接通或真实业务已验收。范围见[提案](../../proposal.md)；业务边界见[模块设计](../../../../../docs/modules/README.md)与[Agent 联动](../../../../../docs/modules/BACKEND_AGENT.md)，数据及返回契约沿用 [SQL](../../../../../docs/database/schema.sql)、[OpenAPI](../../../../../docs/api/openapi.json)和[现有样例](../../../../../docs/api/examples/v0.3/)。

## ADDED Requirements

### Requirement: Explicit model provider selection

系统 MUST 由服务端分别选择对话与文本编码提供方；未设置新增选择项时保持现有 OpenAI 兼容配置。选择 Bedrock 时 MUST 显式指定区域与获准的调用目标，使用 AWS 临时身份且不要求 OpenAI API key。配置缺失、身份不可用或目标不受支持时 MUST 明确失败，不得静默切换提供方、区域或模型；业务请求与模型工具不得改变这些选择。

#### Scenario: Bedrock identity without an OpenAI key
- **WHEN** 服务端选择 Bedrock，配置已验证的区域和调用目标，并提供具备调用权限的 AWS 临时身份但没有 OpenAI API key
- **THEN** 系统 SHALL 使用所配置的 Bedrock 目标完成模型请求，不因缺少 OpenAI API key 拒绝调用，也不在业务响应中暴露身份凭据

#### Scenario: Existing configuration and invalid selection
- **WHEN** 服务端保留原有配置而未设置新增选择项，或显式选择的提供方缺少必需配置
- **THEN** 前一种情况 SHALL 保持原有提供方行为，后一种情况 SHALL 返回可诊断的配置或服务错误；两种情况均不得自动选择其他模型

### Requirement: Confirmed plans and evidence remain binding

Bedrock 对话能力 MUST 输出符合现有契约的计划及最终答复，并支持同一轮中的内置 skill 加载、只读业务工具与结构化答复。系统 MUST 保持用户确认的单录像、区域、时间及语义文本，验证实际回读的有效 Event/Evidence 引用；不得因更换提供方授予模型写入能力，或把候选描述、媒体可用状态升级为视觉事实。

#### Scenario: Structured plan preserves the requested scope
- **WHEN** 用户请求生成计划，Bedrock 返回结构化计划或试图改变固定查询范围
- **THEN** 系统 SHALL 仅接受符合现有计划契约且保留固定范围的结果；不符合要求的结果 SHALL 明确失败，不开始检索

#### Scenario: Skill execution produces verified references
- **WHEN** Bedrock 加载内置 skill，在确认范围内检索并回读事件与关键帧后提交结构化答复
- **THEN** 系统 SHALL 按现有契约返回答复及卡片，并拒绝未回读、失效或不属于确认范围的引用；模型不得执行向量写入或人工核查保存

### Requirement: Bounded requests and cancellation

模型接入 MUST 遵守现有模型调用、工具调用、并发与总时限配置，并约束底层请求超时及重试，避免无界外部调用。用户停止、运行超时或预算耗尽后 MUST 停止安排后续调用，并保持对应终态；已发出请求的迟到返回不得发布完成答复、继续调用工具或改变已提交终态。

#### Scenario: An external response arrives after termination
- **WHEN** 运行因停止、超时或调用预算耗尽而终结，随后 Bedrock 请求才返回
- **THEN** 系统 SHALL 丢弃迟到结果，保持已提交的停止或失败状态，不追加完成答复或触发后续外部调用

### Requirement: Traceable query and document encoding

系统 MUST 使用所选模型规定的查询编码与文档编码方式，并使两端属于同一明确的模型空间。事件描述 MUST 来自可追溯的已发布事件或同事件证据，保留候选、来源与未知信息；不得由本能力凭空补充身份、物品或动作事实。模型修订、编码预处理、输入用途及维度配置 MUST 可追溯，变更编码语义时 MUST 使用新空间版本；不合规向量或超出既定输入策略的文本 MUST 明确失败。

#### Scenario: A description and its query use the matching recipe
- **WHEN** 系统索引已发布事件的来源描述，并随后编码用户的查询文本
- **THEN** 两端 SHALL 分别使用已声明的文档及查询编码方式，并记录一致的模型空间；来源中的未知信息 SHALL 保持未知，错误维度、非有限或全零向量 SHALL 被拒绝

### Requirement: Authorized and resumable description indexing

事件描述编码与入库 MUST 由具备现有导入权限的受限作业发起，仅处理授权范围内的已发布事实；Agent 仍只读。作业 MUST 支持范围限制、编码前预览及逐项结果统计，相同来源与模型版本重跑或并发时复用已写入向量。失败后 MUST 保留真实完成的子集并允许恢复，不覆盖或删除历史向量，不将未完成批次标记为成功。

#### Scenario: Repeated indexing preserves stable results
- **WHEN** 授权作业针对同一事件描述和模型空间重跑，或多个作业并发处理同一来源
- **THEN** 系统 SHALL 复用已存在的向量及其 ID，避免重复发布，不改变原事件、证据或已存向量，并准确统计已完成与跳过项目

#### Scenario: A partially failed indexing job resumes
- **WHEN** 作业部分来源已成功入库，随后因模型或存储错误中断并再次运行
- **THEN** 系统 SHALL 报告实际成功、失败与未完成范围，恢复时跳过已完成项；失败不得伪装成完整索引覆盖

### Requirement: Isolated model spaces and reversible activation

语义检索 MUST 仅在模型名称、编码版本与维度均匹配的空间内比较向量，并在外部编码后重新确认空间未变化。新空间 MUST 先独立构建、核对覆盖及质量后显式启用；切换和回滚 MUST 成套使用对应提供方、编码配置与空间，保留旧向量及稳定的业务 ID，不得将旧空间向量与新查询向量混用。

#### Scenario: Another space cannot satisfy a new query
- **WHEN** 授权范围仅有旧模型或不同维度的向量，或查询编码期间当前空间发生变化
- **THEN** 前一种情况 SHALL 按当前空间报告未索引而不混用旧向量，后一种情况 SHALL 明确拒绝该查询结果；不同空间不得参与同一次相似度比较

#### Scenario: A verified space is activated and rolled back
- **WHEN** 管理员完成新空间覆盖及质量核对后显式切换，并随后恢复旧配置
- **THEN** 系统 SHALL 分别使用对应空间完成检索，保留两套历史向量以及原 Event/Evidence ID，不要求覆盖或删除既有业务数据

### Requirement: Unavailability differs from empty retrieval

系统 MUST 区分模型未配置或故障、向量存储故障、有事件但当前空间未索引、有效检索无匹配及范围外。模型故障 MUST 保持 HTTP 503 `model_unavailable`，不得伪造向量、空成功或覆盖计数；存储错误 MUST 保持既有独立错误。Agent 仅可按既有约定在语义模型不可用后继续普通查询，并明确披露限制；模型本身不可用仍失败。

#### Scenario: Missing indexes and valid empty results
- **WHEN** 授权范围存在事件但当前空间没有索引，或有效范围内确无事件、阈值下无匹配、查询在录像范围外
- **THEN** 前一种情况 SHALL 返回 `unindexed` 且不发起查询编码，其余情况 SHALL 按现有契约返回 `no_matches`，保留真实覆盖计数与范围关系；部分索引 SHALL 明示缺口

#### Scenario: Bedrock failure is disclosed without fabricated retrieval
- **WHEN** Bedrock 编码因权限、限流、超时或无效响应不可用，或向量存储发生故障
- **THEN** 语义接口 SHALL 分别返回 `503/model_unavailable` 或既有存储错误；仅前者可使 Agent 在重新验权后继续普通查询并披露语义不可用，均不得暴露凭据或伪造匹配

### Requirement: Authorization and source isolation across external calls

检索与索引 MUST 在发起外部编码前验证身份、授权及事实范围，并在结果使用或入库前重新验证；外部调用期间不得因占用数据库事务阻止权限变更生效。真实 Bedrock 编码与显式模拟来源 MUST 保持隔离，模拟编码不得用于真实录像，真实编码不得冒充模拟测试结果。权限或来源不符 MUST 明确拒绝，不得降级成空结果或继续发布。

#### Scenario: Access is revoked during encoding
- **WHEN** 身份在编码期间被禁用、授权被撤销或来源不再满足允许范围
- **THEN** 系统 SHALL 在读取查询结果或写入向量前拒绝操作，不发布该结果，也不让 Agent 通过普通查询回退绕过权限

#### Scenario: A real and a simulated source are mixed
- **WHEN** 显式模拟编码器被用于真实录像，或真实 Bedrock 编码被用于要求模拟编码的录像
- **THEN** 系统 SHALL 拒绝编码或结果使用，保留来源标记，不将模拟凭据计入真实模型接入及业务验收
