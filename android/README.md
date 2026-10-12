# Android 开发底座

本目录取自 Anker 安防赛题官方 ESIotKit 2.2.7 示例工程，保留 Android 应用和 Common 公共模块，供本次比赛团队开发使用。来源与授权说明见 [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)。

```text
android/
├── ESIotKit-DemoApp-Android/  # 独立 Gradle 工程
├── ESIotKit-DemoApp-Common/   # sample-core：业务、Compose UI、Android 实现
└── config-templates/         # 不含凭据的本地配置模板
```

Android 工程通过 `../ESIotKit-DemoApp-Common/sample-core` 引用公共模块，两目录应保持同级。`esiotkit.demo.platform=android` 已保留；本底座未收录 iOS 工程、iOS 源集、CocoaPods、构建缓存、SDK 二进制、签名文件或采集数据。

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
