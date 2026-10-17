# Agent 对话与事件过程联动 · 2026-10-17

## 实现范围

- P1：用户蓝色气泡、真实工具进度、助手正文、淡紫色相关片段。进度仅消费服务端实际调用记录，计划、模型思考与工具过程分开。停止后尽力 GET 同一运行回读真实进度；回读失败不会把已提交的停止变成重新执行。
- P2：聊天与 P3 摄像头事件列表共用「事件过程」详情。只传标识，重新鉴权核对录像、事件和证据，展示真实时间线与关键帧；已验证的 SDK 原片由用户显式进入原播放器。超过时间线上限时仍保留单独验证的目标事件；选中项刷新后失效时显示不可用，不替换为其他事件。
- SDK 使用设备 SN 与原生事件 ID 的稳定 People job 对应，核对服务、SDK 原事件、录像、镜头和配置。未导入、排队、处理中、失败、未发布、缺区域和发布后无区域事件分别表达。SDK 检测不直接当成团队算法结论。
- GET Agent run 增加可选 progress；六种工具、五种状态、独立调用 ID、开始序号和 UTC 时间。进程内上限 128 次运行、每运行 64 次调用，终态拒绝迟到更新；旧记录、淘汰或重启不补造进度。不保存参数、结果、模型思考或凭据；先执行原有授权再附进度。
- 不新增 HTTP 路由、SQL 字段或数据库迁移，不改原 SSE 快照／终态含义。没有自动上传、开始分析或新模型调用。

## Android 构建与测试

环境为 macOS arm64、JDK 17.0.2.8.1-amzn、Gradle 8.13、AGP 8.11.1、Kotlin 2.2.21、ESIotKit 2.2.7。私有 AAR、凭据和生成配置沿用本地文件，不进入本记录。

在 `android/` 运行：

```sh
JAVA_HOME=/Users/suerte/.sdkman/candidates/java/17.0.2.8.1-amzn ./gradlew \
  :core:testDebugUnitTest --tests '*TraceAgent*' --tests '*StationEvent*' --tests '*SdkDeviceOrder*' \
  :app:assembleDebug :app:lintDebug --console=plain
```

结果 `BUILD SUCCESSFUL in 1m 46s`，本机日志 `/private/tmp/partrace-agent-event-detail-final-20261017.log`。

| 测试组 | 通过 |
| --- | ---: |
| StationEventDetailsTest | 17 |
| StationEventsTest | 16 |
| TraceAgentNetworkTest | 11 |
| TraceAgentContractTest | 5 |
| SdkDeviceOrderTest | 3 |
| 合计 | 52 |

失败、错误、跳过均为 0。详情使用本机受控 HTTP 服务覆盖授权、录像／证据归属、SDK 映射与状态、当前服务变化、无区域、32 区域／200 事件上限、反查精确 SDK 标识、媒体 403 和取消；没有访问真实摄像头或生产后端。新增两设备原生事件 ID 相同仍保留的测试，以及真实 progress 校验和停止回读网络回归。

首轮发现旧测试的“停止与完成竞态”因无条件 GET 而失败；已将回读限定为 stopped，并使遥测回读失败不覆盖已提交停止。最终上述 52 项全部通过，不将首轮失败计作通过。

之后仅调整详情失效选择的展示策略，再执行 `:app:assembleDebug :app:lintDebug --console=plain`，`BUILD SUCCESSFUL in 1m 33s`，日志 `/private/tmp/partrace-agent-event-detail-ui-final-20261017.log`。最终 lint 为 133 项 Warning、0 Error/Fatal。APK 生成于 `android/app/build/outputs/apk/debug/app-debug.apk`，本轮未安装。

## 后端与 Agent

从仓库根执行：

```sh
backend/.venv/bin/python -m pytest -m 'not integration'
```

结果 453 passed、134 deselected，2.94 秒。覆盖真实工具包装的开始／返回／异常／降级／取消、独立并发与重复调用、observer 故障隔离、终态迟到、容量淘汰及先授权后读取。执行图使用显式 fake 模型和受控 gateway／history，不连接 PostgreSQL、外部模型或运行中的服务；不把离线测试当成实际模型效果或持久化验收。

## 原生布局凭据

在已运行 Android Studio Rabbit 1 / 2026.2.1 的 ParTraceVision 项目中使用 CLI：

```sh
android studio render-compose-preview \
  android/core/src/androidMain/kotlin/com/anker/esiotkit/sample/main/station/StationDesignPreviews.android.kt \
  TraceAssistantConversationPreview --project ParTraceVision --pid 67753 \
  --output-image-file /private/tmp/partrace-conversation-process-studio.png
```

同样调用 `StationEventProcessPreview` 生成详情。两张均成功并逐张目检，保存为 [对话预览](../../../android/design/rendered/assistant-conversation.png) 与 [事件过程预览](../../../android/design/rendered/event-process.png)。对话为 412×1100dp／1082×2888px，详情为 412×917dp／1082×2407px。加高的对话预览展示完整布局，正式页面按设备高度滚动。

