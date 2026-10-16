# Android 设计还原与验证

2026-10-16，按用户提供的五张设计图还原。设计基准约为 412×917dp；运行截图为 1080×2400px / 420dpi。真实 Android 状态栏、手势导航区和输入法占用空间由系统 inset 处理，因此底部位置随设备可用高度变化。

| 设计状态 | 运行截图 |
| --- | --- |
| 设备首页 | [home.png](home.png) |
| 消息中心 | [messages.png](messages.png) |
| P.T.Vision / AI 追溯 | [ai.png](ai.png) |
| 通知静音弹层 | [notifications.png](notifications.png) |
| 站点选择浮层 | [sites.png](sites.png) |
| 320dp 宽、1.3 倍字体检查 | [messages-compact.png](messages-compact.png) |

入口代码位于 [station/](../ESIotKit-DemoApp-Common/sample-core/src/commonMain/kotlin/com/anker/esiotkit/sample/main/station/)，平台启动与系统通知设置在 [ESlotKitMainActivity.kt](../ESIotKit-DemoApp-Android/app/src/main/java/com/anker/esiotkit/sample/main/ESlotKitMainActivity.kt)。默认启动 `StationApp`，原官方 `SampleApp` 由「我的 → SDK 调试」进入，并可返回驿站界面。

## 本次验证环境与命令

- JDK 17.0.2、Gradle 8.13、AGP 8.11.1、Kotlin 2.2.21；compile/target SDK 36，minSdk 26，未升级依赖或更改包名。
- Android Emulator 37.2.12，Android 17 / API 37、arm64、1080×2400、420dpi；单独临时 AVD 验证，数据不与已有设备共享。
- 显式使用本地 JDK 17 运行 `./gradlew :app:assembleDebug :app:lintDebug`，最终代码构建与 lint 均通过。APK：`../ESIotKit-DemoApp-Android/app/build/outputs/apk/debug/app-debug.apk`（构建产物不提交）。
- OpenSpec 1.14.1 `validate --all --strict --no-interactive`：5 项通过；`validate --archived --no-interactive`：归档后2项通过。`git diff --check` 通过。
- `docs/README.md` 中五项设计同步检查均通过；本次没有改动 SQL、OpenAPI 或业务数据。

## 已核对行为

- 五个设计状态均在模拟器实际打开并截图；首页卡片、消息留白、AI 渐变和胶囊、两个浮层的尺寸与层级按图核对。
- 系统通知入口实际打开 Android 的本应用通知设置；没有用本地文字模拟授权成功。
- AI 输入框获得焦点后随键盘上移；编辑并提交 `camera offline`、选择建议并提交后均保存到本地历史，明确提示 AI 服务尚未连接；往返首页后历史仍保留。
- 两个浮层展示时底层控件从可访问树中移除，遮罩拦截点击；站点浮层外部点击不会打开底下的设备画面。
- 选择安防赛场更新标题；切换后通知偏好独立，校园驿站的静音不会影响另一站点。
- 30分钟、1小时、直到手动恢复分别写入有限时间戳或无限期值；点击铃铛可以恢复。无限期静音在强制停止并重启应用后保留。
- 到期恢复使用独立测试设备内注入的30秒偏好验证：先观察到“恢复校园驿站的通知”，到期后变为“关闭校园驿站的通知”，偏好时间戳自动清零。这验证到期逻辑，没有声称实际等待30分钟或1小时。
- 从我的进入消息后返回仍停留我的；SDK 调试能进入官方 Business Login 页面，返回驿站界面后保留设计壳页面。
- 320dp 宽、1.3 倍字体下，通知条自适应增高，“启用”与问号随字体适配，文字完整可见。多画面入口明确显示设计示例及未连接状态。

## 能力与验收边界

设备名称、深灰画面和“68”为设计样例；AI 只保存本地输入，不调用或伪造远端事件、录像、推理或证据。通知静音只保存设计壳的本站点偏好，不表示已更改系统推送、SDK 报警订阅或服务端配置。

测试镜像采用 16KB 页大小，官方 SDK 原生库在启动时出现对齐兼容提示；确认后由系统兼容模式运行。本轮保留官方二进制，没有把模拟器界面检查当成摄像头 SDK 实测。尚未进行真机、摄像头直播/报警、Backend/Agent 联调或 D02/D06/D08/D10 业务验收。

关联 [Issue #2](https://github.com/SUertee/partrace-vision/issues/2)、[本轮提案](../../openspec/changes/archive/2026-10-16-d02-station-design/proposal.md)。保留原四 Tab 规划中的事件、证据、核查与记录业务目标；本次只交付用户设计图范围，不关闭业务 Issue。
