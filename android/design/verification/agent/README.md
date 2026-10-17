# Agent 接入预览

> 工程目录已于 2026-10-17 后续迁移为单一 `android/` 根和 `:core` 模块；下文旧工程名、旧命令与历史产物路径保留当时实际状态，当前使用见 [当前工程说明](../../../README.md)。

`plan.png` 为 2026-10-17 模拟器实际页面，显示后端真实模型生成的待确认计划。所选录像 `rec_mock_001` 是明确标记的模拟数据，不代表摄像头业务验收；计划中的表述也不证明 Agent 实际看过视频。

构建、实际验证与边界见 [验证记录](../../../../openspec/changes/d07-android-agent/verification.md)。

2026-10-17 失败提示修复与 DeepSeek 实测：

- [failure-retry.png](failure-retry.png)：保留旧任务失败及原错误码，仅一张任务卡，卡内提供“重新分析”，不再重复显示服务故障。
- [deepseek-completed.png](deepseek-completed.png)：从同一旧失败卡重新执行，DeepSeek 返回已保存答复；素材仍为模拟数据、关键帧缺失，答复不据此确认实际人员到访。

## 现有 Android 聊天页就地改造（2026-10-17）

按用户确认，正式页面继续在现有 Android/Common 工程中改造，不新增 `frontend` 模块。页面入口和源码位置见 [Android 说明](../../../README.md#正式页面直接在现有工程改造)，行为调整见 [D02 规格](../../../../openspec/changes/d02-ui-design-match/specs/station-design/spec.md)。

本次整理欢迎问题、输入提示和条件入口，展示新问题使用的录像来源、区域与时段；失败状态保留重新分析，错误码和录像 ID 收进可展开详情。选择示例仅编辑本地草稿，发送与计划确认继续由用户操作。

验证环境为 Medium_Phone 模拟器（Android 17 / API 37，1080 × 2400，420 dpi），调试应用版本 2.2.7（44），原 SDK 登录与应用数据保留。

[assistant-inplace.png](assistant-inplace.png) 为最终调试版的模拟器欢迎页，使用明确标记的模拟录像；截图不包含真实摄像头画面。

实际检查了范围栏、欢迎文案和输入提示；点击范围栏可打开录像选择器，保持模拟录像不变。底部“示例问题”可打开选择框并填入草稿。最终版本中，欢迎页建议也已将问题带入草稿，未点击发送；随后出现本轮操作之外的输入及页面跳转，因此停止继续操作，未完成第二条建议替换草稿的独立复验。

本次命令与结果：

- 在 `ESIotKit-DemoApp-Android/` 使用 Java 17 执行 `./gradlew :sample-core:testDebugUnitTest --tests '*TraceAgent*' :app:assembleDebug :app:lintDebug`：构建成功，9 项 Agent 测试全部通过，lint 0 错误、133 项警告。
- `openspec validate d02-ui-design-match --strict` 通过；`git diff --check`、相关文档相对链接和设计文档五项同步检查通过。

模拟器安装期间曾出现访客系统及截图无响应；保留 userdata 重启并改用 SwiftShader 后，最终 APK 安装成功，页面可读取。启动时系统界面曾报无响应，选择等待后恢复，未清除应用数据。

未验证真机、横屏、完整键盘避让与摄像头采集；本次界面检查未发起新的模型调用，未将真实录像发送给模型。失败详情的展开／收起未在本次模拟器回归中复验，旧失败与 DeepSeek 成功记录保持原样。