预览明确标注「设计预览·模拟数据」，仅传虚构候选事件、同一摄像头、缺图与灰色媒体占位，不初始化 SDK、People 或 Agent。只保存用户 P1／P2 设计参考；未复制 P3 中真实摄像头画面。预览不能证明真实工具进度、SDK 回放、导航往返、系统栏、键盘或真机操作。

## 契约与文档

更新 Android、Agent、Backend 源码索引、模块设计、开发计划、OpenAPI 和受影响 SDK 事件 OpenSpec。公开接口仍为 36 项操作，OpenAPI 92 个 schema；progress 为兼容可选字段。按现有脚本重新生成 API／数据库 HTML 和离线工作台，数据库业务结构未因本 change 改动。

最终以下检查全部通过：

```sh
git diff --check
openspec validate d08-agent-event-detail --strict --no-interactive
openspec validate d02-sdk-events-tab --strict --no-interactive
python3 scripts/export-schema-catalog.py --check
python3 scripts/build-schema-canvas.py --check
python3 scripts/sync-workbench-schema.py --check
python3 scripts/build-api-canvas.py --check
python3 scripts/sync-workbench-api.py --check
```

另检查本轮受影响 Markdown 的本地路径和锚点，全部通过。change 保留用于评审，不归档，不关闭 D08 或 D10 Issue。

## 尚未实测的范围

本轮未安装 APK、重启正在运行的后端、读取真实事件执行新模型运行或操作当前模拟器摄像头／People 管道。因此运行中的旧服务不会自动提供新增 progress，旧安装版本也不会自动显示新页面。设备验证还需最终 APK 与加载新代码的后端：

1. 聊天执行真实工具 → 相关片段 → 正确事件和关键帧 → 返回原对话。
2. P3 筛选与滚动 → 事件过程 → 显式播放 SDK 原片 → 返回并恢复来源。
3. 入库前、处理中、无区域、发布后、缺图、权限失效、退出账号和切后台等实际状态。
4. Android 真机、不同尺寸、放大字体、系统栏与键盘。

当前只支持有证据来源的镜头；没有跨镜轨迹或已验证遮挡时间段，不填入设计稿示例。后端原 MP4 尚无通用流式播放器／下载按钮，详情展示关键帧并按可验证 SDK 来源开放原回放。后端会规范化旧镜头／配置别名，但公开接口没有配置别名等价表；与当前 People 映射不严格一致时明确暂不能关联，不按名称或时间猜测。上述边界不等于 D06／D08／D10 完成验收。

## 原片回看追加验证 · 2026-10-17

用户本轮明确要求从“相关事件片段”进入后能够回看原片，已追加后台原片入口，更新上文该项首轮边界。来源为已入库、媒体可用的真实 `sdk/prerecorded` 录像；详情会话绑定验证后的录像，固定通过现有媒体端点与当前 Agent 读身份请求，服务和凭据前后核对，不使用 SDK 上传身份或构造伪 SDK 事件。

原片流式落入应用私有缓存，最大 64 MiB，不跟随重定向；仅接受 200、非空 `video/mp4`。系统 VideoView 只读取本地文件，准备完成后定位至当前事件起始偏移，再开始播放，保留整段上下文。按钮可切回关键帧；切换事件、离前台、销毁、下载失败／取消／配置变化均释放缓存，解码失败显示错误。不会把下载成功记作播放成功。

实际使用 JDK 17.0.2、现有依赖和离线 Gradle：

```sh
JAVA_HOME=/Users/suerte/.sdkman/candidates/java/17.0.2.8.1-amzn ./gradlew :app:assembleDebug :core:testDebugUnitTest \
  --tests 'com.anker.esiotkit.sample.main.station.events.StationRecordingDownloadTest' \
  --tests 'com.anker.esiotkit.sample.main.station.events.StationEventDetailsTest' \
  :app:lintDebug --offline --console=plain
openspec validate d08-agent-event-detail --strict --no-interactive
```

- 最终构建与 lint 成功，0 Error、133 项既有 Warning。
- `StationEventDetailsTest` 20 项、`StationRecordingDownloadTest` 5 项，合计 25 项通过，0 失败／错误。覆盖同身份与固定录像、会话越界、凭据变化和缓存释放、授权 GET、错误 MIME／空响应、已知与分块超限、302 拒绝和取消清理。
- 媒体网络测试使用明确的受控 MP4 `ftyp` 传输字节，不宣称已完成真实视频解码或设备播放；准备／定位／音画与前后台行为须在最终 APK 上实际检查。
- APK：`android/app/build/outputs/apk/debug/app-debug.apk`，284335175 bytes，SHA256 `011ef3ae08e1fe9a10ad4f91af1d48e6d3cd748370693ca1276a0ec18675616e`。

