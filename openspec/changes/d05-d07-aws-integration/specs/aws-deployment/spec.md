# Spec Delta

## Purpose

规定驿站追迹在 AWS 上使用显式环境配置、工作负载身份、受控依赖和单执行器进行部署与恢复的行为。分阶段上线必须提供实际运行证据，并保留现有 Android／Vision 接口、数据持久化与人工核查边界，不能将规划或离线替身结果等同于真实云端业务验收。

依据：[提案](../../proposal.md)、[部署说明](../../../../../backend/README.md)、[模块联动](../../../../../docs/modules/BACKEND_AGENT.md)、[SQL](../../../../../docs/database/schema.sql)、[OpenAPI](../../../../../docs/api/openapi.json)、[当前样例](../../../../../docs/api/examples/v0.3/)。

## ADDED Requirements

### Requirement: Explicit environment and cloud identity

部署 MUST 显式声明账户、环境、各服务区域、模型目标及媒体存储配置。AWS 请求 SHALL 使用部署工作负载的临时身份；客户端与模型不能覆盖云端目标或取得凭据。缺失或冲突配置必须明确拒绝启用对应功能，不能自动创建资源或切换其他 provider。正式业务认证未就绪的环境 MUST 保持既有拒绝，不得通过改环境名称绕过或宣称生产就绪。

#### Scenario: Bedrock selected without an API key
- **WHEN** 已选择 Bedrock，目标和区域有效，工作负载有受权临时身份，但未配置 OpenAI API key
- **THEN** 允许构造该 provider，首次真实请求仍按当前 IAM 与模型可用性验证

#### Scenario: Unauthorized cloud target
- **WHEN** 用户输入、工具参数或部署配置要求未允许的区域、模型或 bucket
- **THEN** 拒绝相应访问，不把请求重定向到其他云目标或放宽资源授权

#### Scenario: Formal authentication is not configured
- **WHEN** 目标是非 development 环境，但仍只有当前仅适用于 development 的业务凭据
- **THEN** 业务鉴权保持 auth_unavailable，AWS 服务身份不能代替用户登录；受控演示验证不被记为正式上线

### Requirement: Verified database transport and access

AWS 数据库连接 MUST 验证服务端主机名和受信任证书，并使用限定应用数据库用户的权限。数据库签名凭据只能用于数据库连接，不得替代业务用户的录像授权；身份、TLS 或数据库不可用必须明确失败。

#### Scenario: Incorrect database certificate
- **WHEN** 数据库主机名不匹配证书、CA 不受信任或 IAM 数据库授权无效
- **THEN** 数据库连接与相应就绪检查失败，不退回不验证身份的 TLS 或静态密码

#### Scenario: Cloud role can read data but business user cannot
- **WHEN** 实例角色有底层存储／数据库访问权，但当前用户的录像授权已撤销
- **THEN** 业务接口、工具与最终答复提交仍拒绝该资源访问

### Requirement: Single execution ownership during rollout

一期部署 MUST 保持单 Agent 执行器和既有运行终态。升级必须停止新分析并排空或明确停止旧运行，释放旧执行归属后才能启动新执行器；重启不得自动重做模型调用或覆盖已停止／完成的结果。

#### Scenario: Second executor starts during deployment
- **WHEN** 同一数据库范围的旧执行器仍持有执行归属
- **THEN** 新执行器拒绝接管，不将旧实例运行中的任务标为中断或重复执行

#### Scenario: Service restarts with running work
- **WHEN** 新执行器取得归属并发现前次遗留的 running 记录
- **THEN** 将其明确标为重启中断失败，保留历史且不续跑，原有历史分页与终态读取仍可用

### Requirement: Reversible staged activation

Chat、Embedding 与媒体存储 SHALL 分别显式切换并保留已验证的回滚路径。回滚不得丢失新产生的媒体引用、混用新旧向量空间或改变既有事实与人工核查；未满足数据可读性条件时必须阻止回滚切换。

#### Scenario: Roll back after new S3 uploads
- **WHEN** S3 启用后已创建新录像或证据，本地恢复副本尚未同步
- **THEN** 拒绝直接切回旧本地存储；先恢复所有当前引用的可读性并验证，再恢复写入

#### Scenario: Roll back an embedding provider
- **WHEN** 管理员将语义服务切回旧 provider
- **THEN** 查询同时回到对应旧模型空间，新空间保留且不能参与旧空间的相似度计算

### Requirement: Honest live acceptance evidence

每个启用阶段 MUST 分别记录离线替身、真实云依赖及真实业务闭环的验证范围。完整一期验收须包含受权原片／关键帧、真实 Bedrock 编码与 Agent 有效引用、持久化回读和人工核查；失败、缺索引或未知采集覆盖不能被记为成功闭环。

#### Scenario: Only offline SDK doubles are available
- **WHEN** 测试验证了云端适配行为但没有实际 AWS 权限或真实录像事件
- **THEN** 只记录离线验证通过，真实服务与 D10 验收保持未完成，不归档整个实施变更

#### Scenario: True end to end acceptance
- **WHEN** 同一真实已发布事件完成 S3 媒体读取、描述索引、受权 Agent 引用、人工保存及服务重启回读
- **THEN** 留存关联 ID、配置版本和脱敏结果，分别报告模型、媒体、数据库、监控与业务验收结果

### Requirement: Deferred platform expansion

一期 MUST 保持既有数据库任务状态与单执行器语义，不默认启用 SQS、AgentCore 或多机图库发布。二期启用前须以独立变更验证消息可靠性、执行归属、跨进程取消与持久状态，不得仅切换托管平台就宣布具备这些能力。

#### Scenario: Optional queue message is lost
- **WHEN** 二期采用消息唤醒但通知丢失或重复
- **THEN** 业务状态仍来自数据库并可由保留的轮询恢复，不以消息删除或死信状态代替任务终态

#### Scenario: Runtime platform is considered for migration
- **WHEN** 计划将 Agent 或视觉 worker 迁至弹性多实例平台
- **THEN** 在执行归属、身份、临时磁盘和图库版本协调验收之前，保持现有部署且不启用多实例执行
