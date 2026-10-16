# Android 六张设计稿还原

2026-10-17，使用现有 Kotlin / Compose 实现。设计图为 824×1834px，以 412×917dp 为基准；本轮原生截图来自 Medium Phone、Android 17 / API 37、arm64、1080×2400px、420dpi。正式页面保留真实系统栏、手势区及输入法 inset。

## 参考与实现

下面六张实现图通过 APK 内已有的 Compose `PreviewActivity` 在模拟器运行、截图并逐张检查。预览函数只传设计样例，不初始化 Agent 或写入业务数据。首页用两张黑底样例卡展示布局；正式首页使用真实 SDK 名称、在线状态与最近缩略图，因此不复制设计稿中的“68”。预览状态栏沿用工具 Activity 的系统外观；正式页面使用宿主的系统栏设置。

| 设计状态 | 用户参考图 | 原生实现预览 |
| --- | --- | --- |
| 设备首页 | [参考](references/home.jpg) | [实现](rendered/home.png) |
| AI 欢迎页 | [参考](references/assistant.jpg) | [实现](rendered/assistant.png) |
| 消息空态 | [参考](references/messages-empty.jpg) | [实现](rendered/messages-empty.png) |
| 消息通知提示与留白 | [参考](references/messages-notification.jpg) | [实现](rendered/messages-notification.png) |
| 消息月份列表 | [参考](references/messages-list.jpg) | [实现](rendered/messages-list.png) |
| 审核记录 | [参考](references/reviews.jpg) | [实现](rendered/reviews.png) |

正式页面另存 [AI 欢迎页](rendered/assistant-device.png)、[消息空态](rendered/messages-device.png) 与 [键盘避让](rendered/assistant-keyboard.png)。截图不包含真实摄像头画面或凭据。

## 页面与数据

- 首页保留设备卡、多画面入口和三项底部导航。人物录像任务与连接状态移入设备菜单，任务配置在我的仍可进入。
- AI 保留原稿欢迎语、三个建议、四个条件按钮和渐变输入框。建议只填入问题，用户发送后才提交；`@设备` 选择已发布录像，时间展示整段录像范围，事件提供问题建议，区域选择已配置范围。右上历史菜单保留新建会话、后端服务设置及分页历史。计划编辑与确认、运行／停止／重试、结果引用和关键帧逻辑保留；连接失败即使尚无会话也会显示错误。
- 消息中心根据真实系统通知状态显示提示，并在返回前台时重读。当前没有真实告警源，正式页面显示空态；月份报警卡片只在设计预览中传入。
- 首页菜单和我的均可进入审核记录。列表及详情消费既有 `GET /api/v1/review-records` 和 `GET /api/v1/review-records/{id}`，复用独立 Backend 配置和 Bearer，不调用模型或保存接口。列表支持本地编号／备注／时间搜索，时间由 UTC 转本机时区。未配置、读取失败、空列表和无匹配分别反馈。
- 顶栏返回与物理返回使用进入消息／审核页时的来源。SDK 原工程入口保留。后端、env、接口及数据库契约没有因本轮 UI 调整改动。

## 本轮验证

从 Android 工程目录使用 JDK 17.0.2 运行：

```sh
./gradlew :app:assembleDebug :app:lintDebug :sample-core:testDebugUnitTest --tests '*TraceAgent*' --console=plain
```

最终构建成功；8 项现有 Agent 协议／网络回归通过；lint 无 Error/Fatal，当前工程报告 133 项 Warning。完整 APK 安装使用 `android run --use-delta-install=false`，保留应用数据。

实际核对：正式首页读取 SDK 设备；AI 欢迎页、新建会话和历史菜单可达；输入框聚焦后随键盘上移；首页菜单进入审核列表后读取到现有 `rev_mock_001`，详情返回后端已有的模拟核查说明；搜索 `no_match_ui` 显示无匹配反馈；通知“启用”打开本应用真实系统设置。“我的 → 审核记录 → 物理返回”回到我的，导航仍保持选中。上述读取没有创建或修改审核记录。

六张预览位于 [StationDesignPreviews.android.kt](../ESIotKit-DemoApp-Common/sample-core/src/androidMain/kotlin/com/anker/esiotkit/sample/main/station/StationDesignPreviews.android.kt)。示例运行命令：

