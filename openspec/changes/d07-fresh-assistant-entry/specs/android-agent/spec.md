## ADDED Requirements

### Requirement: Fresh conversation on explicit assistant entry

Android SHALL 仅在用户明确点击首页、事件页或我的页的 AI 追溯 Tab 时请求默认新对话。当前没有未完成真实运行时，客户端 MUST 清除本地当前会话指针、消息、计划、运行、证据、相关分页状态与未发送草稿，并保留后端配置及已选录像、区域。初始化、入口重置与后续读取 SHALL 按明确顺序执行，不能由并行恢复重新覆盖新对话。

#### Scenario: Enter from a known terminal conversation
- **WHEN** 用户在首页、事件页或我的页点击 AI 追溯 Tab，原会话没有未完成真实运行，或运行已知为 completed、stopped 或 failed
- **THEN** Android 显示欢迎空态和空输入草稿，不自动显示原消息、计划、运行、证据或旧消息分页入口，且保留配置与录像、区域选择

#### Scenario: Explicit entry before local initialization finishes
- **WHEN** 用户点击 AI 追溯 Tab 时，持久快照仍在读取
- **THEN** Android 在完成初始化后处理本次新入口，再决定读取新页面所需数据；旧会话恢复不得覆盖已接受的新入口

#### Scenario: Local reset cannot be persisted
- **WHEN** 明确新入口所需的本地重置保存失败
- **THEN** Android 显示可诊断错误，不以旧会话指针发送被标成新对话的问题

### Requirement: Preserve durable unfinished execution at fresh entry

Android SHALL 在未完成真实运行或启动请求结果不明时保留原会话、稳定 run_id 与确认载荷，并恢复既有运行，作为默认新对话规则的安全例外。客户端 MUST 不因明确新入口另分配运行 ID、静默重跑或丢弃显式停止依据；既有真实来源、授权、取消和恢复规则继续适用。

#### Scenario: Running real analysis exists
- **WHEN** 用户明确点击 AI 追溯 Tab，而当前真实分析尚未进入已知终态
- **THEN** Android 保留并恢复该运行的稳定 ID 和原确认载荷，继续提供实际状态与显式停止能力

#### Scenario: Start response is ambiguous
- **WHEN** 启动请求的响应丢失但本地已保存稳定 run_id，用户随后明确进入 AI 追溯
- **THEN** Android 按原 ID 恢复，后续重试继续遵循既有幂等规则，不因新入口分配另一运行 ID

#### Scenario: Recovery cannot establish terminal status
- **WHEN** 保留运行的回读失败，客户端不能确定其已完成、停止或失败
- **THEN** Android 显示实际请求错误并保留恢复依据，不把请求失败解释为不存在运行或无条件清空会话

#### Scenario: Authorization is revoked during recovery
- **WHEN** 恢复请求返回当前账号无权访问会话或运行
- **THEN** Android 清除页面旧事实、停止自动请求并显示授权错误，不以持久答案绕过当前授权

### Requirement: Retain conversation during ordinary return

Android SHALL 将证据详情返回、前后台切换、SDK 会话重建与服务设置入口视为继续当前会话，MUST 不将这些激活、Compose 重组或页面恢复误认为用户明确点击 AI 追溯 Tab。

#### Scenario: Return from related event evidence
- **WHEN** 用户从当前 AI 对话打开相关事件或证据详情后返回
- **THEN** Android 继续同一会话及其运行／历史状态，不清除对话或未发送草稿

#### Scenario: Resume app or rebuild SDK session
- **WHEN** AI 页从后台回前台，或独立摄像头 SDK 会话重建后恢复 AI 路由
- **THEN** Android 沿用当前会话并重新按当前后端授权恢复所需数据，不触发新入口重置

#### Scenario: Open backend settings
- **WHEN** 用户从设置或审核记录的配置入口打开 AI 服务设置
- **THEN** Android 打开设置并保留当前会话，不因此请求新对话

### Requirement: Keep server history behind explicit selection

Android SHALL 保留服务端会话历史，并通过既有历史入口分页读取和手动打开。客户端 MUST 不因显示欢迎空态创建服务端空会话或删除旧会话；新问题仍按既有稳定 ID 持久化和创建顺序提交。行为使用 [M06 模块](../../../../../docs/modules/README.md#m06)、[Agent 合同](../../../../../docs/api/AGENT_API.md)、[OpenAPI](../../../../../docs/api/openapi.json)及 [SQL](../../../../../docs/database/schema.sql) 的现有约定，不新增字段；列表与消息形状参考 [会话列表样例](../../../../../docs/api/examples/v0.3/conversation-list-response.json)和 [消息样例](../../../../../docs/api/examples/v0.3/messages-response.json)。

#### Scenario: Welcome page without a submitted question
- **WHEN** 明确新入口显示空白对话，但用户尚未提交问题
- **THEN** Android 不 POST 新会话、不删除服务端旧会话，也不自动读取旧会话消息作为当前对话

#### Scenario: Select an old conversation explicitly
- **WHEN** 用户打开历史并明确选择有权访问的旧会话，且没有未完成真实运行阻止切换
- **THEN** Android 通过既有接口读取该会话消息及分页状态，并在随后普通返回时继续该会话

#### Scenario: No conversation history
- **WHEN** 当前授权历史列表为空
- **THEN** Android 显示暂无会话记录，继续允许按既有录像与区域要求发起新问题，不生成占位会话或模拟答复
