# Android Agent 接入验证

> 工程目录已于 2026-10-17 后续迁移为单一 `android/` 根和 `:core` 模块；下文旧工程名、旧命令与历史产物路径保留当时实际状态，当前使用见 [当前工程说明](../../../android/README.md)。

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
- AI 页实际读取 `rec_mock_001 / cam_demo_01 / demo_v1 / E3 / 120000ms`，明确显示“模拟数据”。真实用户问题已持久保存，真实模型返回可确认计划；页面截图见 [plan.png](../../../android/design/verification/agent/plan.png)。
- 最后设备测试中模拟器整体断开，重启日志显示 QEMU 线程挂起；保留数据冷启动后 ADB 已恢复连接，API 37 与原应用数据保留。此故障与应用是否通过完整业务验收分别记录。

## 业务边界

- 独立真实 API 验证：模型计划 HTTP 200（10.45 秒，4 步），启动 HTTP 202，2 次轮询后 `failed / agent_execution_failed`，没有答复、引用或卡片。另一次从 Android 确认的运行进入 `failed / agent_timeout`，模拟器冷启动后实际回读并显示该失败状态。
- 当前私有配置有聊天模型，但 Embedding model/dimensions/key 均未设置；语义服务在此情况下明确 503，不产生模拟向量。无模型的真实 Gateway 读取确认：read_recording 成功、search_events 命中 1 个事件、semantic_search_events 返回 503/model_unavailable（provider 调用 0、业务写入 0）。但没有保存实际失败运行的工具轨迹，因此不将其直接当作上述运行的已证明根因。
- 自动审批拒绝了进一步调用外部模型的诊断，理由是可能发送问题与事实元数据、超出其识别的 Android 接入授权；已向用户说明并请求明确授权，没有改走其他入口绕过。
- 当前仅有模拟录像；本轮不宣称真实摄像头录像的 Agent 引用、人工核查保存与重启回读闭环已完成。模型只读取现有事件元数据与描述，不把生成计划中的“扫描画面”措辞当作已具备视频理解能力。完整业务可用性仍取决于后端分析问题排除及真实事件验收。

## 赛事网关重新接通 · 2026-10-17

用户本轮提供主办方模型使用文档和自己的凭据，明确要求连接该网关。只使用文档的 API 协议作为参考，没有执行文档中的全局工具配置、下载或任意命令。截图仅在本机 OCR，密钥未打印；更新被 Git 忽略、0600 的 `backend/.env`，旧配置备份在私有 `.local-state`。临时 OCR 凭据在验证后删除。

- 现有代码本来就有真实 `ChatOpenAI` API 入口，本次沿用它；基址为 `https://ai-router-cn-pub.anker-in.com/v1`，本地模型改为 `hackathon/v_model/nova-pro`，整轮超时由 60 调整为 180 秒。API 模型来源标识为 `hackathon/v_model/nova-pro@anker-router-nova-pro-20261017`。
- 新凭据的基础聊天测试 HTTP 200，网关实际返回 `bedrock/us.amazon.nova-pro-v1:0`。这是 Anker Router 转接，不是本项目直接配置 AWS IAM／Bedrock Converse；AWS 接入提案仍未实施。
- 真实 Nova＋实际 `create_agent` 图＋明确 mock Gateway：计划成功 3.67 秒；执行成功 11.36 秒，真实请求 `load_skill → search_events → get_event → get_evidence → CandidateAnswer`，得到 1 条引用、2 张卡片。这里的可用帧状态为显式协议 fixture，不是实际录像或图像验证。
- 实际本地 API 使用已有模拟录像首次执行被 `invalid_references` 拦截；独立回读证实该帧 `media_status=missing`，Nova 仍尝试引用它。保留引用账本校验，强化执行提示词：真实 ID 不等于有效引用，全部缺图必须返回 `references=[]` 并说明仅有元数据。这是恢复既定缺图行为的小修正，没有增加接口或权限。
- 确认无进行中 Agent run 后，只重启本项目 `127.0.0.1:8000` 单执行器，其他 LAN 服务保持原样。首次后台启动因解析成系统解释器而退出，改为保留项目虚拟环境路径后恢复；最终 `/health/ready` 200。现有 Android debug 基址 `http://10.0.2.2:8000` 无须改变，未重建或操作 Android App。
- 修正后实际 API：计划 HTTP 200（2.91 秒）、运行启动 HTTP 202，14.16 秒后 `completed`；run 为 `connection_smoke_20261017_6a53d92e_run`。回读为 0 引用、2 张卡片，包含 `media_missing`，摘要明确模拟来源；同 run 同内容重放 HTTP 200，历史只有 1 条助手消息。
- 本轮从 `backend/` 执行 `.venv/bin/python -m pytest ../agent/tests tests/test_model_adapters.py tests/test_agent_runtime.py tests/test_agent_gateway.py -o addopts='' -q`：**87 passed**（1.81 秒）。这验证相关回归，不复用既有 280／338 项结果。

