## Purpose

本能力依据[接入提案](../../proposal.md)，约定本地媒体与 S3 之间可替换、可验收的存储行为，保证原片、关键帧、事件引用和人工核查在存储切换或故障后仍可核对。本文件属于待评审设计，不表示服务已经接入；字段与成功响应沿用 [SQL 契约](../../../../../docs/database/schema.sql)、[OpenAPI 契约](../../../../../docs/api/openapi.json)和 [Vision 联动约定](../../../../../docs/modules/BACKEND_VISION.md)。

## ADDED Requirements

### Requirement: Select one media backend explicitly

系统 MUST 确定唯一活动媒体后端为 local 或 s3；未设置新增存储选项的既有本地配置 SHALL 保持兼容。显式选择 s3 时，缺少必要配置 MUST 拒绝启动，远端运行故障 MUST 返回可诊断错误，不得静默切换为本地写入或读取。

#### Scenario: Existing local deployment remains usable
- **WHEN** 既有本地部署未启用新增 S3 选项
- **THEN** 系统 SHALL 继续通过原有受限媒体存储完成录入和鉴权读取

#### Scenario: Selected remote backend is unusable
- **WHEN** 已选择 s3 但必要配置缺失，或运行中无法访问远端媒体
- **THEN** 系统 MUST 分别拒绝启动或返回存储依赖错误，不得另存一份本地媒体并报告成功

### Requirement: Preserve media identities and public contracts

系统 SHALL 在本地与 S3 模式下保留既有逻辑 storage_key、稳定 Recording／Event／Evidence ID、成功响应结构及鉴权媒体 API；现有数据切换后 MUST 仍按原存储键关联。调用方 MUST 不需要获得 bucket、对象地址、内部路径或 AWS 凭据才能读取获授权媒体。

#### Scenario: Existing evidence is read after switching storage
- **WHEN** 调用方通过既有 Evidence ID 和媒体 API 访问已完成存储迁移的关键帧
- **THEN** 系统 SHALL 使用原引用关系返回同一媒体内容，并保留原成功响应及私有且不缓存的访问约定

### Requirement: Publish only complete validated media

系统 MUST 在当前请求声明的全部新增真实媒体通过既有格式、解码、时长、大小及摘要校验且可读取后，才能确认原片接收或发布完整事实。部分媒体失败 MUST 不留下已发布的半包事件或证据；原片接收的 pending 语义及显式 mock 导入约定 SHALL 保持不变。

#### Scenario: A media bundle is only partly available
- **WHEN** 请求声明的任一真实媒体未完成保存、校验失败或不可读取
- **THEN** 系统 MUST 拒绝该次接收或发布，不得暴露引用不完整媒体的新事实包

#### Scenario: Vision result publication fails
- **WHEN** 已接收原片的任务提交结果，但新增关键帧未能全部保存并校验
- **THEN** 系统 MUST 保留既有原片和 pending 录像，不得将录像发布或将任务标记为 completed

### Requirement: Retain media while commit outcome is uncertain

系统 MUST 在数据库提交结果无法确定时保留本次可能被引用的媒体，且不得将未知结果报告为已完成或确定回滚。只有事务结果已确定且再次核验无数据库引用的本次新增对象，才 SHALL 允许被清理；数据库不可访问本身 MUST 不构成删除依据。

#### Scenario: Database disconnects during commit
- **WHEN** 发布事务在提交阶段失联，无法确定事实或原片任务是否已经提交
- **THEN** 系统 MUST 保留相关媒体并返回依赖错误，允许后续按原稳定身份和请求内容核对真实结果

### Requirement: Preserve idempotency and reused source media

系统 SHALL 沿用既有同身份同内容重放、异内容冲突及 worker 租约规则。Vision 结果 MUST 复用任务已保存且摘要匹配的原片；失败、重复请求或过期租约的清理 MUST 不得删除该原片、已提交媒体或其他请求的对象。

#### Scenario: Identical request is replayed
- **WHEN** 相同录像或任务身份提交与既有内容相同的有效请求
- **THEN** 系统 SHALL 返回既有结果且不重复发布事实，保留原片和已提交证据；同身份异内容 MUST 返回冲突

