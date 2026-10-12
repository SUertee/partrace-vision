# Proposal

状态：待团队评审；本提案不代表协议冻结或业务实现。

## Why

D01 需要四模块对同一事件与证据使用一致的语义，并能通过具体场景核对交接。[Issue #1](https://github.com/SUertee/partrace-vision/issues/1) 已定义工作包，[PR #12](https://github.com/SUertee/partrace-vision/pull/12) 正在评审接口与数据库草案，需要在该草案上补齐并确认行为约定。

## What Changes

- 定义 shared-contracts 的验收场景：ID/时间/来源、覆盖与索引状态、证据可用性、幂等、人工核查、Agent 引用与停止。
- 将场景关联至 [共享接口文档](../../../docs/api/README.md) 的已评审版本及样例；字段和格式继续只在 docs/api 维护。
- 待 PR #12 评审整合、固定素材与查询确认、四人认可后形成 D01 契约基线。

## Capabilities

### New Capabilities

- `shared-contracts`: 四模块交接契约必须规定的行为与可审查场景。

### Modified Capabilities

无，当前尚无已归档业务规格。

## Impact

工作包为 D01，Harry Xu 牵头，Android、backend、agent、vision 四方确认。受影响调用方为算法导出、后端导入与查询、Android 页面和 Agent 工具。实际服务分别由 D04、D05、D07、D08、D09 交付，D10 独立验收。本 change 不实现服务，不替 PR #12 选择数据库引擎，不把模拟样例计为真实能力，不引入 Web 或跨镜功能。
