# ESIotKit Demo App — Common（公共模块）

两端 Demo 共享的业务与 UI 模块（Kotlin Multiplatform）。

```
sample-core/
├── src/commonMain/     # 共享业务与 Compose UI（登录/设备/配网/直播/事件/设置/AI Demo）
├── src/androidMain/    # Android 平台 actual 实现
├── src/iosMain/        # iOS 平台 actual 实现
└── src/iosArm64Main/   # iOS（arm64）补充实现
```

## 被两端工程消费的方式

- **Android**：`ESIotKit-DemoApp-Android/settings.gradle.kts` 通过相对路径引入（`../ESIotKit-DemoApp-Common/sample-core`）；
- **iOS**：`ESIotKit-DemoApp-iOS/kotlin/settings.gradle.kts` 同样相对引入，并经 CocoaPods（`SampleCore.podspec`）打包为 `SampleCore.framework`。

平台开关：消费方工程通过 `gradle.properties` 的 `esiotkit.demo.platform=android|ios` 控制启用对应平台目标（未设置时两端全开）。

> ⚠️ 本目录需与 `ESIotKit-DemoApp-Android` / `ESIotKit-DemoApp-iOS` 保持同级存放。
