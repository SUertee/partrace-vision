# Android 应用宿主

本模块由 [统一 Android 工程](../README.md) 的 `:app` 构建，依赖同工程 `:core`。这里维护应用入口 Activity、Manifest、Android 资源与打包设置；业务页面和 SDK 适配在 [core](../core/README.md)。

Android Studio 打开上一级 `android/`，在该目录使用 `./gradlew :app:assembleDebug`；APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。本地 SDK 位于 `../libs/`，配置与授权说明见 [开发说明](../README.md)和 [第三方来源](../../THIRD_PARTY_NOTICES.md)。

目录迁移保持现有 applicationId、namespace、Activity 与依赖版本，仍可沿用此前安装应用的数据；是否实际安装和运行以验证记录为准。
