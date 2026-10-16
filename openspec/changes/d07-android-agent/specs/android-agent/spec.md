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
