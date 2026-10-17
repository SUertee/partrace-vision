# Android 源码与设计资料整理

> 工程目录已于 2026-10-17 后续迁移为单一 `android/` 根和 `:core` 模块；下文旧工程名、旧命令与历史产物路径保留当时实际状态，当前使用见 [当前迁移记录](../../../openspec/changes/d02-android-single-project/verification.md)。

2026-10-17，按用户要求在现有 Android/Common 工程内统一代码与资料结构。此次为源码归类与命名整理，保留现有界面、回调和业务行为；未新增 Gradle 模块或改动应用 ID。

## 整理结果

- 聊天及消息／我的／审核页面归入 `station/ui`：`assistant` 放聊天入口与布局，`screens` 放消息／我的／审核页，`components` 放图标、顶栏、通知条及共享底部导航。
- 根目录的 `StationApp` 继续负责首页和导航，`StationSdkApp` 继续接 SDK。Agent、审核记录与人物录像任务的协议、状态、网络和持久化保留原职责。
- 设计资料由三处汇总到 `android/design`。原始参考图与已有渲染图保持各自分类，App／Agent 历史验证分别保留，所有历史记录及其限制仍按原日期解释。
- 更新 Android 源码索引、根说明、模块说明、开发计划和受影响 OpenSpec 引用。当前整理不增加行为要求，也不改变 SQL／OpenAPI 契约。

源码和修改入口见 [Android 说明](../../README.md#页面源码索引)，资料入口见 [设计索引](../README.md)。

## 本次验证

在 `ESIotKit-DemoApp-Android/` 使用 JDK 17.0.2 执行：

```sh
./gradlew :sample-core:testDebugUnitTest --tests '*TraceAgent*' :app:assembleDebug :app:lintDebug --console=plain
```

- 最终构建成功，用时 1 分 32 秒；9 项现有 Agent 协议／网络测试全部通过（0 失败、0 错误、0 跳过），lint 为 0 Error/Fatal、133 项 Warning。首次编译发现抽取底部导航时遗漏的 `Role` import，补回后运行上述完整检查通过。
- 对比迁移前快照，7 个迁移文件剥离 package／import 后正文逐字相同；底部导航的抽取正文逐字相同，应用壳与 Preview 的其他正文未变。SDK 入口与设备展示状态文件的 SHA-256 保持一致。
- 原 37 个资料文件均保留，其中 34 个图片／Logo 文件迁移前后 SHA-256 完全一致；3 份历史 README 仅重算路径及补充入口，历史数据来源与验收限制保留。
- 受影响 Markdown 的本地链接均存在；旧设计目录路径、旧页面源码路径与旧页面 FQN 无残留。Android Preview 的文件名、package 和反射启动路径保持匹配，未在本轮运行预览。
- `git diff --check`、D02 界面／设备详情及 D07 Android Agent 三个 change 的 OpenSpec 严格校验、文档五项离线同步检查通过。后者校验既有快照同步，不代表重新验证数据库或业务链路。

本轮完成源码与资料整理、离线检查及 APK 构建，未安装 APK 或操作模拟器，未调用模型、摄像头或业务写入接口。未重新检查真机、页面交互及摄像头效果；对应历史记录和后续业务验收继续独立解释。

## 历史凭据

- [App 页面与设置验证](app/README.md)
- [Agent 接入与聊天页验证](agent/README.md)
- [设计稿还原、设备详情与我的](../README.md)
