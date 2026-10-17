## ADDED Requirements

### Requirement: Shared event process detail
Android SHALL 从聊天相关片段和 SDK 事件进入共用事件详情，按当前授权重新读取真实元数据和关键帧；返回 SHALL 回到原入口并保留可恢复的选择。

#### Scenario: Open a referenced event
- **WHEN** 用户点击某次 Agent 运行的真实事件片段
- **THEN** 详情验证 event 属于预期 recording、evidence 属于该 event，并展示实际时间线和关键帧；失效或越权明确反馈

#### Scenario: Open an Android chat reference independently
- **WHEN** Android 用户点击已有后台事件结果，且主页面存在聊天加载或 SDK 页面生命周期变化
- **THEN** 以仅含录像、事件和可选证据标识的内部导航打开独立详情宿主，复用同一受权仓库；宿主不对其他应用导出，返回恢复原对话，不开始新分析

### Requirement: Exact SDK provenance link
SDK 详情 SHALL 使用设备 SN 与原生事件 ID 对应的稳定 job 标识，并核对服务、镜头、配置及服务端原生事件；MUST 不通过相似时间、设备名或虚构 Event ID 建立关联。

#### Scenario: SDK event has no published analysis
- **WHEN** SDK 原事件尚未入库、正在处理、解析失败或缺有效区域
- **THEN** 详情展示对应状态与 SDK 检测信息，已支持时保留显式回放，不将其解释为零事件或分析完成

### Requirement: Evidence without invented continuity
详情 SHALL 展示真实可用关键帧、来源和相对时间，缺失图像或未知时间 SHALL 明确反馈；MUST 不复制设计图中的遮挡结论、示例设备或跨镜连续性。

#### Scenario: Missing media
- **WHEN** 某关键帧缺失或读取失败
- **THEN** 保留引用和时间线，显示缺图，不替换为样例或声称已查看画面

### Requirement: Authorized original recording replay
详情 SHALL 允许用户显式回看已授权且媒体可用的真实后台录像，从所选事件起始偏移定位，并保留整段上下文。后台原片 MUST 使用当前聊天身份和固定录像媒体端点，不依赖摄像头 SDK 登录，不将凭据放入 URL、导航或日志。

#### Scenario: Replay a prerecorded event
- **WHEN** 用户在已校验的真实预录或 SDK 录像事件详情点击回看原片
- **THEN** 客户端用同身份读取该会话绑定的录像，私有缓存后定位至事件起始偏移播放；用户可查看完整录像上下文

#### Scenario: Reject changed identity or failed media
- **WHEN** 服务或凭据变化、媒体请求越权、返回错误、超出大小限制或无法解码
- **THEN** 客户端明确报告失败，不打开其他来源、不跟随重定向、不冒充播放成功，并清理临时原片

#### Scenario: Leave replay
- **WHEN** 用户切换事件、返回关键帧、离开前台或销毁详情
- **THEN** 客户端取消未完成读取、停止播放器并释放私有临时原片

#### Scenario: SDK logout while viewing a backend event
- **WHEN** 独立授权的后台事件详情位于栈顶时摄像头 SDK 会话失效
- **THEN** 关闭和清理 SDK 页面与任务，不结束独立后台详情宿主；共享导航宿主保留 Home 与当前后台目标并重新验证后台身份，独立宿主在前台恢复和每次读取时继续验证同一后台身份；重复 SDK Logout 不再次销毁该详情或播放器

#### Scenario: SDK logout while viewing an SDK screen
- **WHEN** SDK 页面位于栈顶时摄像头 SDK 会话失效
- **THEN** 返回 Home 并清理 SDK 状态，不恢复历史后台页面；后台凭据失效也不授予事件或媒体访问
