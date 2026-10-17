# Proposal

## Why

四模块已经有工作包与职责，需要把行为场景和变更记录衔接到现有协作中，并为成员提供可复现的工具安装入口。

## What Changes

- 固定 OpenSpec 1.14.1，生成 Codex / Claude Code 的项目入口，使用默认 spec-driven schema。
- 编写 docs/OPENSPEC.md，更新 README、AGENTS、CLAUDE 入口和 PR 模板。
- 配置中文项目上下文、产物规则及规格检查 CI。
- 添加 d01-shared-contracts 待评审提案，关联 Issue #1 和 PR #12；本 change 只交付该提案，不执行其任务。

## Capabilities

### New Capabilities

无。本次为开发工具与文档接入，使用 skip_specs: true。

### Modified Capabilities

无。D01 契约约定独立评审，四模块业务实现不在本 change 范围。

## Impact

关联 D01 开工约定：[Issue #1](https://github.com/SUertee/partrace-vision/issues/1)，衔接 [PR #12](https://github.com/SUertee/partrace-vision/pull/12)。新增 Node.js >=20.19.0 的开发工具要求，不改变 Android 构建依赖。GitHub Actions 只运行规格检查，不修改分支保护，不自动关闭业务 Issue。
