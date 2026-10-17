## Why

用户明确指出，继续直接使用官方 Android／Common 两个外层 Demo 工程不符合本项目的代码组织需求。上一轮只整理了页面和资料，仍保留旧工程结构。本次按用户最新决定，将 Android 应用整理成可直接打开的单一 Gradle 工程，替代此前保留两个同级官方目录的约定。关联 D02 / Issue #2，依赖已实现的 Android 页面与 SDK／Agent 接入。

## What Changes

- `android/` 成为唯一 Gradle 根，根工程命名 `ParTraceVision`，模块为 `:app` 与 `:core`；不再通过工程外相对路径引用官方 Common 目录。
- `app/` 保留应用宿主，`core/` 保留既有 Compose 页面、业务协议与 SDK 适配及 KMP 源集；Wrapper、版本目录、SDK 和本地参数归入同一工程根。
- 更新配置准备脚本、忽略规则、运行路径、仓库约定与源码索引；先保护新私有路径再迁移，保留凭据、AAR、源码及本地历史构建／IDE 数据。
- 保持应用 ID、Kotlin 包名、namespace、生成资源包、SDK 二进制及依赖版本；沿用现有应用数据与接口合同。

## Capabilities

### Modified Capabilities

- `station-design`：将两个官方同级工程的结构要求改为单一 Android Gradle 工程，并规定本地配置、资源与运行身份的兼容边界。

## Impact

涉及 Android 构建与目录、两个本地配置脚本、Git 忽略规则、文档及结构规格。业务页面正文、Backend／Agent／Vision、SQL／OpenAPI 不随迁移改变。不包含升级依赖、改应用 ID、接入 iOS、安装 APK、设备操作或摄像头与模型重新验收。
