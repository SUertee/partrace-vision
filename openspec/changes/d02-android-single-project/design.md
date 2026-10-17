## Context

旧 Gradle 根通过 `projectDir` 引用工程外的共享模块，源码、构建入口和本地 SDK 分散在两个官方 Demo 目录。用户最新要求覆盖先前的保留约定，需要迁移实际构建关系。原工作区含大量未提交实现和私有开发配置，必须逐项保留。

## Decisions

- Android 根直接包含 `app/`、`core/`、`gradle/`、`libs/`、配置模板、设计资料及 Wrapper；应用依赖 `project(":core")`。根项目为 `ParTraceVision`，不新增独立 frontend 或重复源码。
- 保留 `core/src/commonMain`、`androidMain`、`androidDebug`、`androidUnitTest` 和 `commonTest` 的模块内相对映射，保留 Android 平台开关及本地 fat-AAR 的已有构建限制。此次不将 KMP 源集改写成单平台工程。
- 所有 Kotlin 包名、应用 ID、库 namespace、Compose Res 包和 Activity／Preview 全限定名保持。SDK 从统一根的 `libs/` 消费；未启用 iOS 分支的 framework 路径也保持原指向，不声称支持 iOS 构建。
- 根忽略规则先添加新 `core` 私有配置路径并保留旧路径保护。真实配置和 AAR只迁移，不重写值；脚本继续保留校验、拒绝意外覆盖和 debug 限定行为。
- 迁移前记录源码、配置和二进制哈希；旧 build、Gradle／Kotlin 缓存及 IDE 设置保留在忽略的本地迁移备份，从新根重新生成构建产物。旧真实画面等构建输出随备份保留，不删除。
- 当前文档链接与运行指令改用新路径。历史实际执行的旧命令保留并注明历史；第三方说明记录当前目录与上游工程来源。AGENTS 的旧同级约定按本次用户决定更新。

## Verification

从 `android/` 运行 `:core` 的现有 Agent／SDK 排序相关目标测试、`:app:assembleDebug`、`:app:lintDebug` 及 `:core:mergeReleaseAssets`；确认 debug 凭据未进入 release 资产。验证配置脚本保留现有文件、忽略保护、迁移哈希与源码／资源身份、模块列表和新 APK。检查链接、OpenSpec、五项文档同步及 diff。此次不安装 APK或操作模拟器，构建成功不代替真机或业务验收。
