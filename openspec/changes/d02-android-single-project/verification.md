# 单一 Android 工程迁移验证

2026-10-17，用户最新决定将原官方两个外层目录整理为一个 `android/` Gradle 工程。本记录仅对应此次结构迁移；此前界面、摄像头与模型实测继续按各自历史凭据解释。

## 迁移与兼容

实际目录现为 `android/app`、`android/core` 及根构建配置。迁移前后 470 个非构建文件已逐一校验 SHA-256 和文件模式，原样移动；随后仅按迁移设计调整 Gradle 路径、说明注释、模块文档及忽略规则。原 build、Gradle／Kotlin 缓存和 IDE 配置保留在被忽略的 `.local-state/android-layout-migration-20261017/`，包括旧真实画面等构建输出；新根从空项目缓存重新构建。

应用 ID、Kotlin／资源包、namespace、SDK 版本、私有配置值与源集映射保持。新私有路径先保护再移动，原路径忽略保护同时保留，未打印或复制凭据到公共资料。

## 本次检查

在新的 `android/` 根使用 JDK 17.0.2 运行：

```sh
./gradlew projects :core:testDebugUnitTest --tests '*TraceAgent*' --tests '*SdkDeviceOrder*' :app:assembleDebug :app:lintDebug :core:mergeReleaseAssets :app:mergeReleaseAssets --console=plain
```

- **构建成功，2 分 4 秒。** `projects` 确认根名 `ParTraceVision`，仅含 `:app`、`:core`；118 项任务为 83 executed、35 from cache，项目本地缓存为迁移后重新生成。
- **12 项目标测试通过**：Agent 合同 4 项、Agent 网络 5 项、SDK 设备排序 3 项；0 失败、0 错误、0 跳过。未选择真实模型／AWS smoke 测试。
- **lint 0 Error/Fatal、133 项 Warning**，未借目录迁移修改既有依赖或扩展 lint 范围。
- `core`、`app` 的 release 资产合并均成功，release 输出无 `partrace-agent.json`；新 Debug APK 正常包含限定 debug 的 bootstrap 资产。此项检查不构建或发布 Release APK。
- 新 APK 位于 `android/app/build/outputs/apk/debug/app-debug.apk`。Manifest 仍为应用 `com.anker.esiotkit.demo`、44／2.2.7，Application／Activity／FileProvider 与资源命名空间保持；Kotlin 包名未随目录改动。
- 470 个原文件均保留、文件模式未变。后续变化仅限 11 个迁移相关构建配置、模块说明、忽略规则和 smoke 运行注释；其余文件（包括运行源码、配置、媒体和 SDK）哈希不变。
- 5 条新私有配置路径与旧路径均受忽略保护，AAR及 `local.properties` 继续被忽略。两个准备脚本静态编译与非主入口导入通过；实际执行 `python3 scripts/setup-android.py` 保留三份现有配置、找到并校验新路径的 SDK，未覆写配置。
- 当前文档和源码链接已重算，历史命令附迁移说明；新 OpenSpec change 严格校验、受影响既有 change 检查、`git diff --check` 和设计文档五项离线同步检查通过。离线同步不代表重跑数据库业务验收。

本轮未安装 APK、操作模拟器或调用模型／摄像头；未复验真机、页面交互、SDK 设备效果和业务闭环。iOS 分支仅保留路径兼容，当前仓库没有 iOS 工程或运行验收。

## 开发入口

Android Studio 直接打开仓库的 `android/`，使用这里的 `gradlew`。页面位置见 [源码索引](../../../android/README.md#页面源码索引)。本次决定替代此前要求保留两个官方同级目录的约定；上游工程名继续作为 [来源](../../../THIRD_PARTY_NOTICES.md)保留。
