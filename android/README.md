# Android 开发底座

本目录基于 Anker 安防赛题官方 ESIotKit 2.2.7 示例工程，保留 Android 应用和 Common 公共模块。当前默认由 `StationSdkApp` 接入 SDK 会话，并通过 `StationApp` 呈现本轮用户设计的驿站界面；原官方 `SampleApp` 保留在「我的 → SDK 调试」入口。来源与授权说明见 [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)。

## 项目模块职责

主责：李果钦。技术采用 Kotlin + Compose，对应 D02、D08，并配合 D09、D10。首轮完成四 Tab 骨架、查询与候选列表、路线／时间线／关键帧联动、AI 计划与运行状态、人工核查保存和记录回读。先接共享样例，再连接后端与 Agent 的 `/api/v1` 接口；Android 不直接访问数据库。

摄像头 SDK 能力验证由 Harry Xu 独立负责。D10 闭环验收后，Android 再实现 P1 包裹二维码生成、扫码查询与入出库确认页面；扫码本身只查询，管理员确认后提交流水。本轮已实现驿站设计壳、SDK 设备与 Agent API 接入；同一真实事件的完整业务链路仍待验收。

分工、依赖和验收见 [开发计划](../docs/DEVELOPMENT_PLAN.md)，模块连接见 [工作台](https://yizhan-trace-workbench.vercel.app/#modules)。下面说明本地工程准备与官方底座能力。

业务功能及 DB／API 关联统一记录在 [模块设计](../docs/modules/README.md)，App 骨架见 [M01](../docs/modules/README.md#m01)，查询／AI／人工核查与 P1 扫码按对应模块接入。

```text
android/
├── ESIotKit-DemoApp-Android/  # 独立 Gradle 工程
├── ESIotKit-DemoApp-Common/   # sample-core：业务、Compose UI、Android 实现
└── config-templates/         # 不含凭据的本地配置模板
```

Android 工程通过 `../ESIotKit-DemoApp-Common/sample-core` 引用公共模块，两目录应保持同级。`esiotkit.demo.platform=android` 已保留；本底座未收录 iOS 工程、iOS 源集、CocoaPods、构建缓存、SDK 二进制、签名文件或采集数据。

## 本轮 Android 设计还原

最新一轮按用户提供的六张设计图还原首页、AI 默认页、消息空态／通知提示／月份列表与审核记录；参考图、原生预览和本轮验证见 [ui-design](ui-design/README.md)。通知静音弹层和站点选择浮层保留。默认使用首页、AI、我的三个入口；消息中心由首页或我的进入。本轮三入口覆盖既有四 Tab 的视觉草案，原计划中的事件查询、证据、核查与记录业务范围及验收目标保留。上一轮视觉范围与验证记录保留在 [d02-station-design](../openspec/changes/archive/2026-10-16-d02-station-design/proposal.md)、[设计预览说明](design-previews/README.md)；本轮真实设备接入见 [d02-station-sdk-devices](../openspec/changes/archive/2026-10-16-d02-station-sdk-devices/proposal.md)。

- **首页与 SDK：** 保留蓝白渐变、设备卡与胶囊导航，直接读取当前账号真实 SDK 设备列表；当前两个设备显示为真实卡片，数量不写死。名称、在线状态和最近缩略图来自对应设备，原“深圳”“安克黑客松”“68”样例撤下。共用原 Koin／SDK 初始化及有效缓存业务会话；加载、空列表、错误、未登录／401 和重试分别反馈，不用样例兜底。摄像头卡片复用原单路直播，基站及更多／状态入口进入对应原设备设置；多画面先展示真实最近缩略图网格，可点进单路直播，不是两路同时直播。
- **站点与浮层：** 支持校园驿站／安防赛场本地标题切换，当前仅为界面分组，不对应 SDK 房屋映射，也不按该标题过滤真实设备。
- **通知：** 铃铛提供30分钟、1小时、直到重新打开三个本地静音选项，偏好按站点持久化；有限时长到期恢复，再次点击铃铛可手动恢复。该偏好仅控制设计壳的本地状态。消息中心的启用和设置按钮打开真实系统通知设置，不将本地静音描述为系统通知或摄像头报警配置成功。
- **AI 与原工程：** 建议问题和文本输入连接 Backend Agent，按已授权的已发布录像及区域生成计划，确认后运行，支持停止、恢复、服务端分页历史与证据引用。AI 默认页按稿保留欢迎语和建议问题，四个条件按钮可选择录像、查看时间及编辑事件／区域；右上历史菜单可打开服务设置或新建会话。我的区分真实 SDK 设备入口、后端 AI 服务与未连接的报警能力，并保留「SDK 调试」进入官方 `SampleApp` 的路径。
- **消息与审核：** 系统通知关闭时显示黄色提示，返回应用后重新检查权限。消息页目前没有真实告警源，因此显示空态；设计中的月份卡片只在预览出现。首页菜单和我的均可打开审核记录，使用独立后端凭据只读查询 `/review-records` 与详情，支持本地搜索、错误重试及空列表反馈，不调用模型。人物录像任务状态与配置移至首页菜单和我的入口。
- **边界：** 父层处理真实系统栏及键盘 inset，弹层阻止底层触摸；未复制设计图中的时间、电量或消息页左侧灰色残留。最近缩略图不代表实时画面或连续轨迹；本轮构建／UI、真实设备服务与摄像头播放验证分别留证，不代表 D02 真机、D06 全部能力或 D08／D10 业务闭环验收完成。

## SDK 设备详情

首页点击摄像头进入按新稿重排的 `MegaLiveVideoScreen`：真实设备标题、原播放器、五项快捷控制、Events / Playback / All Recordings 和录像管理卡片。全部原直播 SDK action 保留；静音、灯光、跟踪、校准、360°、全景、快捷回复、预设位、双镜头、夜视、手动报警等按设备能力出现在“更多设备控制”。设置仍进入原 SDK 设备设置。参考与实际验证见 [设备详情说明](ui-design/README.md#sdk-设备详情追加还原)。

Events、Playback 和 All Recordings 使用当前设备的原 SDK 事件录像列表；选择片段后使用原回放页，保留日期／类型筛选、下载、删除与队列。它们不代表 SDK 新增连续录像或云订阅；设计稿右侧区域用于真实下载队列与设备设置。基站仍进入原设置页。

## Agent API 接入

AI 追溯使用 [Agent 对接合同](../docs/api/AGENT_API.md) 中的既有 API。先读取授权的已发布录像及其版本区域；当前分析窗口为所选整段录像，未知 UTC 时间不做推断。提交问题后先展示服务端计划，点击“确认并开始分析”才创建运行。活动页面每两秒回读运行；切后台或离页停止轮询，返回后按持久 run_id 恢复。用户“停止分析”调用真实 stop API，并以返回的服务端终态为准。

开发配置可在仓库根执行：

```sh
python3 scripts/setup-android-agent.py
```

默认从私有 `.local-state/bootstrap-credentials.json` 取 viewer 的后端 Bearer，只生成被 Git 忽略的 `sample-core/src/androidDebug/assets/partrace-agent.json`，开发模拟器地址为 `http://10.0.2.2:8000`。使用其他后端时传 `--base-url https://your-api-host`；显式覆盖已有配置用 `--force`。该资产只进入 debug 构建，不进入 release；资产只含 API 地址和独立 Bearer，不复制 Backend `.env`、数据库密码或模型 Key。此开发 APK 包含该 API 凭据，只在自己的开发设备安装；共享 APK 前移除该私有资产并使用页面设置。

App 内“服务设置”允许修改地址与 Bearer，运行时保存在 `noBackupFilesDir`；同地址留空保留凭据，换服务必须填写新凭据。服务地址不含 `/api/v1`；只接受 HTTPS 或开发回环 HTTP，禁用重定向。真机可通过 `adb reverse tcp:8000 tcp:8000` 后配置 `http://127.0.0.1:8000`。摄像头 SDK Token 与后端 Bearer 分别管理。

会话与消息通过服务端分页回读；失败／停止不生成助手消息，本地最后 run_id 用于恢复。创建会话、消息和运行前保存稳定 ID，网络结果不明时使用同载荷重试。结果按四种卡片展示，点击引用重新鉴权读取当前元数据与图片；缺失媒体保留 ID 并明确反馈。无录像、无匹配、未索引、权限错误和依赖失败均不使用模拟答案补位；录像来源在选择器显示。

构建、客户端协议测试与实际服务/模型验证见 [本轮验证记录](../openspec/changes/d07-android-agent/verification.md)。这些接入不代替 D08/D10 的真实事件及人工核查保存验收。

## People 录像自动处理

首页设备菜单中的“人物录像自动解析”打开独立配置页。填写后端 HTTPS 基址（不含 `/api/v1`）、独立 Bearer 凭据、`config_version`，并将当前 SDK 摄像头逐台映射到后端已授权的 `camera_id`，保存后开启。开发 HTTP 只接受 Android 模拟器回环 `10.0.2.2` 或本机回环地址；不跟随 HTTP 重定向。配置默认空、默认关闭，不读取 SDK Token、模型配置或数据库凭据作为后端授权。

启用后的真实 SDK `DetectionType.HUMAN` 事件会持久登记任务；单独的 `FACE`、样例素材与 AI Demo 的本地补位事件不会触发处理。首次启用以保存时刻为发现边界，不上传全历史，也不会丢弃首个轮询间隔的新事件。无视频地址或结束时间尚未完整的事件保留为“等待摄像头完成录像”，后续查询继续更新；元数据稳定至少 20 秒后才开始下载。SDK 查询正常重叠最近 10 分钟，存在等待片段时还保留其起点。单次查询达到 200 条时停止推进水位并明确反馈，避免在未验证分页语义时静默漏事件。

下载使用隐藏 `EventMediaPlayer` 宿主与真实 `EventDownloadState.Success` 回调。回调后检查实际文件，复制到私有目录，使用 Android `MediaMetadataRetriever`／`MediaExtractor` 探测 MP4 的真实时长和单视频轨，再向 `POST /api/v1/recordings` 提交 multipart `mode=source + manifest + files`，单个二进制 part 的文件名固定为 `source.mp4`。`manifest` 保持 `RecordingJobInput`，响应中的 `recording` 明示原片已写入、`publication_status=pending`，`job` 继续驱动 App 状态；待 Vision 完成后才发布事实。原有完整分析包的结果导入保持其既定协议。任务 ID 固定为 `SHA-256(deviceSn + U+001F + sdkEventId)`；重试保留同一 ID、元数据和录像字节。`duration_ms` 来自真实文件，不由 SDK 时间差猜测；`sdk_timestamp_ms` 仅保留原始 SDK 毫秒值，不声明 UTC 校准。后端持久状态继续通过 `GET /api/v1/recording-jobs/{job_id}` 读取，区分原片上传、已入库待解析、Vision 解析中、解析完成与失败；App 完成回调只来自后端 `completed`。本轮不新增 SSE，也不使用 Agent run SSE 代表 Vision 完成。现有任务 ID、私有配置、凭据与完成通知标记保持兼容，不重复上传已受理的录像。

- **运行边界：** 当前是 App 前台、SDK 已登录时的协调器，约 20 秒发现事件、5 秒回读远端状态，单路下载／上传。切到后台或离开驿站壳进入独立 SDK 调试入口时停止执行并释放播放器；返回后恢复持久任务。它不是系统后台常驻服务，不承诺 App 被杀后继续采集。
- **持久化与限额：** 配置、Bearer 和任务快照使用 `noBackupFilesDir/people-pipeline.json` 的 `AtomicFile`，录像在同目录下的 `people-recordings/`，均为本机应用私有且不参加系统备份。任务上限 200 条、单文件 64 MiB、下载前本地录像预算 512 MiB，下载／上传失败最多自动重试 5 次。服务端已受理后的临时状态读取失败保留最后的 `queued`／`processing`，持续有界退避回查，最长间隔 2 分钟；启动时也会恢复旧版本误标为可重试失败的已受理任务，只读回查且不重新上传。401／403 停止自动请求，需修改配置；服务端明确返回的 `failed` 为幂等终态，不伪装重试成功。完成录像删除私有副本；超过 100 条的已完成任务逐步归档为去重标识，最多保留 20,000 个归档标识。切换服务、配置版本或当前摄像头映射不会把旧任务发送到新目标。
- **状态与回调：** 首页菜单与我的可进入人物录像配置页，页面显示 `PeoplePipeline.state: StateFlow<PeoplePipelineState>`、持久任务与安全错误码；消息页独立展示告警空态。`PeoplePipeline.completions: SharedFlow<PeoplePipelineCompletion>` 供当前会话订阅，宿主已有完成 Toast 回调。持久 StateFlow 是完成事实的权威来源；SharedFlow 是在线提示，接收方应按 `jobId` 幂等处理，不能把它当持久消息队列。
- **显式历史验证：** 配置页每台已映射摄像头的“手动处理最近已完成人物录像”只选择该设备最近 24 小时的最新真实 HUMAN 录像，复用相同下载、文件校验和稳定任务 ID。任务标为“历史手动”，不改变自动发现水位，不冒充启用后新事件。

本轮新增逻辑测试覆盖稳定任务 ID、HUMAN／FACE 区分、启用边界及首轮新事件、服务 URL 信任范围、进程恢复后的文件引用、重试与远端终态、配置目标隔离、有限退避，以及原片请求路径、multipart 字段、原始 manifest 和文件字节。构建及模拟器只能验证工程／界面，真实 People 发现、原片下载、API 入库和 Vision 完成须以同一真实任务分别留证；算法未配置或失败时必须保持失败状态。

## 准备本地配置

从仓库根目录执行，Python 3.9 或以上即可：

```sh
python3 scripts/setup-android.py
```

脚本校验模板中的敏感字段为空，然后在 Common 的原始源码路径生成以下本地配置。已存在的文件会保留，不读取或输出其凭据。

| 模板 | 生成路径，相对于 `ESIotKit-DemoApp-Common/sample-core/src/commonMain/kotlin/com/anker/esiotkit/sample/` | 本地填写字段 |
|---|---|---|
| `BusinessLoginConfig.kt.template` | `main/mega/login/business/BusinessLoginConfig.kt` | `EnvPR` / `EnvQA` 的 `CLIENT_SECRET`、`USER_ID`、`TOKEN`，按赛务实际发放填写 |
| `RouterConfig.kt.template` | `demo/RouterConfig.kt` | `API_KEY` |
| `AwsConfig.kt.template` | `demo/AwsConfig.kt` | 使用 AWS 备用实现时填写 `BEDROCK_ACCESS_KEY`、`BEDROCK_SECRET_KEY`、`TRANSCRIBE_ACCESS_KEY`、`TRANSCRIBE_SECRET_KEY` |

模板保留官方预置环境标识、客户端标识、模型与端点配置；所有上表凭据字段均为空串。凭据由赛务方提供，真实值只填写到生成的本地 `.kt` 文件。生成文件由仓库根 `.gitignore` 排除，模板保持空值。

当前 Android 的 Business Login 直接将 `USER_ID`／`TOKEN` 传给 `ESIotUserConfig`，默认环境为 US-PR；`CLIENT_SECRET` 未进入这条登录调用，无需为此额外填写。进入主页只表示完成本地 SDK 配置，凭据是否有效仍须以服务端设备列表查询结果判断；已绑定设备还需开机联网，才能进一步验证 P2P／直播。这组 SDK 凭据与 ParTrace 后端的 Bearer 凭据分别管理。

`RouterConfig` 的 `ROUTER_API_KEY` 环境变量用于 JVM 测试注入。Android 真机应用通常不能读取开发机终端的环境变量，真机运行时使用生成的本地配置。示例中的 AWS 实现保留为备用；默认 Router 链路与绑定见 `StartScreen.kt`。

## 安装本地 SDK

从已获得的官方材料中选择 AAR，执行：

```sh
python3 scripts/setup-android.py --sdk /path/to/ESIotKit-2.2.7.aar
```

脚本验证以下 SHA-256 后，将文件安装到 `ESIotKit-DemoApp-Android/libs/ESIotKit-2.2.7.aar`。SDK 不进入源码仓库。

```text
27916001d9f973361910e2a59da3a870f540f3038816dfc6141b3d33c633f87a
```

AAR 大小为 94,107,807 bytes，约 89.75 MiB。SDK 的公共运行依赖仍由 Gradle 解析；只放入 AAR 并不足以离线完成首次构建。脚本不联网，不运行 Gradle，不检查账号可用性；SDK 缺失时会明确报告。

## 构建和运行

安装 JDK 17 与 Android SDK API 36；最低设备版本为 Android 8.0 / API 26。工程使用 Gradle 8.13、AGP 8.11.1、Kotlin 2.2.21。

配置和 SDK 准备完成后，在 Android Studio 打开 `android/ESIotKit-DemoApp-Android`，或执行：

```sh
cd android/ESIotKit-DemoApp-Android
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

APK 产物为 `app/build/outputs/apk/debug/app-debug.apk`。`installDebug` 需要已连接且允许调试的设备。Gradle 首次运行会下载 Wrapper、插件和公共 Maven 依赖。成功构建、真机安装、设备登录分别需要实际运行验证；本仓库初始化不代表这些步骤已完成。

## 安卓交付与比赛展示

先完成可安装、能跑通核心流程的 Android App。比赛现场可使用电脑安卓模拟器或安卓真机投屏，具体方式后续根据设备和场地条件确定；开发阶段可先用模拟器推进页面和流程，并按开发计划补齐真机验收。

- **App 交付：** 提供可安装 APK、安装与启动说明，以及实际验证的 Android/设备环境。
- **现场演示：** 使用固定脚本演练选定的展示方式，核对 App、服务及输入素材版本一致。
- **录屏交付：** 每个拟用于比赛演示的稳定版本保留核心流程录屏，覆盖查询、证据打开、AI 引用跳转、人工保存与重启回读，作为讲解素材和现场备用。附 App/服务版本、Android 环境及模拟/真实预录/SDK 数据来源说明；画面不展示 Token、密钥等凭据。

摄像头 SDK 登录、直播与事件能力继续按 D06 单独做真机实测。展示彩排、录屏和各工作包的实际验收结果分别留存，完整交付见 [D10](../docs/DEVELOPMENT_PLAN.md#d10--同一事件闭环首页与-demo-验收)。

## 本底座包含的官方能力

保留官方登录、设备列表、配网、直播、事件、设备设置、能力查询、AI Demo 与多语言实现，以及 Common 的 `commonTest` / `androidUnitTest`。团队新增功能可以在此基础上迭代；自动采集得到的图片、视频、设备信息和日志应留在本地。

内部官方 README 中指向原材料目录的文档链接属于原包结构。完整 SDK 开发指南和 API 参考位于各参赛者持有的官方材料 `文档/` 目录，本仓库没有复制整套赛题材料。

共享接口与数据格式见 [docs/api](../docs/api/README.md)。

## Aurora 公网 People 验收 · 2026-10-17

已登录模拟器连接真实两台摄像头，People基址已切换为 `https://35.84.190.14`，使用独立AWS受限凭据，保留14条本机旧任务及其目标身份。门铃A真实历史原片与新自动People事件均完成SDK下载→公网POST原片→Aurora pending→Vision published/completed→App完成回调；B真实自动原片已入Aurora排队，待现场标定。新手动历史入口跳过已存在任务；验证错误拒绝且尚未受理的源提供明确“重新下载原片”入口，接受/完成/冲突数据不能被替换。最终JDK17构建、lint及13项People单测通过。App仍需前台及SDK登录，A worker需本机在线；这不是Android真机或系统后台常驻验收。详见[D06云端记录](../openspec/changes/d06-people-recording-pipeline/verification.md#aurora-公网与真实自动事件2026-10-17)。
