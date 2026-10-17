# Android Agent 接入验证

日期：2026-10-17（Asia/Shanghai）。D07 / Issue #7，协作 D08；不替代 D10。

## 代码与配置

- 从 `origin/dev` 拉取并快进至 `97067ce`，工作分支 `feat/d07-android-agent`。保留原有 Android、People 管线、Backend 与 Vision 未提交修改；合并文档冲突后 unmerged 为零，autostash 保留作为恢复副本。
- Android 使用已授权的已发布录像、对应摄像头与版本区域，整段窗口以 Long 毫秒传递；提问、可编辑计划确认、运行、停止、恢复、服务端分页历史和当前证据图片使用既有 API。
- 运行采用 2 秒轮询；SSE 路由已随最新 Backend 挂载，本客户端不订阅 SSE。后台、离页停止本地请求，显式 stop 才停止服务端运行。SDK 会话重建时保留 AI 路由。
- 私有配置脚本仅生成 debug API 资产；release 合并资产未包含 `partrace-agent.json`。已扫描 Debug APK，Backend 模型 Key 与数据库 URL 未进入 APK。运行配置和稳定请求信息保存在 noBackupFilesDir。

## 工程与隔离检查

JDK 17.0.2 / Gradle 8.13 / AGP 8.11.1 / Kotlin 2.2.21，compileSdk 36。

```sh
cd android/ESIotKit-DemoApp-Android
./gradlew :sample-core:testDebugUnitTest --tests '*TraceAgent*' :app:assembleDebug :app:lintDebug
./gradlew :sample-core:mergeReleaseAssets
```

- 最终页面恢复修改后构建、lint 和 8 项目标测试全部通过。合同测试 4 项，本地真实 HTTP 测试 4 项，0 失败、0 跳过。
- 覆盖稳定 ID 先落盘、503 结果不明时相同计划重放、用户编辑、Long 范围、已发布过滤、恢复撤权清旧事实、跨来源不转发 Bearer、stop 与 completed 竞态、结果卡片及当前证据字节。
- Backend 管理命令测试 `uv run --no-sync pytest tests/test_management.py -q`：27 项通过，覆盖显式卡片迁移拒绝路径与事务边界。
- OpenSpec 单项严格校验、`git diff --check` 及暂存区空白检查通过。五项设计同步检查通过；schema 快照为 12 表、104 字段，API 为 35 个操作。这些离线检查不验证业务运行。

## 本地服务与设备

- 原 8000 进程仍加载旧模型环境，Android 首次实际提交返回 `model_unavailable`，没有模拟答案回退。
- 只读确认配置中模型已设置、原 `runs` 为空，但缺少最新 dev 的 `cards_json`。显式执行 `python -m backend.manage migrate-agent-cards` 成功，保留现有用户、录像、任务和消息；随后以原私有 `.env` 重启单进程 Backend。
- 重启后 `/health/ready` 200，PostgreSQL / pgvector 均正常；最新 SSE 路由确认已挂载。
- 在现有 `Medium_Phone` / arm64 / API 37 模拟器覆盖安装 Debug APK，保留 SDK 登录与本地任务。差量安装曾导致原有类缺失，改用完整覆盖安装后消失；不修改 SDK 类。
- AI 页实际读取 `rec_mock_001 / cam_demo_01 / demo_v1 / E3 / 120000ms`，明确显示“模拟数据”。真实用户问题已持久保存，真实模型返回可确认计划；页面截图见 [plan.png](../../../android/agent-previews/plan.png)。
- 最后设备测试中模拟器整体断开，重启日志显示 QEMU 线程挂起；保留数据冷启动后 ADB 已恢复连接，API 37 与原应用数据保留。此故障与应用是否通过完整业务验收分别记录。

## 业务边界

- 独立真实 API 验证：模型计划 HTTP 200（10.45 秒，4 步），启动 HTTP 202，2 次轮询后 `failed / agent_execution_failed`，没有答复、引用或卡片。另一次从 Android 确认的运行进入 `failed / agent_timeout`，模拟器冷启动后实际回读并显示该失败状态。
- 当前私有配置有聊天模型，但 Embedding model/dimensions/key 均未设置；语义服务在此情况下明确 503，不产生模拟向量。无模型的真实 Gateway 读取确认：read_recording 成功、search_events 命中 1 个事件、semantic_search_events 返回 503/model_unavailable（provider 调用 0、业务写入 0）。但没有保存实际失败运行的工具轨迹，因此不将其直接当作上述运行的已证明根因。
- 自动审批拒绝了进一步调用外部模型的诊断，理由是可能发送问题与事实元数据、超出其识别的 Android 接入授权；已向用户说明并请求明确授权，没有改走其他入口绕过。
- 当前仅有模拟录像；本轮不宣称真实摄像头录像的 Agent 引用、人工核查保存与重启回读闭环已完成。模型只读取现有事件元数据与描述，不把生成计划中的“扫描画面”措辞当作已具备视频理解能力。完整业务可用性仍取决于后端分析问题排除及真实事件验收。
