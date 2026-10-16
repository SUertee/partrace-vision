# Android 开发底座

本目录取自 Anker 安防赛题官方 ESIotKit 2.2.7 示例工程，保留 Android 应用和 Common 公共模块，供本次比赛团队开发使用。来源与授权说明见 [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)。

## 项目模块职责

主责：李果钦。技术采用 Kotlin + Compose，对应 D02、D08，并配合 D09、D10。首轮目标包括四 Tab 骨架、查询与候选列表、路线／时间线／关键帧联动、AI 计划与运行状态、人工核查保存和记录回读。开发先接共享样例，再连接后端与 Agent 的 `/api/v1` 接口；Android 不直接访问数据库。

摄像头 SDK 能力验证由 Harry Xu 独立负责。D10 闭环验收后，Android 再实现 P1 包裹二维码生成、扫码查询与入出库确认页面；扫码本身只查询，管理员确认后提交流水。当前 `dev` 保留官方示例底座；下述本地增量实现尚待合入，上述完整业务链路仍未验收。

分工、依赖和验收见 [开发计划](../docs/DEVELOPMENT_PLAN.md)，模块连接见 [工作台](https://yizhan-trace-workbench.vercel.app/#modules)。下面说明本地工程准备与官方底座能力。

业务功能及 DB／API 关联统一记录在 [模块设计](../docs/modules/README.md)，App 骨架见 [M01](../docs/modules/README.md#m01)，查询／AI／人工核查与 P1 扫码按对应模块接入。

```text
android/
├── ESIotKit-DemoApp-Android/  # 独立 Gradle 工程
├── ESIotKit-DemoApp-Common/   # sample-core：业务、Compose UI、Android 实现
└── config-templates/         # 不含凭据的本地配置模板
```

Android 工程通过 `../ESIotKit-DemoApp-Common/sample-core` 引用公共模块，两目录应保持同级。`esiotkit.demo.platform=android` 已保留；本底座未收录 iOS 工程、iOS 源集、CocoaPods、构建缓存、SDK 二进制、签名文件或采集数据。

## 当前状态：本地验证与待合入实现

截至 2026-10-17，以下结果来自本地工作区与开发服务。相关 Android、Agent 和 People 管线增量源码尚未随本次文档更新合入 `dev`；从当前 `dev` 按下方命令构建，得到的仍是其现有源码版本。跨模块状态与验收边界见 [项目当前状态](../docs/PROJECT_STATUS.md)。

| 范围 | 已有本地结果 | 尚未验收的边界 |
| --- | --- | --- |
| 驿站界面 | Compose 三入口为首页、AI、我的，另有消息中心、站点选择与本地静音弹层；原官方示例保留调试入口 | 原四 Tab 规划中的事件、证据、人工核查与记录业务目标继续保留；三入口视觉壳不代表这些业务已完成 |
| 真实 SDK 设备 | 在 API 37 arm64 模拟器连接两台真实摄像头，核对设备列表、名称、在线状态、最近缩略图及单路直播；列表数量随账号返回变化 | 最近预览网格不等于两路同时直播；Android 真机、全部异常协议及其余 SDK 能力仍待逐项实测 |
| People 原片处理 | A 门铃的真实历史 HUMAN 录像明确标为手动验证，30.466 秒原片完成 SDK 下载、API 入库、Vision 解析及 App completed 回读；B 球机的新 HUMAN 原片由前台自动发现并入库排队。已验证 source 模式先写 pending 录像，完成后发布事实；已受理任务的断网恢复只读回查 | B 仍为 pending／queued，等待与现场画面一致的货架标定和区域映射；A 的业务区域事件／证据计数为 0，不代表无人。当前协调器依赖 App 前台及 SDK 登录，不承诺系统后台常驻 |
| Agent 接入 | 本地客户端已接计划确认、运行控制、恢复、历史及证据读取；真实模型曾对明确标为模拟数据的录像生成计划 | 实际执行分别返回 `agent_execution_failed` 与 `agent_timeout`，没有成功的最终答复、引用或卡片。合同及本地 HTTP fixture 测试不等于真实模型／摄像头证据验收；模型读取元数据与描述，未向 VLM 发送视频或图片像素 |
| 最新六图还原 | 本地已有首页、AI、消息多状态及只读审核页面的增量代码 | 尚无本轮完成凭据，需对当前版本重新完成构建、界面核对及验收记录。真实告警尚未接入；审核读取不等于人工保存流程已完成 |

Android 真机、D09 人工保存与重启回读、D10 同一真实事件的查询开帧、Agent 引用、人工保存及回读闭环仍未验收。设备画面、原片、私有配置、SDK 设备映射和凭据保留在本地，不随本次文档发布。

## AWS 公网 People 验收

2026-10-17已登录模拟器使用 `https://35.84.190.14` 和独立AWS Bearer，真实门铃A的历史原片与新自动People事件均完成App下载→API source上传→Aurora pending→Vision published/completed→App完成回调。球机B新自动原片已入Aurora排队，等待现场标定；A建档0区域事件/0业务证据不代表无人。此前本机任务保留原目标，未被重定向。新增历史入口跳过已有任务；尚未受理的验证错误源可显式重下，已受理/完成/冲突源不替换。

本轮JDK17构建、lint及13项People测试通过；A worker仍在Mac运行，自动采集依赖App前台及SDK登录。源码尚待合入dev，详情与实际ID见[AWS部署验收](../docs/deployment/AWS_PEOPLE_20261017.md)。

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

## 本底座包含的官方能力

保留官方登录、设备列表、配网、直播、事件、设备设置、能力查询、AI Demo 与多语言实现，以及 Common 的 `commonTest` / `androidUnitTest`。团队新增功能可以在此基础上迭代；自动采集得到的图片、视频、设备信息和日志应留在本地。

内部官方 README 中指向原材料目录的文档链接属于原包结构。完整 SDK 开发指南和 API 参考位于各参赛者持有的官方材料 `文档/` 目录，本仓库没有复制整套赛题材料。

共享接口与数据格式见 [docs/api](../docs/api/README.md)。
