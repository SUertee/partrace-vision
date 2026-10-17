# Android 业务与 UI 模块

本模块为统一工程内的 `:core`，由 `:app` 消费；包含驿站页面、SDK 原有调试能力、Agent／审核／人物录像协议及 Android 适配。页面修改位置见 [源码索引](../README.md#页面源码索引)。

```text
src/
├── commonMain/       # Compose UI、业务协议与共享逻辑
├── androidMain/      # Android 网络、存储、SDK 与平台 actual
├── androidDebug/     # 仅开发构建的本地资产，凭据被 Git 忽略
├── androidUnitTest/  # Android 本地单元测试
└── commonTest/       # 共享逻辑测试
```

沿用原 Kotlin Multiplatform 源集，当前仓库仅配置 Android 使用路径。根 `gradle.properties` 保留 `esiotkit.demo.platform=android`，未包含 iOS 工程、源集或 SDK 框架，不以保留的构建分支表示 iOS 可用。

从上一级 `android/` 执行 `./gradlew :core:testDebugUnitTest --tests '*TraceAgent*'` 运行现有目标测试。SDK 从根 `libs/` 消费；生成资源包及 Android namespace 保持既有值。运行、私有配置准备和来源授权见 [Android 说明](../README.md)及 [第三方说明](../../THIRD_PARTY_NOTICES.md)。