```sh
adb shell am start --activity-clear-top \
  -n com.anker.esiotkit.demo/androidx.compose.ui.tooling.PreviewActivity \
  --es composable com.anker.esiotkit.sample.main.station.StationDesignPreviews_androidKt.MessageAlarmDesignPreview
android layout --device=emulator-5554 --full
android screen capture --device=emulator-5554 --output=/tmp/messages-list.png
```

本机 Android Studio CLI 预览尝试返回 `Unknown error`，未产出图片；这里记录的是实际模拟器 Compose 预览，没有将工具失败描述为成功。OpenSpec 本 change 严格校验、`git diff --check` 和文档五项离线同步检查均通过。

本轮未重验摄像头直播、外部模型效果、真机、多分辨率及完整业务闭环；模拟器界面检查不代替 D06/D08/D09/D10 验收。原生字体和系统安全区会造成与静态稿少量差异。

关联 [本轮提案](../../openspec/changes/d02-ui-design-match/proposal.md)、[Android 说明](../README.md) 和 [模块设计](../../docs/modules/README.md)。

## SDK 设备详情追加还原

2026-10-17，按用户新增的 [设备详情参考](references/device-detail.jpg) 重排 `MegaLiveVideoScreen`。[最终原生预览](rendered/device-detail.png) 使用设计名称和离线时间样例，不读取或展示真实画面；正式详情显示真实设备名称及原 SDK 播放器状态，不使用样例离线时间。画面保持 SDK 实际宽高比，竖屏高度有上限，卡片区域可滚动，真实视频比例不一定与参考的 16:9 相同。

- 顶部：返回、设备名、更多设备控制、原设备设置。
- 快捷条：云台、对讲、录像、截图、全屏，根据真实设备能力／直播状态启用。画面底部保留播放／暂停、码率和录像时长。
- 更多控制：保留原有录像、截图、对讲、声音、灯光、跟踪、校准、360°、全景、快捷回复、预设位、云台、双镜头、夜视；原顶栏手动报警也移入该面板。报警倒计时和停止、预设位强制保存确认等仍使用原逻辑。当前设备支持哪些能力，由原 SDK 判断。
- Events / Playback / All Recordings：分别进入标题为“设备事件”“录像回放”“全部录像”的原 SDK 事件列表，默认筛选当前设备，保留日期／类型／设备筛选、片段播放、下载、批量下载、删除等原菜单。当前 SDK 示例无独立连续录像页面，这些入口提供事件片段回放。
- 右侧卡片：设计稿的商业云存储位改为真实录像下载队列；完整设备／存储设置仍从顶部齿轮进入，没有伪造订阅、开通或存储状态。

代码见 [布局组件](../ESIotKit-DemoApp-Common/sample-core/src/commonMain/kotlin/com/anker/esiotkit/sample/main/mega/live_video/DeviceLiveDetailLayout.kt)、[SDK 直播集成](../ESIotKit-DemoApp-Common/sample-core/src/commonMain/kotlin/com/anker/esiotkit/sample/main/mega/live_video/MegaLiveVideoScreen.kt)、[事件入口](../ESIotKit-DemoApp-Common/sample-core/src/commonMain/kotlin/com/anker/esiotkit/sample/main/mega/event/MegaEventScreen.kt)。未修改直播 ViewModel、SDK 二进制、Backend、Agent 或 env。

本轮最终执行 `:app:assembleDebug :app:lintDebug :sample-core:testDebugUnitTest --tests '*MegaLiveStream*'` 成功；2 项现有直播 PN 常量测试通过，不将其当作设备控制实测。对比修改前后的 SDK action 集合，原有 18 种 action 均保留。完整安装最终 APK 后重录预览；模拟器环境与上文相同。

本轮操作验证了首页进入真实设备详情、实际设备能力的更多面板、Events / Playback / All Recordings 默认摄像头筛选、选择真实片段进入原回放页、下载队列空状态、原设备设置和往返；全屏隐藏卡片，返回后仍在详情并显示 SDK 播放状态与码率。没有保存真实画面截图，没有发起下载、删除、对讲、手动报警、云台转动或修改设置。功能调用与真机效果未逐项实测，仍按 D06 独立验收。

OpenSpec [d02-sdk-device-detail](../../openspec/changes/d02-sdk-device-detail/proposal.md) 严格验证、文档链接、空白检查及五项文档同步检查通过。
