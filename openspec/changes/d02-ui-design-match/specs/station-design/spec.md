## ADDED Requirements

### Requirement: Station interface preserves live integrations
Android SHALL 按用户提供的六张设计稿展示首页、AI、消息与审核记录，并保留现有真实数据入口。参考见 [设计图](../../../../../android/design/references/)。

#### Scenario: Home displays SDK devices
- **WHEN** SDK 返回当前账号设备
- **THEN** 首页按设计卡片展示真实名称、状态与最近预览，任务配置入口仍可通过菜单访问

#### Scenario: Assistant opens before a question
- **WHEN** 当前没有提问、计划或运行
- **THEN** AI 页面沿用原稿的欢迎布局、建议问题、四个条件入口与渐变输入框，示例和占位提示对应当前已支持的录像活动及证据查询，服务配置和范围选择仍可操作

#### Scenario: Assistant keeps scope selection in existing controls
- **WHEN** 用户进入聊天或已选择一段可查询录像
- **THEN** 页面不显示顶部“新问题的查询范围”卡片；底部录像、录像时段和区域入口继续可用，确认计划及运行卡片保留实际范围，不暗示自由编辑时间区间

#### Scenario: Failed analysis remains actionable
- **WHEN** 服务端返回失败任务
- **THEN** 页面保留失败说明和重新分析入口，错误码及内部录像编号可展开查看，不隐藏失败或伪造成功；证据引用仍按真实 ID 操作，面向用户的按钮和来源状态使用可读文案

### Requirement: Messages and review design states
Android SHALL 为消息提供通知提示、空态和月份列表组件，为审核记录提供可搜索的只读列表；设计样例仅在预览使用。

#### Scenario: No real alerts
- **WHEN** 没有真实告警数据
- **THEN** 消息中心展示空态，不把录像任务或设计文案冒充真实告警

#### Scenario: Review read fails
- **WHEN** 审核记录未配置、越权或读取失败
- **THEN** 页面展示对应状态与操作入口，不展示伪造的已保存记录

### Requirement: Profile presents saved review records
Android SHALL 按用户新增第一张参考图将“我的”展示为蓝白渐变品牌头、右上设置齿轮、常驻“检索记录”搜索、“全部”和审核卡片，并保留底部三项导航。审核列表与详情 SHALL 复用既有只读数据源，不新增保存操作。

#### Scenario: Profile loads existing records
- **WHEN** 用户进入“我的”且后端返回已保存审核记录
- **THEN** 页面展示真实事件编号、本机时区时间及“已保存”卡片，点击后展示既有详情中的真实核查结论，不使用设计样例替代后端数据

#### Scenario: Profile searches records
- **WHEN** 用户在常驻搜索框输入事件编号、审核编号、备注或时间
- **THEN** 页面对已读取记录进行本地匹配，无匹配时明确反馈，清空后恢复全部记录

#### Scenario: Profile cannot display records
- **WHEN** 正在加载、未配置、读取失败或后端返回空列表
- **THEN** 页面显示相应状态，失败可重试，未配置可进入服务设置，右上齿轮及导航保持可操作

### Requirement: Profile settings preserves existing tools
Android SHALL 将原“我的”页的 SDK 调试移入右上角齿轮设置面板，并保留消息中心、人物录像自动解析、审核记录与服务设置入口。

#### Scenario: Open SDK tools from settings
- **WHEN** 用户从“我的”点击齿轮并选择 SDK 调试
- **THEN** 应用通过现有回调进入官方 SDK 原工程，返回后仍可使用驿站界面

#### Scenario: Close profile settings
- **WHEN** 设置面板显示时用户按返回、点击关闭或点击遮罩
- **THEN** 面板关闭，底层页面在面板显示期间不响应穿透点击，原页面与底部导航选择保留

#### Scenario: Search with keyboard visible
- **WHEN** 用户在“我的”搜索框中输入且键盘可见
- **THEN** 页面遵循系统栏、键盘与底部导航的安全区，记录列表仍可滚动，页面不复制设计图中的时间或系统图标
