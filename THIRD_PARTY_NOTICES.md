# 第三方材料与授权说明

## Anker / eufy Security ESIotKit 官方示例

`android/ESIotKit-DemoApp-Android/` 与 `android/ESIotKit-DemoApp-Common/` 来自 2026 Anker 安防赛题随附的 `安防赛题材料/demo工程/AppSDKDemo/`，版本为 ESIotKit 2.2.7。工程中的官方代码、资源和示例业务实现版权归发布方所有。

原包 `AppSDKDemo/README.md` 的 License 说明为：

> 示例代码版权归发布方所有（Proprietary），仅供本次比赛参赛使用。

原材料 `文档/赛题规则与准备指南.md` 的授权范围说明为：

> 示例工程版权归发布方所有（Proprietary），仅供本次比赛参赛使用。参赛者作品归属与开源要求以赛事正式条款为准。

本仓库为参赛团队的私有开发仓库，保留上述授权范围。此说明不为官方示例新增开放源代码许可，也不授予公开发布或一般再分发权限。后续是否公开团队作品，应依据赛事正式条款及其中第三方材料的授权范围处理。

收录时保留 Android Gradle 工程、Common 的共享与 Android 源集、原有测试和资源；剔除 iOS 工程及源集、CocoaPods、缓存、构建产物、本地凭据配置和 SDK AAR。三份官方配置源码改为 `android/config-templates/*.kt.template`，所有凭据字段为空，通过 `scripts/setup-android.py` 生成仅供本地使用的配置。

## ESIotKit SDK 二进制

官方 AAR `ESIotKit-2.2.7.aar` 未收录于本仓库。参赛者可从自己取得的官方材料中使用该文件，并通过本地安装脚本校验及放置到工程 `libs/` 目录。

```text
大小：94,107,807 bytes
SHA-256：27916001d9f973361910e2a59da3a870f540f3038816dfc6141b3d33c633f87a
```

SDK 与其内含组件的授权仍以发布方及对应组件的条款为准。

## 公共构建与运行依赖

Android / Common 工程依赖 Gradle、Kotlin、Android Gradle Plugin、AndroidX、Compose、Ktor、Koin、Coil、SQLDelight、AWS Android SDK、Paho MQTT 等第三方组件。具体依赖和版本见 `android/ESIotKit-DemoApp-Android/gradle/libs.versions.toml` 以及两个模块的 `build.gradle.kts`。

公共依赖通过官方示例配置的 Maven 仓库解析，本仓库未复制这些依赖的发行包。其版权与许可由各依赖项目及实际分发材料决定；本说明不替代对应的完整许可文件。
