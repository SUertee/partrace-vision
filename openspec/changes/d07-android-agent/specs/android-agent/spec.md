## ADDED Requirements

### Requirement: Authorized recording plan confirmation
Android 助手 SHALL 选择已授权的已发布录像及其配置区域，在该录像窗口提交问题，并在启动前展示计划供用户修改和显式确认。接口沿用 [Agent 合同](../../../../../docs/api/AGENT_API.md)。

#### Scenario: Plan ready
- **WHEN** 后端返回所选录像范围内的有效计划
- **THEN** Android 展示范围、可编辑的检索问题与步骤，仅在确认后执行

#### Scenario: No published recording
- **WHEN** 已授权录像列表为空
- **THEN** Android 展示空态，不生成虚构事件或结果

### Requirement: Durable run control and history
Android SHALL 在创建请求前持久保存稳定 ID，恢复最后运行，提供显式停止，并按服务端游标分页回读本人历史。

#### Scenario: Ambiguous start response
- **WHEN** 启动请求的响应丢失
- **THEN** 重试使用原运行 ID 及不变的确认计划

#### Scenario: Stop races completion
- **WHEN** 停止请求返回已完成运行
- **THEN** Android 展示服务端返回的完成状态，不自行宣称停止

### Requirement: Current authorized evidence
Android SHALL 渲染受支持的结果卡片，按当前后端授权读取引用的元数据和媒体；SDK 登录变化不应将独立的 AI 页面重置为首页。

#### Scenario: Evidence removed
- **WHEN** 历史引用现在返回媒体缺失
- **THEN** Android 保留引用 ID 并显示缺失，不使用替代图片

#### Scenario: SDK session changes
- **WHEN** 摄像头 SDK 会话失效并重建 SDK 导航
- **THEN** Android 保留当前 AI 路由，后端数据仍以独立 Bearer 权限校验

### Requirement: Real recording sources in production chat
Android SHALL 只允许已授权、已发布且来源为 sdk 或 prerecorded 的录像发起新分析，MUST 不将 mock 录像自动选中或呈现在来源选择器中。当前持久执行指针若明确属于 mock SHALL 退出该本地对话，不删除服务端历史；与该选择无关的真实执行指针 MUST 保持稳定。

#### Scenario: Only mock recordings available
- **WHEN** 已授权已发布列表仅包含 mock 录像
- **THEN** Android 显示暂无可分析的真实录像，清除旧模拟选择及其本地恢复状态，不 POST 计划或运行

#### Scenario: Legacy mock history
- **WHEN** 用户查看旧模拟分析或其运行恢复返回不存在
- **THEN** Android 可只读查看历史与显式停止既有运行，MUST 不通过确认、重试或 404 恢复新建模拟计划／运行；历史 running 不阻止新建真实会话

#### Scenario: Real run with stale mock selection
- **WHEN** 旧选中录像为 mock 但持久 pending query 指向另一真实录像
- **THEN** Android 保留真实执行的稳定 ID 与原始确认载荷，不用新的选中录像改绑执行范围
