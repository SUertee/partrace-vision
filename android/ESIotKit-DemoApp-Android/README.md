# ESIotKit Demo App — Android

ESIotKit 的 Android 示例工程（独立 Gradle 工程）。

> 公共模块：`../ESIotKit-DemoApp-Common/sample-core`（**需保持同级存放**）

## 快速开始

```bash
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk

./gradlew :app:installDebug   # 安装到已连接设备
```

登录凭证由赛务方现场发放，填入公共模块 `BusinessLoginConfig.kt` 后运行（详见 ../docs/PREREQUISITES-Android.md）。

详细说明见 [../docs/GETTING_STARTED-Android.md](../docs/GETTING_STARTED-Android.md)。

## 环境

JDK 17+、Android SDK（compileSdk 36 / minSdk 26）。依赖仅来自 `google()` / `mavenCentral()`；SDK 为工程内 `libs/ESIotKit-2.2.7.aar`（fat-AAR），无需任何私有仓库。
