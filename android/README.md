# Android 应用与开发

本目录是 ParTrace Vision 的单一 Android Gradle 工程，包含 `app` 应用宿主与 `core` 业务／Compose UI 模块，基于 Anker 安防赛题官方 ESIotKit 2.2.7 示例整理。当前默认由 `StationSdkApp` 接入 SDK 会话，并通过 `StationApp` 呈现本轮用户设计的驿站界面；原官方 `SampleApp` 保留在「我的 → 右上设置齿轮 → SDK 调试」入口。来源与授权说明见 [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)。

**当前开发环境 · 2026-10-17：本地联调。** Agent 服务设置与 People 人物录像上传统一使用 `http://10.0.2.2:8000`，由 Mac 本地 PG `127.0.0.1:54391/partrace` 和 `.local-state/local/media/` 保存元数据与原片。`https://35.84.190.14` 为 **AWS 生产版本（线上）**，下方 Aurora 验收段落保留历史实测。切换后的新事件进入本地，旧任务保留原目标。环境、镜头映射及存储位置见 [环境说明](development-environment.md)。

本次已回读已安装 App，确认 Agent／People 地址一致、People 已启用且 A／B 映射保留；61 条旧任务保持不变。本地 API、独立凭据及 A worker 领取检查通过；修复私有媒体探测工具路径后，既有真实 A／B 原片的幂等重传均返回 200／`replayed=true`，本地 MP4、任务 ID 与尝试次数保留。**08:18 后回读：** 切换后的 A／B 新自动人物事件均已完成 SDK 下载、本地 POST、PG 与真实 MP4 落盘，新 A 已为 `completed`／1 次尝试；B 原片已入库并排队待标定。其中一条 B 的损坏首份副本曾被 422 拒绝，恢复原模拟器连接后观察到同一任务重新下载并受理，触发重下的具体原因未确认；异常副本保留，未放宽校验。该更新取代初次检查及 08:13 的未完成状态；扫描周期不代表入库时限，详见 [本次切换实测](development-environment.md)。

## 项目模块职责

主责：李果钦。技术采用 Kotlin + Compose，对应 D02、D08，并配合 D09、D10。首轮完成四 Tab 骨架、查询与候选列表、路线／时间线／关键帧联动、AI 计划与运行状态、人工核查保存和记录回读。先接共享样例，再连接后端与 Agent 的 `/api/v1` 接口；Android 不直接访问数据库。

摄像头 SDK 能力验证由 Harry Xu 独立负责。D10 闭环验收后，Android 再实现 P1 包裹二维码生成、扫码查询与入出库确认页面；扫码本身只查询，管理员确认后提交流水。本轮已实现驿站设计壳、SDK 设备与 Agent API 接入；同一真实事件的完整业务链路仍待验收。

