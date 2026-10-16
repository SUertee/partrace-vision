## ADDED Requirements

### Requirement: Station interface preserves live integrations
Android SHALL 按用户提供的六张设计稿展示首页、AI、消息与审核记录，并保留现有真实数据入口。参考见 [设计图](../../../../../android/ui-design/references/)。

#### Scenario: Home displays SDK devices
- **WHEN** SDK 返回当前账号设备
- **THEN** 首页按设计卡片展示真实名称、状态与最近预览，任务配置入口仍可通过菜单访问

#### Scenario: Assistant opens before a question
- **WHEN** 当前没有提问、计划或运行
- **THEN** AI 页面展示原稿欢迎语、建议问题、四个条件入口与渐变输入框，服务配置和范围选择仍可操作

### Requirement: Messages and review design states
Android SHALL 为消息提供通知提示、空态和月份列表组件，为审核记录提供可搜索的只读列表；设计样例仅在预览使用。

#### Scenario: No real alerts
- **WHEN** 没有真实告警数据
- **THEN** 消息中心展示空态，不把录像任务或设计文案冒充真实告警

#### Scenario: Review read fails
- **WHEN** 审核记录未配置、越权或读取失败
- **THEN** 页面展示对应状态与操作入口，不展示伪造的已保存记录