本次已验证赛事模型连接、实际 skill／工具图、本地 API 与 PostgreSQL 助手答复持久化回读；只用明确 mock 数据，没有发送真实摄像头视频、图片或人物资料。Embedding 接口、模型及维数未在所给文档中定义，仍不配置或猜测；真实事件有效帧引用、VLM 看图、Android 实际操作与 D10 仍待分别验证。本地 Key 不进入 Android 资产、源码或 Git，也不视为已部署到 AWS 后端。后续分享截图应遮盖凭据。

## DeepSeek 切换与失败提示修复 · 2026-10-17

用户明确要求改用 DeepSeek，并反馈聊天页反复显示“分析失败／服务暂时不可用”。这是对现有提供方参数及失败呈现的修正，未改变 API、数据库、工具权限或证据策略，不新增 OpenSpec change，也不将 D07／D10 整包标为完成。

- 只读确认 App 连接 `http://10.0.2.2:8000`，恢复的是北京时间 04:25 的 GLM 旧失败。旧记录只有安全公共错误码，没有底层异常及完整工具轨迹，不能反推为缺少 Embedding、Key 或媒体所致。旧失败保持原样。
- `applyRun` 原先将 `failed` 同时复制为通用 `state.error`，导致一个任务显示两张错误卡。现仅任务卡展示真实失败说明和错误码，卡内“重新分析”使用既有新 run_id 语义；真实网络／API 错误仍进入独立错误提示，不自动重跑。
- DeepSeek 基础聊天 HTTP 200，实际返回 `deepseek-v4-pro-202606`；`tool_choice=required` 返回网关 400001，即使关闭思考模式仍拒绝；`auto`＋`thinking.type=disabled` 对照请求 HTTP 200 并返回工具调用。新增两个显式可选兼容配置，默认空值不改变既有提供方行为。保留 ToolStrategy 和其 schema，提示词明确必须调用 DraftPlan／CandidateAnswer 提交，仍拒绝普通文字冒充结构化结果。官方参数参考：[DeepSeek Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion/)、[工具调用](https://api-docs.deepseek.com/guides/tool_calls/)。
- 正式适配后的真实只读复测：计划 6.35 秒、执行 12.66 秒，通过 `load_skill / read_recording / semantic_search_events / search_events / get_event / CandidateAnswer`；确认范围不变，无业务历史写入。语义不可用时按既有协议披露并继续普通查询。
- 私有 `backend/.env` 切换为 `hackathon/v_model/deepseek-v4-pro`、版本 `anker-router-deepseek-v4-pro-20261017`，设置 `PARTRACE_AGENT_THINKING=disabled`、`PARTRACE_AGENT_TOOL_CHOICE=auto`；超时保持 180 秒。原环境已备份在私有 `.local-state`，配置权限 0600，密钥不进入源码或 APK。确认进行中 run 为 0 后仅重启本项目本地 8000，readiness 200。
- JDK 17 下运行 `./gradlew :sample-core:testDebugUnitTest --tests '*TraceAgent*' :app:assembleDebug :app:lintDebug`：**9 项目标测试通过、构建通过、lint 0 Error／133 项既有 Warning**。覆盖失败恢复不自动运行、不重复服务错误、显式重跑仅更换 run_id 且保留计划、真正 503 仍显示请求错误。
- 模拟器 `emulator-5554` 完整覆盖安装 APK，保留登录、配置与历史；视觉确认单一失败卡后点击“重新分析”。新 run `run_25705db521e144508247909d6dfba638` 与旧失败使用相同 input／plan，于北京时间 07:10:34—07:10:50 完成，模型标识为 `hackathon/v_model/deepseek-v4-pro@anker-router-deepseek-v4-pro-20261017`。API 回读为 **completed、0 引用、2 张卡片（含 media_missing）**；同 ID 同载荷重放 HTTP 200，仅 1 条对应助手消息，App 错误码为空。截图见 [失败卡](../../../android/design/verification/agent/failure-retry.png)和 [实际答复](../../../android/design/verification/agent/deepseek-completed.png)。
- 从 `backend/` 执行 `.venv/bin/python -m pytest ../agent/tests tests/test_model_adapters.py tests/test_agent_runtime.py tests/test_agent_gateway.py tests/test_runtime.py -o addopts='' -q`，本轮 **125 项通过**；包含真实 ChatOpenAI＋MockTransport 请求体验证、默认行为与非法配置拒绝，不将离线测试视为外部模型凭据。

本轮外部模型与 App 验证仅使用既有 `rec_mock_001 / E3` 模拟事实；缺失图片、未校准 UTC、Embedding 未配置均如实说明，不确认实际人员到访，也未发送真实摄像头图片或视频。未改变公网／Aurora／LAN 服务模型配置，Android 真机、有效真实帧引用、人工核查保存及 D10 仍未验收。

<a id="正式聊天移除模拟录像2026-10-17"></a>

## 正式聊天移除模拟录像 · 2026-10-17

按用户截图中的「模拟数据 · E3 区域 · 0:00–2:00」追加需求，正式聊天仅从已发布录像中提供 `source_mode=sdk/prerecorded`。mock 录像不进入选择器、不作自动默认；无真实录像时显示空态。明确属于 mock 的本地活动会话／计划／运行恢复指针退出当前对话；仅旧选中录像为 mock、实际 pending 属于另一真实录像时，真实稳定 ID 与原计划保持不变。服务端模拟录像与历史没有删除，Backend 契约、测试素材、模型和凭据未改。

`preparePlan`／`startRun`、确认与重试核验原始真实录像、摄像头、偏移及其配置区域，旧模拟计划不能改绑到新录像；404 恢复回退也必须经过运行入口校验。非真实／不可用历史仍可只读或显式停止，但不提供重新分析、不自动轮询，running 历史不锁新问题；列表首次失败时重试先重新读取元数据，再恢复原 ID。

最终在 `android/` 使用 JDK 17.0.2.8.1-amzn 执行：

```sh
./gradlew :core:testDebugUnitTest --tests '*TraceAgent*' --tests '*StationEventsTest' --tests '*SdkDeviceOrder*' \
  :app:assembleDebug :app:lintDebug --console=plain
```

结果 `BUILD SUCCESSFUL in 1m 20s`。32 项相关测试通过（Agent 协议 4、网络 10、摄像头事件 15、SDK 排序 3），失败／错误／跳过均为 0；lint 133 项 Warning、0 Error/Fatal。最终日志为本机 `/private/tmp/partrace-agent-real-sources-final-20261017.log`，APK 位于 `android/app/build/outputs/apk/debug/app-debug.apk`。

本轮扩展原真实重试测试，加入 mock 排首验证；新增 5 项本机 HTTP 网络回归：mock-only 旧恢复清理且无创建请求、模拟历史仅 GET 且重跑不 POST、旧模拟选择不清真实运行、列表 503 后重取并按原 ID 恢复、running 模拟历史在超过 2 秒后仍不覆盖新真实计划。服务均为临时本机测试端点和虚构数据，不调用生产模型、不删除业务数据。

用 Android Studio Rabbit 1 / 2026.2.1 渲染现有 `TraceAssistantIdlePreview`，核对 [空态预览](../../../android/design/rendered/assistant-real-sources.png)：未选择录像时显示「选择真实录像后开始提问」，不再显示固定 E3 和两分钟模拟范围。预览为纯布局、412×917dp／1082×2407px，未连接录像 API 或模型。此前一次使用错误预览名称未产图，随后使用实际函数名成功；这里只保存成功产物。

Android 说明、M06、当前提案／规格／任务与设计资料已同步。OpenSpec strict、`git diff --check`、受影响 Markdown 相对链接及现有五项离线设计同步检查通过；DB／API 契约未变。

**本轮未覆盖安装最终 APK，也未操作真实摄像头、现有会话或真机。** 已安装旧版本仍可能显示此前的模拟默认范围，需更新安装后才应用本次策略。真实 SDK 录像是否已解析／发布及具备有效区域，继续由实际后端结果决定；不以来源过滤证明 D08／D10 完成。
