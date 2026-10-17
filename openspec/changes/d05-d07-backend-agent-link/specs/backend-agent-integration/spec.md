## ADDED Requirements

### Requirement: 同应用显式组合与受限依赖

联动实现 SHALL 在 Backend 应用工厂显式注入 Agent router 和受限 Python 服务；Backend 只添加一次 Agent 前缀，内部调用不新增公共 API。认证与事实查询来自 Backend，Agent 不取得数据库连接或通用 SQL／写工具。

#### Scenario: 挂载 Agent 路由
- **WHEN** 应用组合真实 Agent router
- **THEN** SHALL 使用现有 `/api/v1/agent/...` 路径并复用权限检查；仅健康探测或演示 router 测试不能作为联动验收

### Requirement: 最终计划与动态权限作为执行依据

运行 SHALL 从本人用户消息派生会话，以最终确认计划重新校验单视频查询与当前授权；创建后全部工具固定在保存的运行范围。draft plan 不持久化，不能当作初始范围记录。

#### Scenario: 模型工具试图扩大查询范围
- **WHEN** 工具参数选择另一录像、扩大窗口或越过本次事件归属
- **THEN** SHALL 拒绝该调用，不信任模型自报身份、权限或 SQL，提交答复前再次校验当前资源授权

### Requirement: 运行持久化与异步幂等调度

联动实现 SHALL 使用 App 提交的稳定 run_id，规范化校验后持久化 running，再快速返回首次 202 并由受控任务执行；同内容重放 200，不同内容 409，不重复启动任务。

#### Scenario: App 因超时重试创建运行
- **WHEN** 相同本人请求再次提交相同 run_id
- **THEN** SHALL 验证当前权限后读取原运行；调度失败必须结束为 failed，不留下没有执行任务的 running

### Requirement: AI 历史通过 Backend 事务保存

Agent SHALL 使用 Backend 的 AI 历史服务保存本人会话、用户消息与运行，不直接改表。completed 和唯一完整助手答复必须在同一事务提交；终态与原引用不得被晚到回调覆盖。

#### Scenario: 停止与完成同时到达
- **WHEN** 两者竞争同一 running 运行
- **THEN** SHALL 只有一个事务进入终态；停止获胜后不能插入完成消息或追加引用，完成获胜后停止返回当前终态

### Requirement: 事实工具只读与引用有效

Agent 事实工具 SHALL 仅调用现有事件、关键帧与语义检索；自己的 AI 历史写入不赋予事件导入、向量写入、人工核查或包裹流水权限。模型空间由 Backend 确定。

#### Scenario: 结果引用失效或越界的证据
- **WHEN** 完成答复包含越界引用、不可读取帧或工具失败
- **THEN** SHALL 拒绝非法引用，缺图仅表达候选或未知，不用模拟成功、语义分数或占位图冒充事实

### Requirement: 首版单执行实例明确恢复失败

首版运行器 SHALL 按单执行实例管理取消与重启恢复，不自动续跑；扩展为多实例前须另行设计执行归属或租约。

#### Scenario: 服务重启有旧运行
- **WHEN** 唯一执行实例启动发现遗留 running
- **THEN** SHALL 将其标为 failed／service_restarted 并保留历史；用户重试使用新 run_id，不能在多实例启动时扫描并误终止其他活跃执行
