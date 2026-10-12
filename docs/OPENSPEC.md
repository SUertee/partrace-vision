# OpenSpec 安装与开发流程

ParTrace Vision 使用 OpenSpec **1.14.1**、默认 `spec-driven` schema 和 `core` 工具入口，补充行为规格与变更记录。Android 主端、D01-D10、四人职责和真实验收口径沿用现有约定。

## 文档分工

| 位置 | 负责内容 |
| --- | --- |
| [AGENTS.md](../AGENTS.md) | 团队与 AI 助手共用开发规则 |
| [开发计划](DEVELOPMENT_PLAN.md) | 范围、工作包、依赖、交付和验收 |
| [GitHub Issues](https://github.com/SUertee/partrace-vision/issues) | 实际进度、阻塞和凭据 |
| [docs/api](api/README.md) | 共享字段、接口及 JSON 样例 |
| [openspec/config.yaml](../openspec/config.yaml) | 项目背景、产物规则及执行指导 |
| `openspec/changes/<name>/` | 当前提案、行为增量、设计与任务 |
| `openspec/specs/` | 已接受的规格基线 |
| `openspec/changes/archive/` | 已完成变更的历史记录 |

规格说明应满足什么约定，不能独立证明代码存在或验收通过。文档契约基线与业务实现分别交付；接口字段只在 docs/api 维护，规格引用它。CLI 的格式校验不会自动验证 config 中每条自然语言规则。

## 成员安装

要求 Node.js >=20.19.0；本次使用 Node.js 24.18.0。OpenSpec 是开发工具，不改变 Android 的 JDK、SDK 和 Gradle 要求。

在已拉取本接入改动的仓库根目录，Windows PowerShell 逐条执行：

```powershell
node --version
npm.cmd install -g @fission-ai/openspec@1.14.1
openspec.cmd --version
openspec.cmd list
openspec.cmd validate --all --strict --no-interactive
openspec.cmd validate --archived --no-interactive
```

`--version` 应输出 `1.14.1`。macOS/Linux 使用 `npm`、`openspec`，去掉 `.cmd`。Windows 使用 `.cmd` 可避免 npm 的 PowerShell shim 受执行策略限制，无需修改系统执行策略。

仓库已经提交 Codex 和 Claude Code 的项目入口，成员通常无需再次运行 init 或 update。若助手尚未发现新 skills，重新打开项目或会话；CLI 能运行不代表编辑器已经加载入口。Codex 桌面端可从 Skills 选择 `openspec-propose`，也可明确请求助手使用该技能。

找不到 CLI 时，先检查 `Get-Command openspec.cmd` 和 `npm.cmd prefix -g`，重新打开终端使已有 PATH 更新生效。也可使用固定版本替代调用，例如 `npx.cmd --yes @fission-ai/openspec@1.14.1 --version`，不要因此改动系统权限。

OpenSpec 默认收集匿名使用统计；可选在当前 PowerShell 会话设置 `$env:OPENSPEC_TELEMETRY='0'`。本次验证和 CI 使用该环境变量，不写全局 Agent 配置。

## 首次初始化与工具入口

以下是维护者首次接入的步骤，已有项目无需重复执行：

```powershell
git status --short --branch
git fetch origin
git switch -c chore/openspec-setup origin/dev
openspec.cmd init --tools codex,claude --profile core --language Chinese
git status --short
git diff --check
```

保留已有修改，确认分支名未占用后再创建。初始化生成的文件由 OpenSpec 管理，项目规则另写在 AGENTS.md 和 config.yaml；不要手改生成内容。

| 工具 | 生成位置 | 发起提案 | 执行任务 | 归档 |
| --- | --- | --- | --- | --- |
| Codex | `.agents/skills/openspec-*/SKILL.md` | `$openspec-propose` | `$openspec-apply-change` | `$openspec-archive-change` |
| Claude Code | `.claude/skills/openspec-*`、`.claude/commands/opsx/` | `/opsx:propose` | `/opsx:apply` | `/opsx:archive` |

表中入口在 AI 对话或技能选择器中调用，不能直接当作终端命令；以初始化输出与工具实际加载结果为准。core 还包含 explore、update-change、sync-specs；verify 等扩展入口不在本次配置中。

只提交上述 OpenSpec 生成文件、`.agents/skills/.openspec-target`、项目配置与规格。个人 `.claude/settings.local.json`、会话、凭据不提交。当前版本不自动生成团队 AGENTS.md，CLAUDE.md 由本仓库维护为共享规则入口。

## 日常开发

1. 从最新 dev 建单项任务分支，读取工作包、Issue、模块 README、接口契约和相关规格。
2. 新功能、接口或业务语义变化先提出 change，说明范围、依赖与影响；提案命名如 `d07-stop-run`。文档纠错等小修复可直接 PR，并说明无需 change。
3. 在 AI 对话中发起提案。默认产物为 `proposal.md`、`specs/<capability>/spec.md`、`design.md` 和 `tasks.md`；共享边界明确后按任务实现。纯工具/文档 change 可在 `.openspec.yaml` 声明 `skip_specs: true`，不为通过校验编造业务要求。
4. 涉及契约时，同一 PR 更新 docs/api、样例与受影响规格；说明调用方和兼容方式。任务完成一项再勾选一项。
5. 执行规格检查及模块实际验证，记录环境、输入来源、结果、失败与未验证项；PR 关联 Issue 和 change。
6. 完成本 change 自身的交付后，在任务分支归档，将归档与最终代码一起提交评审；归档操作不表示 PR 已合并。团队尚未确认的契约保持活跃，禁止提前勾选或归档。

以当前 D01 提案为例，终端检查：

```powershell
openspec.cmd status --change d01-shared-contracts
openspec.cmd validate d01-shared-contracts --strict --no-interactive
openspec.cmd validate --all --strict --no-interactive
openspec.cmd validate --archived --no-interactive
git diff --check
```

`status` 显示产物是否齐全；`validate` 检查格式及规格增量；`--archived` 检查归档任务是否全部勾选。三者都不证明测试通过或业务可用。

确认某 change 已完成后，使用 `openspec archive <change-name>`（Windows 为 `openspec.cmd`）；自动化需要显式 `--yes`。归档前验证，归档后再次运行上述全量检查并检查 diff，修正因目录移动而变化的文档链接。不要对仍待评审的 D01 执行归档。

## 首个 D01 提案

当前 [d01-shared-contracts](../openspec/changes/d01-shared-contracts/proposal.md) 关联 [Issue #1](https://github.com/SUertee/partrace-vision/issues/1)，并衔接 [PR #12 的接口与数据库草案](https://github.com/SUertee/partrace-vision/pull/12)。它是待评审的契约规格，不代表接口冻结、服务运行或 D01 完成。该 change 归档后，可通过 `openspec list --specs` 和 archive 目录查找最新位置。

先确认 PR #12 的版本和差异，再让四方确认 ID、时间、来源、覆盖与索引状态、证据失效、幂等、引用与停止约定；不在 OpenSpec 重抄字段表或 JSON schemas。契约确认后的实现分别落在 D04、D05、D07、D08、D09，D10 继续使用同一真实事件验收。

## CI 与升级

[OpenSpec 工作流](../.github/workflows/openspec.yml) 对目标为 dev/main 的 PR、dev/main push 及手动运行执行固定版本的规格与归档任务检查。它不构建 Android、不验证真实数据，也不修改分支保护或把自己设为必需检查。远端是否通过以该 PR 的 Actions 记录为准。

升级应单独开 PR：选定具体版本，更新此文档、AGENTS 和 CI 中的版本，安装该版本，再用 `init --tools codex,claude --profile core` 重新生成本项目入口并核对差异、重跑验证。此处不传 `--language`，保留现有 config。普通 `update` 会采用当前用户的 profile/delivery 设置，不能假设每个成员都一致；不要顺手改全局 profile。

生成入口可重新生成，但 specs 与 archive 是项目历史，需要保留。卸载本机 CLI 使用 `npm.cmd uninstall -g @fission-ai/openspec`，不会删除仓库文档。

## 官方依据

- [1.14.1 CLI 命令](https://github.com/Fission-AI/OpenSpec/blob/v1.14.1/docs/cli.md)
- [1.14.1 工具入口与调用方式](https://github.com/Fission-AI/OpenSpec/blob/v1.14.1/docs/supported-tools.md)
- [1.14.1 项目配置](https://github.com/Fission-AI/OpenSpec/blob/v1.14.1/docs/customization.md)
