## ADDED Requirements

### Requirement: 业务操作使用真实 PostgreSQL

Backend SHALL 按[当前 OpenAPI](../../../../../docs/api/openapi.json)实现本轮十二个数据操作，使用[既有 SQL](../../../../../docs/database/schema.sql)持久化，保持 API／表数量及字段契约。业务数据库失败时不得回退为内存或模拟结果。

#### Scenario: 服务重启后回读
- **WHEN** 完整录像结果包及人工核查事务成功提交后服务重启
- **THEN** SHALL 从 PostgreSQL 读取相同稳定 ID 的事实及人工记录，不依赖进程内数据

### Requirement: 鉴权范围与用户状态均有效

Backend SHALL 在 development 以受限配置的 Bearer 摘要映射身份及 scope，并结合数据库的 active／role 和资源授权校验每次业务请求。真实令牌和 DSN 不进入错误正文或日志；production 必须拒绝启用本地引导鉴权。

#### Scenario: 身份或资源不获授权
- **WHEN** 令牌无效、用户被禁用、scope 缺失或录像不在授权内
- **THEN** SHALL 按既有契约拒绝请求，不返回事实、图片或人工记录正文

### Requirement: 不可变配置与受限媒体

Backend SHALL 在启动时验证版本配置与摘要 pin 并保存不可变快照，读取媒体时验证 storage_key 不越过媒体根目录且文件真实存在。

#### Scenario: 配置被覆盖或证据图片缺失
- **WHEN** 配置文件摘要不符或关键帧媒体不存在／路径逃逸
- **THEN** SHALL 分别拒绝启动或返回明确媒体错误，不用新配置替代历史版本，不生成模拟图冒充证据

### Requirement: 完整导入与显式人工留档

Backend SHALL 在单事务发布完整视频结果包，以规范内容和稳定 ID 处理重放与冲突；核查仅由获授权管理员显式保存，引用原事件固定关键帧，操作人与时间由服务端确定。

#### Scenario: 导入失败或重复人工保存
- **WHEN** 结果包校验／写入失败，或相同 review_id 被重放
- **THEN** SHALL 分别整体回滚或按当前幂等契约回读；不同内容冲突，不覆盖事实及既有人工决定

### Requirement: 查询区分真实空结果与范围外

Backend SHALL 按单视频、区域和时间范围返回事件／录像范围语义，保留候选、未知与证据缺口，不把范围外或依赖失败说成已确认零事件。

#### Scenario: 查询没有命中事件
- **WHEN** 查询窗口在有效视频范围内无事件，或完全位于视频范围外
- **THEN** SHALL 按当前契约分别返回对应空结果／范围状态，不能混为同一种成功事实

### Requirement: 空库初始化只允许显式且原子执行

development 的 `init-db` SHALL 只连接显式配置的数据库，在取得 advisory transaction lock 的同一事务内检查 public schema 为空并执行既有 DDL；拒绝已有关系、失败时回滚。应用启动和 readiness 不得建表或重建数据库。

#### Scenario: 重复或并发初始化
- **WHEN** 目标库已有业务关系，或另一个初始化事务刚完成
- **THEN** SHALL 拒绝重复初始化，保留既有数据与结构，不执行 DROP 或静默覆盖

### Requirement: 管理员创建用户不改写既有身份

development 的 `seed-user` SHALL 通过参数化 INSERT 首次创建 active 用户，仅接受 admin／viewer，使用服务端 UTC 时间；重复 ID 必须明确失败，不做 UPSERT。

#### Scenario: 重复创建已有用户
- **WHEN** 管理员提交已存在的 user_id 或在非 development 环境运行引导命令
- **THEN** SHALL 返回明确错误且不修改已有用户角色、资料或历史归属
