## ADDED Requirements

### Requirement: SDK device detail follows the supplied layout
Android SHALL 按设备详情参考稿组织原 SDK 直播、控制和录像入口，保留原设备能力判断和实际调用。

#### Scenario: Open a camera
- **WHEN** 用户点击首页摄像头
- **THEN** 显示该设备真实名称、原 SDK 播放器、快捷操作及当前设备事件录像入口

#### Scenario: Additional controls
- **WHEN** 用户打开更多控制
- **THEN** 该设备原有支持的功能仍可使用，不以设计稿为由删减

#### Scenario: View recordings
- **WHEN** 用户选择事件、回放或全部录像
- **THEN** 进入当前设备原 SDK 事件录像列表，保留日期筛选、回放、下载及删除能力

#### Scenario: Leave or enter fullscreen
- **WHEN** 用户切换全屏或返回
- **THEN** 继续使用同一播放器集成，最终离页释放原 SDK 资源
