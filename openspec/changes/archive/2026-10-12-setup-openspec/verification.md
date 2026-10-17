# 接入验证记录

日期：2026-10-12（Asia/Singapore）。环境：Windows PowerShell、Node.js 24.18.0、OpenSpec 1.14.1。输入仅为仓库文档、官方生成入口和规格提案，无真实媒体或用户数据。

## 本次执行

| 命令或操作 | 结果 |
| --- | --- |
| npm install -g @fission-ai/openspec@1.14.1；openspec --version | 安装成功，版本 1.14.1 |
| openspec init --tools codex,claude --profile core --language Chinese | 生成两组各 6 个 skills、Claude 6 个 commands 及项目配置 |
| openspec init --tools codex,claude --profile core，再比较 SHA256 | 重复初始化成功；生成入口及已有项目配置均未变化 |
| openspec instructions tasks --change setup-openspec --json | 中文 context 与 tasks rules 被正确读取 |
| openspec validate --all --strict --no-interactive | setup-openspec 与 d01-shared-contracts 两项通过 |
| openspec validate --archived --no-interactive（归档前） | 成功，无既有归档 |
| 使用 OpenSpec 自带 yaml 库解析配置和 Actions YAML | 无语法或重复键错误；只读权限与 dev PR 触发配置可核对 |
| 本地 Node 检查手写 Markdown 相对链接 | 29 个目标存在；未验证网页可访问性和锚点 |
| git diff --check | 通过 |
| openspec archive setup-openspec --yes | 工具接入归档成功，不写入业务规格基线 |
| 归档后重跑 validate --all --strict 与 validate --archived（均 --no-interactive） | D01 活跃提案 1 项、工具接入归档 1 项分别通过 |
| 归档后重跑相对链接检查 | 29 个目标存在 |

Windows 实际调用使用 npm.cmd / openspec.cmd；表中省略后缀以便其他系统阅读。CLI 验证使用会话环境变量 OPENSPEC_TELEMETRY=0。

## 验证边界

以上不包括 Android 构建、真机/SDK、模型、业务服务、真实素材或 D10。未在 Claude Code / Codex 新会话里执行完整业务工作流；已核对生成入口与 CLI 行为，编辑器加载仍需实际会话确认。远端 CI 以提交后的 PR Actions 结果为准。
