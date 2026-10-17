# Proposal

状态：联动设计草案，待 Backend／Agent 确认；没有实现或冻结接口。

## Why

[D05 / Issue #5](https://github.com/SUertee/partrace-vision/issues/5) 与 [D07 / Issue #7](https://github.com/SUertee/partrace-vision/issues/7) 已确定职责，但同一 FastAPI 应用内的模块注入、只读事实工具、AI 历史存储和异步运行需要具体交接。当前评审基于 dev@27d5f85 的 11 表、27 个公开 API 草案及活跃 D01。

## What Changes

- 在 [联动说明](../../../docs/modules/BACKEND_AGENT.md) 定义 Backend 提供的认证、只读事实与 AI 历史接口，Agent 负责 router、计划、模型和执行器。
- 定义确认计划、幂等运行、原子完成、停止竞争和单执行实例的重启恢复场景。
- 公共字段仍引用当前 SQL／OpenAPI，不增加 API、表、运行状态或独立服务；P1 包裹只读后置。

## Capabilities

### New Capabilities

- `backend-agent-integration`: 同一应用内的受限依赖注入与分析运行交接。

### Modified Capabilities

无；当前共享契约仍为待团队确认的活跃提案。

## Impact

Harry Xu 提供 Backend 数据与事务服务，韩广宁实现 Agent 工作流，李果钦按现有八个 Agent API 接 App。影响 M04／M05／M06，D08 联调和 D10 验收；依赖 D01 范围确认。运行历史列表、SSE、多实例租约与真实模型选择不在本次定义范围。
