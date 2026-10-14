## ADDED Requirements

### Requirement: 模块与契约来源一致

共享规格 SHALL 引用 [模块设计](../../../../../docs/modules/README.md)、[SQL](../../../../../docs/database/schema.sql)、[OpenAPI](../../../../../docs/api/openapi.json) 与当前 v0.3 样例；行为场景不重复维护 HTTP 或持久化字段。设计仍待团队确认，工具检查不代表协议冻结或业务已实现。

#### Scenario: 四模块评审同一版本
- **WHEN** 团队按本提案核对首轮范围与交接
- **THEN** 场景 SHALL 可通过 [关联表](../../scenario-map.md) 追溯至 Mxx、当前 API、表和样例，并保留版本与剩余确认项

### Requirement: 稳定标识与可选 UTC

共享契约 SHALL 保留稳定的录像、事件和关键帧 ID，以视频相对毫秒定位；SQL 绝对时间与 API UTC 字符串遵循当前定义。未验证起始时间时只使用偏移，不构造 UTC。

#### Scenario: 同一事件被 App 和 Agent 引用
- **WHEN** 核对算法导出、后端查询、App 开帧和 Agent 引用样例
- **THEN** 契约 SHALL 指向同一 Event／Evidence ID 与源视频偏移，只有已验证的起点才允许转换 UTC

#### Scenario: 视频起点未知时请求 UTC
- **WHEN** 录像 started_at 为空而请求使用 UTC 窗口
- **THEN** 契约 SHALL 规定 clock_unverified 错误；合法偏移查询仍可使用，两种时间窗口不能混用

### Requirement: 来源与候选语义独立表达

共享契约 MUST 区分模拟、人工标注、真实预录及适配成连续视频的 SDK 输入；官方原生检测事件和离散缩略图不进入当前完整录像包。观测、候选与未知不得推导未经验证的动作、身份或包裹归属。

#### Scenario: 模拟候选包含跟踪断点
- **WHEN** 团队核对样例来源与结果
- **THEN** 文档与样例 SHALL 明示模拟及候选性质，断点保持未知，不把离开画面写成离站、连续路线或责任判断

### Requirement: 完整录像查询与错误分别表达

共享契约 SHALL 定义单视频、镜头和区域查询，以已完整发布录像推导交集范围；事件命中、范围内未检出、范围外与导入／读取错误分别表达。首轮不要求全局采集台账、待处理索引或覆盖表。

#### Scenario: 没有命中与视频范围外
- **WHEN** 核对 no_detections 与 outside_recording 样例
- **THEN** 契约 SHALL 明示各自原因及 recording_range，不由空列表推断无人经过或驿站没有采集

#### Scenario: 请求只有部分范围在视频内
- **WHEN** 查询与已发布录像部分相交
- **THEN** 契约 SHALL 返回实际交集与 partial 范围，录像外保持未知，不补造缺口事实

#### Scenario: 完整包导入失败
- **WHEN** 包内容校验或导入处理失败
- **THEN** 契约 SHALL 返回诊断错误，不发布半包、不模拟成功或把失败解释为无采集

### Requirement: 缺失媒体保留历史引用

共享契约 SHALL 使用 available／missing 表达媒体状态；媒体删除或失效后仍保留稳定 Evidence ID 与核查历史，实际读取缺失媒体返回明确错误。

#### Scenario: 核查引用的关键帧已不可读取
- **WHEN** 回读记录时原图缺失
- **THEN** 契约 SHALL 保留引用及 missing 状态，媒体读取返回 media_missing，不用占位图充当真实帧，不新增 expired 状态

### Requirement: 语义向量空间与索引缺口可核对

共享契约 SHALL 将事件描述绑定 Event 及可选同事件 Evidence，固定模型／编码版本／维度；后端生成查询向量并按授权范围、相同模型空间检索。分数只排序候选，向量未索引是语义检索状态，不是录像无采集。

