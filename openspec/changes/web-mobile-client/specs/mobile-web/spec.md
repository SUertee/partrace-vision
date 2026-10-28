## ADDED Requirements

### Requirement: Mobile web reuses backend contracts

Web SHALL 通过 `web/src/api/` 统一访问 [OpenAPI](../../../../../docs/api/openapi.json) 已定义的 `/api/v1` 接口，MUST 不新增或修改 Backend 接口，MUST 不在应用中内置模拟业务数据。

#### Scenario: Backend not configured
- **WHEN** 用户未配置 Bearer 打开首页、事件、AI 追溯或审核记录
- **THEN** Web 显示中文提示并提供服务设置入口，不显示模拟设备或事件

#### Scenario: Credential rejected
- **WHEN** 任一接口返回 401
- **THEN** Web 显示凭据失效的中文提示并引导到服务设置，不显示原始错误对象

### Requirement: Event and evidence viewing

Web SHALL 按录像、区域读取事件，区分 `matched`、`no_detections` 与 `outside_recording`，并标明录像来源与检测来源。关键帧 MUST 通过带凭据的媒体接口读取，媒体缺失时保留原始引用。

#### Scenario: Zone without detections
- **WHEN** 某区域查询返回 `no_detections`
- **THEN** Web 显示该区域覆盖内零事件，而非接口错误

#### Scenario: Keyframe missing
- **WHEN** 证据 `media_status` 为 `missing` 或媒体接口返回 `media_missing`
- **THEN** Web 显示关键帧缺失并保留证据 ID

### Requirement: Agent trace with streaming state

Web SHALL 保留 Android 的计划确认流程：用户选择真实录像与区域、提交问题、修改并确认计划后才开始运行。运行状态 MUST 通过 SSE 更新，异常断开时回读运行；用户可停止运行，失败后可用新 run_id 重试同一计划。

#### Scenario: Stop a running analysis
- **WHEN** 用户在运行中点击停止
- **THEN** Web 调用停止接口，并在终态为 stopped 后不再追加结果

#### Scenario: Stream closes before terminal state
- **WHEN** SSE 连接在终态前结束
- **THEN** Web 回读运行；仍为 running 时重新订阅，已终态时显示终态结果

### Requirement: SDK-only capabilities stay explicit

摄像头在线状态、直播、SDK 检测记录与回放 SHALL 标注为仅 App 可用，Web MUST 不以模拟结果代替。

#### Scenario: Open multi-view preview
- **WHEN** 用户在首页打开多画面预览
- **THEN** Web 显示后端已知镜头及最近录像信息，并说明直播画面仅 App 可用
