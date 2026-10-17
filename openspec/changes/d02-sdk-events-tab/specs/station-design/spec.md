## RENAMED Requirements

- FROM: `### Requirement: 默认进入三入口设计壳并保留官方调试路径`
- TO: `### Requirement: 默认进入四入口设计壳并保留官方调试路径`

## MODIFIED Requirements

### Requirement: 默认进入四入口设计壳并保留官方调试路径

Android SHALL 默认启动 Kotlin/Compose 驿站设计壳，提供首页、事件、AI、我的四个入口与独立消息中心。我的 SHALL 保留官方 `SampleApp` SDK 调试入口。AI SHALL 保留全屏聊天与明确返回操作；首页、事件、我的 SHALL 使用一致的四入口底部导航。此次入口扩展不代表 D02／D06／D08 业务全部验收。

#### Scenario: 启动与往返导航
- **WHEN** 用户启动并切换首页、事件、AI、我的，或打开并返回消息、事件回放及 SDK 调试
- **THEN** 页面 SHALL 正确显示对应内容及返回目标，事件页日期／筛选和底栏选中态保持可辨，SDK 账号状态重建后仍能恢复合法主页面
