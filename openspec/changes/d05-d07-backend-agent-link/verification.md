# 联动设计检查

2026-10-14（Asia/Shanghai）。输入为 dev@27d5f85 的现有设计，以及对本地待提交运行骨架的只读检查；本次只定义 Backend／Agent 联动，不提交或执行并行开发中的代码。

- OpenSpec 1.14.1：两个活跃提案 strict 校验与一个历史归档校验通过。
- 现有五项文档离线 `--check` 通过：11 表／77 字段、27 个 API，SQL／OpenAPI／目录和工作台 HTML 保持原样。
- 联动说明、入口与提案相对链接及 Mxx 锚点通过，`git diff --check` 通过。
- 核对现有 AgentPlanRequest／AgentRunRequest／AgentRunResponse：App 稳定 run_id、首次 202、重放 200、冲突 409；未增加公开字段、路由、表或状态。
- 本地骨架仅有健康探测与 router 注入点，默认入口没有实际 Agent router；代码阅读不代表构建、运行、鉴权、数据库持久化或真实模型已通过。

全部实现与双方确认任务保持未勾选，活跃提案未归档。消息撤权、完成／停止竞争、真实证据、进程重启和 App 联调仍需按 D05／D07／D08／D10 实现并验收。
