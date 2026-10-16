# D07 · 受限业务 Skill 调用

## Why

关联 D07／Issue #7，协作 D05／Issue #5；影响 M04、M05、M06。现有执行图只暴露五个只读工具，没有可发现和按需加载的业务指引，语义编码不可用还会被收敛为整轮执行失败。

## What Changes

在 Agent 内增加固定白名单的业务 skill 目录及 `load_skill`，指导事件核查、语义候选检索与录像来源读取。只加载随代码发布的指引，不读取用户路径或全局技能，不增加 Shell、SQL 或业务写入。语义编码明确不可用时返回安全的 unavailable 状态，允许调用现有普通查询；权限、范围、存储和未知错误仍终止。

## Impact

依赖现有 ToolGateway、create_agent、运行预算和引用账本。公开 API、SQL、卡片结构与 Android 调用保持兼容；依据见 [模块](../../../../docs/modules/BACKEND_AGENT.md)、[API](../../../../docs/api/openapi.json)、[SQL](../../../../docs/database/schema.sql)。外部模型成功、真实事件引用与 D10 不属于本次离线验证。
