## Context
参考为用户设备详情图，见 android/ui-design/references/device-detail.jpg。复用 MegaLiveVideoScreen 和 MegaLiveStreamViewModel，避免创建第二套播放器或模拟 SDK 成功。

## Decisions
- 保持播放器在同一组合位置，横竖屏只隐藏详情控件，不因布局切换新增播放器。
- 快捷区放云台、对讲、录像、截图和全屏；更多面板保留静音、灯光、跟踪、校准、360、全景、预设位、快捷回复、双镜头、夜视及手动报警。
- 快捷按钮根据设备能力和真实直播状态启用；全屏与更多始终可达。
- SDK 没有独立连续录像页面，Playback 和 All Recordings 共用该设备原有事件录像列表，保留筛选、回放与下载。
- 保留播放器退出释放、权限请求及真实错误，预览样例不进入生产状态。

## Verification
Debug 构建、lint，已有直播相关回归；模拟器核对详情布局、事件导航、更多面板、设置及返回，并保存无真实画面的独立预览。实际变更与未测项写入 android/ui-design/README.md。
