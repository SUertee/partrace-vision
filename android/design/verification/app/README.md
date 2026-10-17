# Android 设计还原与验证

> 工程目录已于 2026-10-17 后续迁移为单一 `android/` 根和 `:core` 模块；下文旧工程名、旧命令与历史产物路径保留当时实际状态，当前使用见 [当前工程说明](../../../README.md)。

## “我的”追加设计（2026-10-17）

用户要求将“我的”按新增第一张参考图呈现：蓝白渐变的“驿站追迹”品牌头、右上齿轮、常驻“检索记录”搜索框、“全部”和已保存审核卡片，下方保留首页／AI／我的胶囊导航。此前首屏消息／审核／人物录像／SDK 大按钮与设备连接说明收进设置入口。

审核列表与详情复用既有 `StationReviewSource`，只读请求 `/api/v1/review-records` 和 `/api/v1/review-records/{id}`，沿用独立后端授权。搜索在已读取记录中匹配事件编号、审核编号、备注与本机时间；清空恢复全部记录。卡片“已保存”只说明记录已持久化，真实核查结论继续显示在详情，不把“待确认”误写成未保存。正式页面不加入参考图中的 `E3-01.02` 等样例；设计样例仅供预览。

设置齿轮收纳 SDK 调试，并保留消息中心、人物录像自动解析、审核记录与服务设置入口。SDK 调试仍进入官方 `SampleApp`；People 配置、通知与服务凭据继续使用原有页面。加载、服务未配置、读取失败、空列表及搜索无匹配分别显示状态，设置和底部导航保持可达。系统栏与键盘遵循真实 inset，列表为底部导航保留滚动空间。

| 本次状态 | 运行截图 |
| --- | --- |
| “我的”设计预览，使用明确的 `E3-01.02` 样例 | [profile.png](../../rendered/profile.png) |
| 正式页面读取既有后端模拟审核记录 | [profile-device.png](../../rendered/profile-device.png) |
| 右上齿轮设置面板 | [profile-settings.png](../../rendered/profile-settings.png) |
| 记录搜索与无匹配反馈 | [profile-search.png](../../rendered/profile-search.png) |

参考为 [本次第一张图](../../references/profile.png)；[修改前页面](../../references/profile-before.png)保留用于对照。四张运行截图均已逐张目检。以下是本次实际执行结果，不沿用下方 2026-10-16 的历史验证：

- JDK 17.0.2 执行 `./gradlew :app:assembleDebug :app:lintDebug --console=plain` 成功，用时 1 分 20 秒；lint 为既有 133 项 Warning、0 Error。本次未运行单元测试。
- 使用 `android run --device=emulator-5554 --apks=app/build/outputs/apk/debug/app-debug.apk --use-delta-install=false` 安装新版 APK，保留应用数据。模拟器为 Android 17 / API 37、1080×2400px、420dpi。
- 正式“我的”读取已有后端 `evt_mock_001`／`rev_mock_001`，详情明确标注“模拟核查记录，无真实画面”。该记录来自后端，不是正式页面注入的设计数据；本次只读操作没有新增或修改审核记录。
- 搜索 `no_match_profile` 显示无匹配反馈，点击清除后恢复列表；打开审核详情并关闭后仍在我的。
- 搜索获得焦点后打开设置齿轮，确认输入法已收起、底层语义隐藏，六项设置入口与关闭按钮完整可见。进入 SDK 调试到官方 Devices 列表，再选择返回驿站，仍停留“我的”、底部选择与搜索内容保留。新增 `StationProfileDesignPreview` 已在模拟器成功运行。
- OpenSpec 1.14.1 的 `validate d02-ui-design-match --strict --no-interactive`、`git diff --check` 及 `docs/README.md` 中五项设计同步检查通过。本轮没有改动 API／DB 契约。

本次未重新触发服务未配置、请求失败与空列表状态，这些状态沿用共用审核逻辑；未完成完整键盘展开时的避让实测，搜索截图仅展示输入后的界面与输入法折叠按钮，不作为完整键盘避让凭据。未进行真机、多分辨率、摄像头功能或 D08／D10 完整业务验收。任务见 [d02-ui-design-match](../../../../openspec/changes/d02-ui-design-match/tasks.md)。

## 初版设计历史（2026-10-16）

2026-10-16，按用户提供的五张设计图还原。设计基准约为 412×917dp；运行截图为 1080×2400px / 420dpi。真实 Android 状态栏、手势导航区和输入法占用空间由系统 inset 处理，因此底部位置随设备可用高度变化。

| 设计状态 | 运行截图 |
| --- | --- |
| 设备首页 | [home.png](home.png) |
| 消息中心 | [messages.png](messages.png) |
| P.T.Vision / AI 追溯 | [ai.png](ai.png) |
| 通知静音弹层 | [notifications.png](notifications.png) |
| 站点选择浮层 | [sites.png](sites.png) |
| 320dp 宽、1.3 倍字体检查 | [messages-compact.png](messages-compact.png) |

入口代码位于 [station/](../../../core/src/commonMain/kotlin/com/anker/esiotkit/sample/main/station/)，平台启动与系统通知设置在 [ESlotKitMainActivity.kt](../../../app/src/main/java/com/anker/esiotkit/sample/main/ESlotKitMainActivity.kt)。默认启动 `StationApp`，原官方 `SampleApp` 由「我的 → SDK 调试」进入，并可返回驿站界面。

## 本次验证环境与命令

- JDK 17.0.2、Gradle 8.13、AGP 8.11.1、Kotlin 2.2.21；compile/target SDK 36，minSdk 26，未升级依赖或更改包名。
- Android Emulator 37.2.12，Android 17 / API 37、arm64、1080×2400、420dpi；单独临时 AVD 验证，数据不与已有设备共享。
- 显式使用本地 JDK 17 运行 `./gradlew :app:assembleDebug :app:lintDebug`，最终代码构建与 lint 均通过。APK：`../../../ESIotKit-DemoApp-Android/app/build/outputs/apk/debug/app-debug.apk`（构建产物不提交）。
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

关联 [Issue #2](https://github.com/SUertee/partrace-vision/issues/2)、[本轮提案](../../../../openspec/changes/archive/2026-10-16-d02-station-design/proposal.md)。保留原四 Tab 规划中的事件、证据、核查与记录业务目标；本次只交付用户设计图范围，不关闭业务 Issue。
