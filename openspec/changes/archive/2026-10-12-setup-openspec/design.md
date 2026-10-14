# Design

## Context

动机和范围见 proposal.md。仓库现有 AGENTS.md、D01-D10 与 GitHub Issues 已承担协作和进度管理；PR #12 正在评审 API/数据库草案。新工具须复用这些边界。

## Goals / Non-Goals

提供固定版本安装、两种 AI 工具入口、可审查的行为场景与 CI。此次不冻结接口、不实现业务、不改变仓库权限、不导入旧 Web 首版 README。

## Decisions

- 选择全局 CLI 1.14.1 和仓库内生成入口；不为 Android 仓库新建 npm 应用。成员与 CI 使用同一明确版本，升级通过单独 PR。
- 选择默认 spec-driven 与 core profile，避免首轮维护自定义 schema。配置规则仅为助手指导，不声称它们都能被 CLI 自动强制检查。
- 共享规则保留在 AGENTS.md，CLAUDE.md 只提供链接；docs/api/ 保存字段，OpenSpec 保存行为场景；Issue 保存进度。
- setup-openspec 采用 skip_specs，只记录工具接入；D01 是单独活跃提案，tasks 保持未完成。待团队确认后的文档契约基线可归档，业务实现仍由 D04-D10 验收。
- 提交 CLI 生成的 .agents / .claude 指定入口及所有权标记，个人配置不入库。无需修改全局 Agent 配置。
- CI 以只读权限在 dev/main 的 PR、push 及手动运行时检查现行规格与归档任务；不增加自动合并或必需检查设置。

## Risks / Trade-offs

- 生成文件较多：保留官方原样，由固定版本生成；手写约定集中在配置和文档。
- 未冻结契约被误当成成品：D01 提案明确待评审，并保留未完成任务；不关闭 #1。
- 格式通过被误作业务成功：文档、PR 模板和工作流名称明确检查边界，实测另附凭据。

## Migration Plan

从最新 dev 建独立分支，安装并初始化，补齐文档和配置，校验后推送并向 dev 开 PR。成员拉取后安装同版本 CLI 并检查 list/status。升级时固定新版本、重新生成两种入口并检查 diff。需要撤回时在新 PR 中撤回工具接入改动，保留后续已形成的规格与历史；不修改业务数据。