#### Scenario: 全部或部分事件没有描述向量
- **WHEN** 核对 unindexed 与 partial 索引样例
- **THEN** 契约 SHALL 返回 eligible／indexed／missing 数量，全部未索引时明确 unindexed，部分索引时仍报告缺口；阈值导致无匹配不能掩盖索引缺失

#### Scenario: 模型空间不匹配或存储失败
- **WHEN** 模型／编码版本／维度不符或依赖故障
- **THEN** 契约 SHALL 拒绝不合法输入或返回服务错误，不混排模型空间、不接受客户端任意查询向量、不伪装成零命中

### Requirement: 幂等导入与单事件人工核查

共享契约 MUST 规定稳定请求 ID 的重试与内容冲突；管理员显式确认后才追加一个事件的人工决定，身份与时间由服务端填写，人工记录与原事件分别持久化。

#### Scenario: 同一导入或保存请求重试
- **WHEN** 团队核对同 ID 同内容与同 ID 不同内容样例
- **THEN** 契约 SHALL 分别回读原结果或返回冲突，不重复追加记录，且重试仍验证身份与资源权限

#### Scenario: 重启后回读人工核查
- **WHEN** 核对一条单事件核查的保存与历史回读
- **THEN** 契约 SHALL 恢复原查询、事件及关键帧引用、说明与决定，不覆盖原事件；真实重启验证仍由 D09／D10 执行

### Requirement: AI 本人历史与运行范围独立

共享契约 SHALL 持久保存本人 conversations／messages／runs；会话不固定录像，每次计划与运行仍查询一段录像。会话／用户问题校验 active owner；运行与助手历史按本次范围、当前权限和引用归属校验。管理员也不能跨 owner。

#### Scenario: 同会话不同分析查询不同录像
- **WHEN** 同一会话的新问题选择另一段视频
- **THEN** 契约 SHALL 将范围保存到本次确认计划，不用会话或上一轮录像代替运行范围；录像撤权不隐藏本人会话列表

#### Scenario: 历史消息页包含无权助手消息
- **WHEN** 分页已确定且某助手答复对应的运行录像不再授权
- **THEN** 契约 SHALL 整页返回 403，不返回正文、部分消息或 next_cursor，不静默跳过无权答复

#### Scenario: 完成答复与运行状态同时保存
- **WHEN** 已授权运行成功完成
- **THEN** 契约 SHALL 重新验权后原子保存 completed 状态和唯一完整助手答复；用户问题不能冒充助手消息，历史保存不赋予事实写权限

### Requirement: Agent 事实只读与运行终态稳定

共享契约 SHALL 规定后端事实工具只读、有效 Event／Evidence 引用，以及 running／completed／stopped／failed 状态和终态规则。Agent 可以保存自己的 AI 历史，但不导入事实、写向量、人工核查或包裹流水。

#### Scenario: 工具结果在停止后返回
- **WHEN** stopped 运行收到迟到结果
- **THEN** 契约 SHALL 禁止追加答复、引用或改成 completed；stopped／failed 不保存伪完成消息，summary 为空

#### Scenario: 服务重启留下未完成运行
- **WHEN** 服务恢复时发现旧 running
- **THEN** 契约 SHALL 将其标为 failed／service_restarted，不自动续跑；重试使用新 run ID，原历史可回读

#### Scenario: Agent 结果引用事实
- **WHEN** 审查已完成答复的事实句
- **THEN** 契约 SHALL 验证引用属于本次录像／事件范围且关联真实帧；缺图只表达候选或未知，工具失败不得伪造证据

### Requirement: 首轮与 P1 范围分别评审

本次 D01 SHALL 对齐首轮 M01–M08 的契约行为；M09 SDK 独立验证，M10 包裹入出库保持 P1、D10 后实施。proposed 接口与未定义运行列表不得当作现有契约，包裹后续用独立 change 评审。

#### Scenario: 核对现有 API 数与 P1
- **WHEN** 团队检查当前 27 个草案操作
- **THEN** 文档 SHALL 区分首轮 22 个与包裹 5 个，保持扫码只查询、管理员确认才写流水、Agent 只读历史的边界；设计存在不表示 P1 已实现或冻结