分工、依赖和验收见 [开发计划](../docs/DEVELOPMENT_PLAN.md)，模块连接见 [工作台](https://yizhan-trace-workbench.vercel.app/#modules)。下面说明本地工程准备与官方底座能力。

业务功能及 DB／API 关联统一记录在 [模块设计](../docs/modules/README.md)，App 骨架见 [M01](../docs/modules/README.md#m01)，查询／AI／人工核查与 P1 扫码按对应模块接入。

```text
android/                     # Android Studio 直接打开这里
├── app/                     # 应用宿主、Manifest、打包
├── core/                    # Compose 页面、业务、SDK 适配及测试
├── gradle/                  # Wrapper 与依赖版本目录
├── libs/                    # 本地官方 AAR，不入库
├── config-templates/        # 空凭据配置模板
├── design/                  # 设计参考、渲染图与验证记录
├── settings.gradle.kts      # ParTraceVision：:app、:core
├── build.gradle.kts
├── gradle.properties
├── gradlew                  # Windows 使用 gradlew.bat
└── README.md
```

根 `settings.gradle.kts` 直接包含 `:app` 和 `:core`，应用依赖工程内的业务模块；无需工程外的 Common 路径。 迁移前的 IDE 配置和构建缓存保留在本地备份；开发时重新在 Android Studio 打开当前 `android/` 根。`esiotkit.demo.platform=android` 已保留；本底座未收录 iOS 工程、iOS 源集、CocoaPods、构建缓存、SDK 二进制、签名文件或采集数据。

### 正式页面直接在现有工程改造

按用户 2026-10-17 最新确认，正式 App 统一使用当前 `android/` 工程，替代此前保留两个官方 Demo 外层目录的决定。页面继续使用原生 Compose，应用包名和 SDK 集成保持；正式驿站界面为默认入口，SDK 调试保留独立入口。

- 页面与样式位于 [station](core/src/commonMain/kotlin/com/anker/esiotkit/sample/main/station/)：`StationApp.kt` 负责导航与首页，`ui/` 按聊天、事件、其他页面和共享组件归类；聊天布局与状态连接各自有明确文件。
- 平台适配、Agent 网络与持久化位于同包的 `androidMain`；宿主与打包位于 `app/`。
- [design](design/README.md) 统一管理参考图、渲染图和验证记录；`config-templates` 管理空凭据模板。应用使用的图片仍放在 `composeResources`，按各自用途管理。

AI 页的欢迎示例和占位提示已对齐录像查询功能；按用户最新截图要求移除顶部查询范围卡片。录像、录像时段和区域仍从底部入口选择，确认计划与运行卡片保留实际范围；失败保持真实状态，错误码可展开查看。展示文案调整不改变设备 SDK、后端接口、数据来源或人工核查边界。

### 页面源码索引

以下目录位于 `core/src/commonMain/kotlin/com/anker/esiotkit/sample/main/station/`：

```text
station/
├── StationSdkApp.kt          # SDK 会话与平台回调接入
├── StationApp.kt             # 首页、导航与应用浮层
├── StationDeviceState.kt     # 首页设备展示状态
├── ui/
│   ├── assistant/
│   │   ├── TraceAssistantScreen.kt  # 消息、计划与运行状态连接
│   │   └── TraceAssistantLayout.kt  # 聊天布局、建议与输入框
│   ├── events/               # 摄像头列表、共用事件过程与纯展示布局
│   ├── screens/              # 消息中心、我的、审核记录
│   └── components/           # 图标、顶栏、通知条、底部导航
├── agent/                    # Agent 协议与状态入口
├── events/                   # SDK 检测查询及受权事件详情仓库
├── records/                  # 审核记录数据访问
└── pipeline/                 # 人物录像任务协议与状态
```

| 修改内容 | 主要位置 |
| --- | --- |
| 首页、导航与浮层 | [StationApp.kt](core/src/commonMain/kotlin/com/anker/esiotkit/sample/main/station/StationApp.kt) |
| 聊天样式、输入框及建议 | [TraceAssistantLayout.kt](core/src/commonMain/kotlin/com/anker/esiotkit/sample/main/station/ui/assistant/TraceAssistantLayout.kt) |
| 聊天消息、计划和运行状态 | [TraceAssistantScreen.kt](core/src/commonMain/kotlin/com/anker/esiotkit/sample/main/station/ui/assistant/TraceAssistantScreen.kt) |
| 事件样式及 SDK 连接 | [ui/events](core/src/commonMain/kotlin/com/anker/esiotkit/sample/main/station/ui/events/) |
| 摄像头事件查询与过滤 | [StationEvents.kt](core/src/commonMain/kotlin/com/anker/esiotkit/sample/main/station/events/StationEvents.kt) |
| 消息、我的、审核页面 | [ui/screens](core/src/commonMain/kotlin/com/anker/esiotkit/sample/main/station/ui/screens/) |
| 公共样式组件 | [ui/components](core/src/commonMain/kotlin/com/anker/esiotkit/sample/main/station/ui/components/) |
| 设备直播详情 | [mega/live_video](core/src/commonMain/kotlin/com/anker/esiotkit/sample/main/mega/live_video/) |

新增页面和共享组件沿用 `ui` 下的分类，数据访问继续由 `agent`、`events`、`records`、`pipeline` 按来源负责；Android 专有网络、文件存储及平台能力放在对应的 `androidMain` 包。应用宿主位于 `app` 模块，官方 SDK 的 `mega` 页面保留原位置。Compose Preview 继续使用原 `StationDesignPreviews.android.kt`，与实际业务数据分开。

页面与资料归类见 [整理记录](design/verification/README.md)；本次统一 Gradle 工程的决定、兼容范围与实测见 [迁移记录](../openspec/changes/d02-android-single-project/verification.md)。

## 本轮 Android 设计还原

本轮按用户提供的六张设计图还原首页、AI 默认页、消息空态／通知提示／月份列表与审核记录，并按随后第一张参考图将“我的”改为记录主页面。六张参考与原生预览见 [设计与验证](design/README.md)，“我的”追加说明与本次验证见 [设计预览说明](design/verification/app/README.md)。通知静音弹层和站点选择浮层保留。当前按用户追加需求使用首页、事件、AI 追溯、我的四个入口；AI 保留全屏聊天和返回操作，消息中心由首页或我的设置齿轮进入。独立事件页接摄像头检测记录，原计划中的算法事件查询、证据、核查与记录业务范围及验收目标保留。上一轮视觉范围与验证记录保留在 [d02-station-design](../openspec/changes/archive/2026-10-16-d02-station-design/proposal.md)；本轮真实设备接入见 [d02-station-sdk-devices](../openspec/changes/archive/2026-10-16-d02-station-sdk-devices/proposal.md)。

- **首页与 SDK：** 保留蓝白渐变、设备卡与胶囊导航，直接读取当前账号真实 SDK 设备列表；当前两个设备显示为真实卡片，数量不写死。名称、在线状态和最近缩略图来自对应设备，原“深圳”“安克黑客松”“68”样例撤下。共用原 Koin／SDK 初始化及有效缓存业务会话；加载、空列表、错误、未登录／401 和重试分别反馈，不用样例兜底。摄像头卡片复用原单路直播，基站及更多／状态入口进入对应原设备设置；多画面先展示真实最近缩略图网格，可点进单路直播，不是两路同时直播。
- **站点与浮层：** 支持校园驿站／安防赛场本地标题切换，当前仅为界面分组，不对应 SDK 房屋映射，也不按该标题过滤真实设备。
- **通知：** 铃铛提供30分钟、1小时、直到重新打开三个本地静音选项，偏好按站点持久化；有限时长到期恢复，再次点击铃铛可手动恢复。该偏好仅控制设计壳的本地状态。消息中心的启用和设置按钮打开真实系统通知设置，不将本地静音描述为系统通知或摄像头报警配置成功。
- **AI 与原工程：** 建议问题和文本输入连接 Backend Agent，按已授权的已发布录像及区域生成计划，确认后运行，支持停止、恢复、服务端分页历史与证据引用。AI 默认页按稿保留欢迎语和建议问题，四个条件按钮可选择录像、查看时间及编辑事件／区域；右上历史菜单可打开服务设置或新建会话。我的右上齿轮提供「SDK 调试」进入官方 `SampleApp` 的路径，并保留消息中心、人物录像自动解析、审核记录与后端服务配置入口；SDK 与后端凭据仍独立管理。
- **我的与审核：** 我的采用蓝白渐变品牌头、常驻“检索记录”输入框、“全部”和已保存记录卡片，复用独立审核页的数据源；列表与详情使用独立后端凭据只读查询 `/api/v1/review-records` 和 `/api/v1/review-records/{id}`。支持本地事件编号／审核编号／备注／时间搜索与清空，时间由 UTC 转为本机时区；加载、未配置、错误、空列表和无匹配分别反馈。卡片“已保存”表示该记录已由后端持久化，详情中的核查结论仍为真实已确认／已排除／待确认；本轮不调用模型或保存接口，不新增样例业务记录。
- **消息与人物录像：** 系统通知关闭时显示黄色提示，返回应用后重新检查权限。消息页目前没有真实告警源，因此显示空态；设计中的月份卡片只在预览出现。首页菜单和我的设置均可打开消息与人物录像配置，首页仍保留独立审核记录入口。
- **边界：** 父层处理真实系统栏及键盘 inset，弹层阻止底层触摸；未复制设计图中的时间、电量或消息页左侧灰色残留。最近缩略图不代表实时画面或连续轨迹；本轮构建／UI、真实设备服务与摄像头播放验证分别留证，不代表 D02 真机、D06 全部能力或 D08／D10 业务闭环验收完成。

“我的”追加调整已用 JDK 17.0.2 完成本次 Debug 构建与 lint（133 项既有 Warning、0 Error），并在 API 37 模拟器验证既有后端模拟审核记录的读取／详情、搜索／清空、设置面板及 SDK 调试往返。四张 [本次截图与验证凭据](design/verification/app/README.md)已保存；未重验服务未配置／失败／空列表、完整键盘展开避让或真机，本次未运行单元测试。

## 摄像头检测事件页

底部「事件」汇总当前 SDK 账号摄像头在所选日期的检测记录，沿用首页蓝白渐变、圆角白卡和胶囊导航。日期按本机时区划分，支持全部／单台摄像头，以及人物、移动、宠物、车辆、包裹、门铃和声音筛选；卡片显示设备、时间、类型、时长与真实缩略图。点击卡片先进入共用“事件过程”详情，查看原始 SDK 检测及经核实的算法事件；详情中的“播放摄像头原片”再进入原 SDK 回放。缺录像时保留检测元信息和真实提示。

查询 30 秒超时，切换条件、离页或切后台取消旧请求。同一筛选范围的结果只在根页面内存保留，回放返回时保留筛选与列表位置；刷新反馈放在列表外，防止临时提示项移动列表索引。账号退出或摄像头范围变化后不展示旧范围记录。加载、未登录、无摄像头、暂未返回事件和失败分别显示。

SDK 返回可能合并缓存，不能据此声明完整云端历史或所有来源刷新成功。单次 `count=200`，达到上限明确提示结果可能不完整；暂未接未经验证的游标分页。此页只读 SDK，不自动下载、删除、上传或发起 AI 分析，也不读取 Backend 算法事件和 People 处理任务，不新增 DB／API 契约。站点名称仍只是本地界面分组。

本轮 27 项相关单元测试、Debug 构建及 lint 通过；列表、空态与失败态由纯 Compose Preview 核对，示例明确标注为模拟事件。实际摄像头查询、回放往返与 Android 真机仍待本功能实测，当前 APK 未安装到设备。本轮代码、命令及验证边界见 [事件页验证](../openspec/changes/d02-sdk-events-tab/verification.md)，样式见 [设计预览](design/README.md#摄像头检测事件页)。

## Agent 对话与事件过程联动

按用户新增 P1/P2/P3 参考，对话采用蓝色用户气泡、助手正文、可展开核查进度和淡紫色相关片段。工具进度来自现有 GET run 的可选 `progress`，仍每 2 秒轮询；旧服务无字段、进程重启或有界记录淘汰时显示未记录，不以计划步骤或完成状态补造“已完成”。计划确认、真实录像门禁、停止和历史继续保留，顶部查询范围卡片保持移除。

已返回的相关片段按有效录像／事件 ID 打开独立详情，不再因聊天控制器 `busy` 暂时禁用入口；卡片和旧引用回退入口保持一致。详情仍由原仓库重新验证后端身份、录像和证据归属，不复用聊天请求的授权结果。真机检查与限制见 [D08 验证](../openspec/changes/d08-agent-event-detail/verification.md)。

聊天的相关事件片段与底部事件列表共用 [StationEventDetailScreen](core/src/commonMain/kotlin/com/anker/esiotkit/sample/main/station/ui/events/StationEventDetailScreen.kt)。后端事件按原运行的 `recording_id/event_id/evidence_id` 重新鉴权；SDK 原事件按设备 SN 和原生事件 ID 的稳定 job 核对来源、服务、镜头及配置，关联到同一原片的已发布事实。未入库、排队、失败、无区域配置、映射不同和权限错误分别反馈。SDK 与算法的时间线条目明确区分，不按相近时间拼接。

详情提供实际事件时间线、受权关键帧与翻帧；已入库且媒体可用的 `sdk/prerecorded` 录像可显式回看原片，使用同一 Agent 读身份与固定录像端点，私有缓存后由系统播放器从所选事件起点定位并保留完整上下文。该入口不依赖摄像头 SDK 登录；切换片段、后台或退出时停止并清理缓存，读取或解码失败明确反馈。有真实 SDK 关联时仍保留独立 SDK 回放；内存无 SDK 事件时，只有经核实的设备／原始 ID／SDK 时间齐备才做有界查询，并严格匹配 ID 后回放，查找失败不伪造播放器对象。时间使用录像相对偏移，未校准 SDK 时间不冒充 UTC；缺帧不等于已确定的遮挡区间。单次只读范围最多 32 个真实区域／200 条事件，达到上限提示可能不完整，已验证的点击目标始终保留。

当前一次 Agent 运行仅有一段录像，不生成设计稿中的第二摄像头或跨镜轨迹。旧版本摄像头／配置别名若与任务规范 ID 不同，会保守提示不能关联，需由真实映射对齐。详情不自动上传、启动算法或模型，也没有通用后端原片播放器／下载按钮；后端原片与 SDK 回放分别管理。

数据源位于 [StationEventDetails.kt](core/src/commonMain/kotlin/com/anker/esiotkit/sample/main/station/events/StationEventDetails.kt)，Android 实现复用独立读凭据，媒体会话不含 token，配置变更后旧图不再读取。参考、预览与实际检查见 [设计资料](design/README.md#agent-对话与共用事件过程) 和 [D08 验证](../openspec/changes/d08-agent-event-detail/verification.md)。本轮界面使用新 APK；真实工具进度需后端加载对应版本。

## SDK 设备详情

首页点击摄像头进入按新稿重排的 `MegaLiveVideoScreen`：真实设备标题、原播放器、五项快捷控制、Events / Playback / All Recordings 和录像管理卡片。全部原直播 SDK action 保留；静音、灯光、跟踪、校准、360°、全景、快捷回复、预设位、双镜头、夜视、手动报警等按设备能力出现在“更多设备控制”。设置仍进入原 SDK 设备设置。参考与实际验证见 [设备详情说明](design/README.md#sdk-设备详情追加还原)。

Events、Playback 和 All Recordings 使用当前设备的原 SDK 事件录像列表；选择片段后使用原回放页，保留日期／类型筛选、下载、删除与队列。它们不代表 SDK 新增连续录像或云订阅；设计稿右侧区域用于真实下载队列与设备设置。基站仍进入原设置页。

## Agent API 接入

AI 追溯使用 [Agent 对接合同](../docs/api/AGENT_API.md) 中的既有 API。先读取授权的已发布录像及其版本区域；当前分析窗口为所选整段录像，未知 UTC 时间不做推断。提交问题后先展示服务端计划，点击“确认并开始分析”才创建运行。活动页面每两秒回读运行；切后台或进入证据详情时停止轮询，返回同一对话后按持久 run_id 恢复。用户“停止分析”调用真实 stop API，并以返回的服务端终态为准。

在首页、事件页或我的页明确点击「AI 追溯」Tab，默认进入新对话并清除旧消息、计划、证据、分页状态和未发送草稿，保留后端配置与已选录像、区域；旧会话通过历史入口手动打开。欢迎空态不会创建服务端空会话，也不删除服务端历史。若真实运行尚未结束，或启动响应结果不明，则保留原稳定 run_id 和确认载荷，先恢复该运行，避免遗失停止依据或重复执行。从证据详情返回、前后台切换、SDK 会话重建及服务设置入口继续当前会话。范围与验收场景见 [本轮入口变更](../openspec/changes/d07-fresh-assistant-entry/proposal.md)。

开发配置可在仓库根执行：

```sh
python3 scripts/setup-android-agent.py
```

默认从私有 `.local-state/bootstrap-credentials.json` 取 viewer 的后端 Bearer，只生成被 Git 忽略的 `core/src/androidDebug/assets/partrace-agent.json`，开发模拟器地址为 `http://10.0.2.2:8000`。使用其他后端时传 `--base-url https://your-api-host`；显式覆盖已有配置用 `--force`。该资产只进入 debug 构建，不进入 release；资产只含 API 地址和独立 Bearer，不复制 Backend `.env`、数据库密码或模型 Key。此开发 APK 包含该 API 凭据，只在自己的开发设备安装；共享 APK 前移除该私有资产并使用页面设置。

App 内“服务设置”允许修改地址与 Bearer，运行时保存在 `noBackupFilesDir`；同地址留空保留凭据，换服务必须填写新凭据。服务地址不含 `/api/v1`；只接受 HTTPS 或开发回环 HTTP，禁用重定向。真机可通过 `adb reverse tcp:8000 tcp:8000` 后配置 `http://127.0.0.1:8000`。摄像头 SDK Token 与后端 Bearer 分别管理。

会话与消息通过服务端分页回读；失败／停止不生成助手消息，本地最后 run_id 用于恢复。创建会话、消息和运行前保存稳定 ID，网络结果不明时使用同载荷重试。结果按四种卡片展示，点击引用重新鉴权读取当前元数据与图片；缺失媒体保留 ID 并明确反馈。无录像、无匹配、未索引、权限错误和依赖失败均不使用模拟答案补位。正式聊天仅选择已发布的 SDK 原片或真实预录，模拟录像不进入选择器或自动默认范围；暂无真实录像时明确显示空态。旧模拟选择／执行的本地恢复指针退出当前对话，不删除服务端历史；历史只读查看不提供重新分析。确认、计划创建、启动及重试在客户端再次检查真实录像、摄像头、时段和对应配置区域，不能把旧模拟计划改绑到新录像。

读取到失败任务时，只在任务卡内显示失败说明、错误码和“重新分析”；不再重复显示为“服务暂时不可用”。真正的请求错误仍单独提示。重新分析使用原确认计划和新的 run_id，保留旧失败历史，不自动重跑。2026-10-17 已在模拟器覆盖安装验证，并通过该入口完成 DeepSeek 模拟录像分析，见 [验证记录](../openspec/changes/d07-android-agent/verification.md#deepseek-切换与失败提示修复--2026-10-17)。

构建、客户端协议测试与实际服务/模型验证见 [本轮验证记录](../openspec/changes/d07-android-agent/verification.md)。这些接入不代替 D08/D10 的真实事件及人工核查保存验收。

## People 录像自动处理

首页设备菜单中的“人物录像自动解析”打开独立配置页。填写后端 HTTPS 基址（不含 `/api/v1`）、独立 Bearer 凭据、`config_version`，并将当前 SDK 摄像头逐台映射到后端已授权的 `camera_id`，保存后开启。开发 HTTP 只接受 Android 模拟器回环 `10.0.2.2` 或本机回环地址；不跟随 HTTP 重定向。配置默认空、默认关闭，不读取 SDK Token、模型配置或数据库凭据作为后端授权。

用户已明确授权将人物录像范围扩展为真实 SDK `DetectionType.FACE=1` 或 `DetectionType.HUMAN=2`。SDK 查询、本地自动判定和手动最新历史选择统一使用这两类；事件图片或缩略图任一包含 FACE／HUMAN 即可，同一事件混合两类仍使用同一个稳定任务 ID。普通移动、其他检测类型、样例素材与 AI Demo 的本地补位事件不触发处理。人脸裁剪图不是连续原片，也不作为已确认身份。

首次启用以保存时刻为发现边界，不上传全历史，也不会丢弃首个轮询间隔的新事件。正式范围扩展逻辑不重置已启用边界、水位、去重记录或既有任务，不全量回填此前 FACE 历史；本轮针对用户指定漏片另做一次私有有界回查，只回退扫描游标，保留启用边界和seen，非后续自动行为。无视频地址或结束时间尚未完整的事件保留为“等待摄像头完成录像”，后续查询继续更新；元数据稳定至少 20 秒后才开始下载。SDK 查询正常重叠最近 10 分钟，存在等待片段时还保留其起点。单次查询达到 200 条时停止推进水位并明确反馈，避免在未验证分页语义时静默漏事件。

下载使用隐藏 `EventMediaPlayer` 宿主与真实 `EventDownloadState.Success` 回调。回调后检查实际文件，复制到私有目录，使用 Android `MediaMetadataRetriever`／`MediaExtractor` 探测 MP4 的真实时长和单视频轨，再向 `POST /api/v1/recordings` 提交 multipart `mode=source + manifest + files`，单个二进制 part 的文件名固定为 `source.mp4`。`manifest` 保持 `RecordingJobInput`，响应中的 `recording` 明示原片已写入、`publication_status=pending`，`job` 继续驱动 App 状态；待 Vision 完成后才发布事实。原有完整分析包的结果导入保持其既定协议。任务 ID 固定为 `SHA-256(deviceSn + U+001F + sdkEventId)`；重试保留同一 ID、元数据和录像字节。`duration_ms` 优先取真实 MP4 视频轨道时长（MediaExtractor，微秒转毫秒），缺少该元数据才回退容器时长，避免音频尾部较长导致与后端视频轨道校验不符；不由 SDK 时间差猜测；`sdk_timestamp_ms` 仅保留原始 SDK 毫秒值，不声明 UTC 校准。后端持久状态继续通过 `GET /api/v1/recording-jobs/{job_id}` 读取，区分原片上传、已入库待解析、Vision 解析中、解析完成与失败；App 完成回调只来自后端 `completed`。本轮不新增 SSE，也不使用 Agent run SSE 代表 Vision 完成。现有任务 ID、私有配置、凭据与完成通知标记保持兼容，不重复上传已受理的录像。

- **运行边界：** 当前是 App 前台、SDK 已登录时的协调器，约 20 秒发现事件、5 秒回读远端状态，单路下载／上传。切到后台或离开驿站壳进入独立 SDK 调试入口时停止执行并释放播放器；返回后恢复持久任务。它不是系统后台常驻服务，不承诺 App 被杀后继续采集。
- **持久化与限额：** 配置、Bearer 和任务快照使用 `noBackupFilesDir/people-pipeline.json` 的 `AtomicFile`，录像在同目录下的 `people-recordings/`，均为本机应用私有且不参加系统备份。任务上限 200 条、单文件 64 MiB、下载前本地录像预算 512 MiB，下载／上传失败最多自动重试 5 次。服务端已受理后的临时状态读取失败保留最后的 `queued`／`processing`，持续有界退避回查，最长间隔 2 分钟；启动时也会恢复旧版本误标为可重试失败的已受理任务，只读回查且不重新上传。401／403 停止自动请求，需修改配置；服务端明确返回的 `failed` 为幂等终态，不伪装重试成功。完成录像删除私有副本；超过 100 条的已完成任务逐步归档为去重标识，最多保留 20,000 个归档标识。切换服务、配置版本或当前摄像头映射不会把旧任务发送到新目标。
- **状态与回调：** 首页菜单与我的右上设置齿轮可进入人物录像配置页，页面显示 `PeoplePipeline.state: StateFlow<PeoplePipelineState>`、持久任务与安全错误码；消息页独立展示告警空态。`PeoplePipeline.completions: SharedFlow<PeoplePipelineCompletion>` 供当前会话订阅，宿主已有完成 Toast 回调。持久 StateFlow 是完成事实的权威来源；SharedFlow 是在线提示，接收方应按 `jobId` 幂等处理，不能把它当持久消息队列。
- **显式历史验证：** 配置页每台已映射摄像头的“手动处理最近已完成人物录像”只选择该设备最近 24 小时的最新真实 FACE／HUMAN 录像，复用相同下载、文件校验和稳定任务 ID。任务标为“历史手动”，不改变自动发现水位，不冒充启用后新事件。

既有逻辑测试覆盖稳定任务 ID、启用边界及首轮新事件、服务 URL 信任范围、进程恢复后的文件引用、重试与远端终态、配置目标隔离、有限退避，以及原片请求路径、multipart 字段、原始 manifest 和文件字节。本次 FACE 扩展的 `PeoplePipelineLogicTest` 实际13项通过，无失败或跳过；覆盖 FACE-only／HUMAN-only／混合检测、非人物排除和共同启用边界，并核对三处筛选复用同一条件，见 [追加验证](../openspec/changes/d06-people-recording-pipeline/verification.md#face与human共同触发范围扩展--2026-10-17)。新版APK assemble与lint已通过（0 errors／133既有warnings），已保留userdata覆盖安装并恢复前台扫描。

两条首次被422拒绝的 HUMAN 录像，各重新下载一次后已严格校验并本地受理，保留同一任务和首份坏片历史；新原片为 A 30.866秒、B 20.544秒，PG摘要与磁盘一致，独立完整解码均通过。最终回读A已completed／published，B仍queued／pending。扩展人物类型本身不修复坏片，也不放宽 MP4 校验。

A 10:26:50的raw FACE事件已实际入库：稳定任务 `704de40a…`、18.266秒／2,270,487bytes，原片SHA与PG一致、完整解码通过，最终核对为available／pending／processing、无错误。受理后的一致DB／WAL快照仍为raw detection=1／crops=[1]，与同事件任务关联，证明查询侧FACE扩展已在真实摄像头链路生效。SDK会将部分raw FACE1映射成Domain HUMAN2，thumbnail缺省为0，因此其业务值 `[2,0]` 不表示检测随时间改变，也不要求Domain仍为1。该目标在解析中，未宣称算法完成；B现场标定和完整解析仍另行验收，默认关闭的算法通知与 AWS 不因此改变。

## 准备本地配置

从仓库根目录执行，Python 3.9 或以上即可：

```sh
python3 scripts/setup-android.py
```

脚本校验模板中的敏感字段为空，然后在 Common 的原始源码路径生成以下本地配置。已存在的文件会保留，不读取或输出其凭据。

| 模板 | 生成路径，相对于 `core/src/commonMain/kotlin/com/anker/esiotkit/sample/` | 本地填写字段 |
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

脚本验证以下 SHA-256 后，将文件安装到 `libs/ESIotKit-2.2.7.aar`。SDK 不进入源码仓库。

```text
27916001d9f973361910e2a59da3a870f540f3038816dfc6141b3d33c633f87a
```

AAR 大小为 94,107,807 bytes，约 89.75 MiB。SDK 的公共运行依赖仍由 Gradle 解析；只放入 AAR 并不足以离线完成首次构建。脚本不联网，不运行 Gradle，不检查账号可用性；SDK 缺失时会明确报告。

## 构建和运行

安装 JDK 17 与 Android SDK API 36；最低设备版本为 Android 8.0 / API 26。工程使用 Gradle 8.13、AGP 8.11.1、Kotlin 2.2.21。

配置和 SDK 准备完成后，在 Android Studio 打开 `android`，或执行：

```sh
cd android
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

APK 产物为 `app/build/outputs/apk/debug/app-debug.apk`。`installDebug` 需要已连接且允许调试的设备。Gradle 首次运行会下载 Wrapper、插件和公共 Maven 依赖。成功构建、真机安装、设备登录分别需要实际运行验证；本仓库初始化不代表这些步骤已完成。

个人安卓手机可直接安装此 APK：把文件传到 Android 8.0 或以上的手机，点击 APK，并在系统提示时允许该来源安装应用。Mac 使用安卓模拟器运行。2026-10-17 已将桌面名称更新为「驿站追迹」，启动器及启动画面沿用“我的”页现有白色品牌 Logo 与蓝色背景；本轮 Debug 构建及 lint 通过，尚未进行本轮真机安装。图标预览及资源检查见 [品牌图标记录](design/README.md#android-安装图标与启动画面)。

当前 APK 为包含私有联调配置的开发包，只用于本人设备，不作为公开下载包。手机无法使用模拟器专用的 `10.0.2.2` 地址；USB 联调可按上文配置 `adb reverse` 和 `http://127.0.0.1:8000`，Agent 与人物录像服务分别设置；脱离电脑使用时需填写手机可访问的 HTTPS 后端及对应授权。

## 安卓交付与比赛展示

先完成可安装、能跑通核心流程的 Android App。比赛现场可使用电脑安卓模拟器或安卓真机投屏，具体方式后续根据设备和场地条件确定；开发阶段可先用模拟器推进页面和流程，并按开发计划补齐真机验收。

- **App 交付：** 提供可安装 APK、安装与启动说明，以及实际验证的 Android/设备环境。
- **现场演示：** 使用固定脚本演练选定的展示方式，核对 App、服务及输入素材版本一致。
- **录屏交付：** 每个拟用于比赛演示的稳定版本保留核心流程录屏，覆盖查询、证据打开、AI 引用跳转、人工保存与重启回读，作为讲解素材和现场备用。附 App/服务版本、Android 环境及模拟/真实预录/SDK 数据来源说明；画面不展示 Token、密钥等凭据。

摄像头 SDK 登录、直播与事件能力继续按 D06 单独做真机实测。展示彩排、录屏和各工作包的实际验收结果分别留存，完整交付见 [D10](../docs/DEVELOPMENT_PLAN.md#d10--同一事件闭环首页与-demo-验收)。

## 本底座包含的官方能力

保留官方登录、设备列表、配网、直播、事件、设备设置、能力查询、AI Demo 与多语言实现，以及 core 的 `commonTest` / `androidUnitTest`。团队新增功能可以在此基础上迭代；自动采集得到的图片、视频、设备信息和日志应留在本地。

当前工程与模块说明已按新目录维护；旧官方工程名仅保留在来源与历史验证记录中。完整 SDK 开发指南和 API 参考位于各参赛者持有的官方材料 `文档/` 目录，本仓库没有复制整套赛题材料。

共享接口与数据格式见 [docs/api](../docs/api/README.md)。

## AWS 生产版本历史验收 · Aurora 公网 People · 2026-10-17

已登录模拟器连接真实两台摄像头，People基址已切换为 `https://35.84.190.14`，使用独立AWS受限凭据，保留14条本机旧任务及其目标身份。门铃A真实历史原片与新自动People事件均完成SDK下载→公网POST原片→Aurora pending→Vision published/completed→App完成回调；B真实自动原片已入Aurora排队，待现场标定。新手动历史入口跳过已存在任务；验证错误拒绝且尚未受理的源提供明确“重新下载原片”入口，接受/完成/冲突数据不能被替换。最终JDK17构建、lint及13项People单测通过。App仍需前台及SDK登录，A worker需本机在线；这不是Android真机或系统后台常驻验收。详见[D06云端记录](../openspec/changes/d06-people-recording-pipeline/verification.md#aurora-公网与真实自动事件2026-10-17)。
