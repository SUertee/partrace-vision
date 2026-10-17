# Design

## Context

首版已选择 Backend／Agent 分模块、共用 Python + FastAPI 应用。Agent 目录尚无正式实现；本地待提交 Backend 骨架只有健康探测与显式 router 挂载点，不能证明业务接通。本 change 交付设计与后续工作清单，不提交并行开发中的代码。

## Goals / Non-Goals

形成可以独立实现并注入的最小内部接口，先跑通一个问题、一个确认计划、一次只读检索、一次完整答复和重启回读。公共 API 与 SQL 保持原草案；不实现模型、数据库仓储或独立队列服务。

## Decisions

- Backend 管身份与权限、事实工具、数据库连接和 AI 历史事务；Agent 管 HTTP router、计划、模型与受控任务。Agent 不获得数据库连接或任意写入接口。
- 同进程经 Python 受限接口调用，不绕回本机 HTTP；Backend 统一添加 `/api/v1/agent` 前缀。
- draft plan 不落表；运行创建重新验证最终确认计划及当前权限，保存后所有工具限定在该运行的 `plan.query`，不虚构已保存的初始 scope。
- App 提交稳定 run_id，先持久化 running 再返回首次 202；重放 200，不同内容 409。任务启动失败不得遗留无执行的 running。
- completed 与唯一完整助手消息同事务，停止／完成竞争同一 running 状态。重启旧 running 变 failed，不自动续跑。
- 首版按单执行实例管理内存任务注册表；多实例需要后续执行归属或租约设计，不能启动时无条件终止其他实例的运行。

## Risks / Trade-offs

进程内任务不能保证服务重启后继续执行，当前用明确失败保留历史；单实例不承担横向扩展承诺。模型、编码维度和媒体存储待确认；模拟适配器与真实提供方必须可区分。

## Migration Plan

按 [联动说明](../../../docs/modules/BACKEND_AGENT.md) 先确认内部签名与错误，再交 Backend 服务、Agent router／执行器、同一事件联调。原 D01 不关闭；新增公共路由、表或分布式恢复另开 change。