该实现未修改 Backend／Agent、数据库、权限或媒体事实。装机及实际原片回放由主执行流程继续验证，本段不将旧轮或后续设备操作计为此次子任务已执行结果。

## SDK 登出导致详情退出的修复 · 2026-10-17

主执行流程真机观察到：相关片段曾短暂显示事件详情，随后返回 AI；更新原片回看 APK 后也发生返回首页，未发现 AndroidRuntime 崩溃。源码中存在 SDK Logout 的 `popUntilRoot()`、`key(authenticated)` 与随身份变化的 Navigator key，均会移除独立后台详情；SDK 事件流同时 `replay=1`，重建订阅会再次接收最后一次 Logout。Activity 的页面回调只切换 Agent 活跃状态和系统栏，没有导航复位操作。

本次固定根 Navigator，在 SDK 失效时关闭 SDK 栈、释放 Home 的 SDK ScreenModel、清理 SDK 详情缓存和下载任务；仅保留 Home 与当前栈顶后台目标。首次失效用原事件／录像／证据 ID 新建详情，从正常仓库入口重新检查后台读身份；重复 Logout 不重建该详情或打断其播放器。后台身份失败仍走原错误分支，不改变权限、服务或媒体事实，也不恢复藏在 SDK 页面下的旧后台目标。

新增 `StationSdkNavigationTest` 验证 SDK 栈清理、精确当前目标保留、首次重验与 ID 保持、重复 Logout 幂等、Home／空栈及旧后台目标不复活。实际执行：

```sh
JAVA_HOME=/Users/suerte/.sdkman/candidates/java/17.0.2.8.1-amzn ./gradlew :app:assembleDebug :core:testDebugUnitTest \
  --tests 'com.anker.esiotkit.sample.main.station.StationSdkNavigationTest' \
  --tests 'com.anker.esiotkit.sample.main.station.events.StationEventDetailsTest' \
  --tests 'com.anker.esiotkit.sample.main.station.events.StationRecordingDownloadTest' \
  :app:lintDebug --offline --console=plain
openspec validate d08-agent-event-detail --strict --no-interactive
git diff --check
```

48 秒构建成功，导航 7 项、详情 20 项、媒体 5 项共 32 项通过，0 失败／错误；lint 0 Error、133 既有 Warning。严格规格与空白检查通过。修复 APK 284335272 bytes，SHA256 `7cc87ec89ab841a6ef8f0196565cfbeb61922b7f8088f5f45080f9a2a2997bf5`。本轮不操作设备，真机新 APK 上的详情保持、播放定位和返回来源继续由主执行流程留证。

## 相关片段入口的聊天 busy 解耦 · 同日追加

用户反馈点击相关事件无法进入。原事件卡及旧引用入口均把导航 enabled 绑定到聊天控制器的 busy；本轮改为仅要求录像与事件标识非空，使已返回结果可独立打开详情。详情仓库仍执行原身份／范围／证据校验，没有新增 API、DB 字段或绕过访问校验，属于恢复既有详情可达行为。

本轮 JDK 17.0.20.1 执行 `:app:assembleDebug :app:lintDebug :core:testDebugUnitTest --tests "*StationSdkNavigationTest*" --tests "*StationEventDetailsTest*"` 成功，导航7项／详情20项通过，lint无Error、133项Warning。临时导航诊断版已在 Redmi K70（Android16/API36）打开原会话的第一条事件，布局确认“事件过程”、两张关键帧及两条时间线，导航记录为同一 Navigator 从Home栈1项push至后台详情2项。随后手机又切换到其他提问并重启进程，连续切帧和保持检查暂未完成，不能将该变化归因于自动弹回。

已移除临时println诊断；最终 `:app:assembleDebug :app:lintDebug` 54秒成功。五项设计同步检查及本change严格校验通过；没有改生成物。最终装机与连续真机结果待执行后追加。


## Android 提交前状态 · 2026-10-17

相关片段入口改由独立 `StationEventDetailActivity` 承载，只传事件、录像和可选证据 ID；详情重新校验归属，返回关闭该 Activity。27 项导航及详情相关测试、Debug 构建和 lint 已通过。用户要求暂停手机验证并先提交，因此本次独立 Activity 的连续真机点击／返回验证尚未完成，不视为 D08 端到端验收。

提交工作区基于最新 `dev`：使用 JBR 21 执行 `./gradlew :app:assembleDebug :app:lintDebug :core:testDebugUnitTest --offline --console=plain` 成功，354 项测试，0 失败、0 错误、7 跳过。初次 JDK 17 全量测试有 4 个 Android 36 Robolectric 类因要求 Java 21 无法初始化；换 JBR 21 后通过，未更改测试或业务逻辑规避。五项设计同步检查、相对链接、凭据模式扫描及 `git diff --check` 通过。