#### Scenario: Reused source or lease is invalid
- **WHEN** Vision 提交的原片摘要与任务不符，或提交者持有过期或已替换的租约
- **THEN** 系统 MUST 拒绝发布，保留既有任务原片、事实及其他有效尝试的媒体

### Requirement: Authorize every media access

系统 MUST 继续根据当前身份、操作范围、镜头和录像授权决定媒体访问；worker 原片读取还 MUST 满足当前任务租约。存储切换 SHALL 不扩大访问范围；未授权调用方 MUST 无法绕过现有 API 直接获得私有对象，响应和诊断不得泄露访问凭据。

#### Scenario: Permission or worker lease is revoked
- **WHEN** 用户资源授权已撤销，或 worker 使用失效租约读取原片
- **THEN** 系统 MUST 按现有契约拒绝本次媒体访问，不得返回对象内容或可继续访问该对象的地址

### Requirement: Distinguish missing media from dependency failures

系统 MUST 将确证不存在、损坏或超限的媒体按既有 missing 与 media_missing 约定处理，并保留历史 ID。远端超时、服务端拒绝访问或临时服务错误 MUST 返回存储依赖错误，不得伪装成媒体缺失或成功空结果；元数据、媒体读取与 Agent 引用检查 SHALL 保持一致。

#### Scenario: Referenced image is missing or corrupt
- **WHEN** 已引用关键帧被确证不存在、损坏或超过既有限额
- **THEN** 系统 SHALL 保留引用身份，元数据表达 missing，媒体读取返回 media_missing，且不得将该帧确认为有效引用

#### Scenario: Object service is temporarily unavailable
- **WHEN** 对象服务超时、拒绝服务端访问或返回临时故障
- **THEN** 系统 MUST 返回存储依赖错误，不得改写为 missing、无事件或有效媒体，Agent 不得据此确认引用有效

### Requirement: Verify migration and preserve rollback data

本地与 S3 切换 MUST 在暂停媒体录入及结果发布的维护窗口内核验全部数据库引用，包含 pending 录像和未完成或失败任务。只有原存储键对应字节、大小、摘要及必要媒体有效性全部通过，才 SHALL 切换活动后端；回滚 MUST 覆盖切换后新增引用，不能仅切回缺少新媒体的旧目录。

#### Scenario: Migration encounters missing or conflicting bytes
- **WHEN** 任一数据库引用的源媒体缺失，或目标同键对象与源内容不一致
- **THEN** 系统 MUST 中止切换并报告可核对差异，保留原活动存储及已有对象，不得覆盖冲突对象后宣称迁移完成

#### Scenario: Rollback is requested after new writes
- **WHEN** 切换到 S3 后已产生新的媒体和数据库引用，再请求回滚
- **THEN** 系统 MUST 先确保回滚目标覆盖全部当前引用并通过一致性核验；未满足时不得启用缺少新媒体的目标，原存储 SHALL 在约定恢复窗口内保留

### Requirement: Collect orphans without deleting referenced media

系统 MUST 默认只报告孤儿候选；实际回收仅 SHALL 在媒体写入已暂停、在途请求已结束且数据库可核验的维护窗口执行。删除前 MUST 再次排除 recordings、recording_jobs 和 evidence 的全部引用，包含 pending 及失败任务；对象年龄或未更新标记 MUST 不得单独作为最终媒体删除依据。

#### Scenario: Collector sees a source referenced by an unfinished job
- **WHEN** 回收候选仍被 pending 录像、未完成或失败任务、已发布事实中的任意对象引用
- **THEN** 系统 MUST 保留该媒体，并只允许回收已再次确证无引用的孤儿对象

#### Scenario: Safe collection conditions are absent
- **WHEN** 仍存在在途媒体请求、数据库不可访问或提交结果尚不明确
- **THEN** 系统 MUST 拒绝实际删除并保留候选清单，不得仅凭对象存在时间或发布标记缺失清理媒体
