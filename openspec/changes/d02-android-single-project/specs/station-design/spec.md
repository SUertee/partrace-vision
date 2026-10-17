## MODIFIED Requirements

### Requirement: 视觉实现保持业务契约和验收边界

Android SHALL 保留设计壳样式、原 SDK 初始化及官方调试能力，并在单一 `android/` Gradle 工程内组织 `app` 宿主与 `core` 业务／UI 模块；允许在设计壳内接入已验证的 SDK 设备入口。消息、AI 和本地静音继续遵循各自来源边界。后续业务 SHALL 继续引用 [模块设计](../../../../../docs/modules/README.md)、[SQL](../../../../../docs/database/schema.sql)、[OpenAPI](../../../../../docs/api/openapi.json) 与 [当前样例](../../../../../docs/api/examples/v0.3)，首页 SDK 接入 SHALL 不新增或改变 DB/API。

#### Scenario: 记录交付凭据
- **WHEN** 本次构建、UI 检查、真实设备列表／缩略图服务或摄像头操作完成
- **THEN** SHALL 分别记录实际版本、环境、来源、结果与未验证项，不用模拟器／构建成功代替真机／摄像头验收，不据此宣布 D02/D06/D08/D10 业务闭环全部完成或自动关闭 Issue

#### Scenario: 从统一工程构建应用
- **WHEN** 开发者打开 `android/` 或使用该目录的 Gradle Wrapper 构建
- **THEN** SHALL 通过工程内的 `:app` 与 `:core` 完成既有 Android 构建，不依赖旧 Demo 同级目录

#### Scenario: 迁移后保留运行与配置身份
- **WHEN** 现有源码、运行资源、本地 SDK 与开发配置迁入统一工程
- **THEN** SHALL 保留应用 ID、namespace、Kotlin 和生成资源包、SDK版本及配置值，私有配置继续被 Git 忽略且开发 API 凭据仅进入 debug 资产，不因目录迁移改变业务行为
