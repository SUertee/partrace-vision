# Proposal

状态：待团队评审；本提案不代表协议冻结或业务实现。

## Why

D01 需要四模块对同一事件与证据使用一致的语义，并能通过具体场景核对交接。本次关联 [Issue #1](https://github.com/SUertee/partrace-vision/issues/1)，以已进入 `dev@4d8e621` 的 [模块设计](../../../docs/modules/README.md)、[数据库设计](../../../docs/database/README.md) 与 [0.3-mvp-draft API](../../../docs/api/API_DESIGN.md) 为评审输入；[PR #12](https://github.com/SUertee/partrace-vision/pull/12) 保留为来源。进入 dev 不代表草案已获四方确认。

## What Changes

- 对齐首轮场景：ID／时间／来源、完整录像查询、证据可用性、导入与人工核查幂等、描述向量检索、AI 本人历史、引用、停止与重启。
- 通过 [场景关联表](scenario-map.md) 连接 Requirement／Scenario、Mxx 模块、API、数据表和当前样例；HTTP 字段以 OpenAPI 为准，持久化字段以 SQL 为准，不在规格中维护第二份字段表。
- 当前设计共 11 表、27 个草案操作；D01 首轮围绕 M01–M08 的 22 个操作确认。M09 SDK 独立验证，M10 包裹五接口保持 P1、D10 后实施，后续单独提案。
- 固定素材、三条查询及四方确认完成后才形成 D01 契约基线；五个 proposed 接口与运行历史列表候选仍待评审。

## Capabilities

### New Capabilities

- `shared-contracts`: 四模块交接契约必须规定的行为与可审查场景。

### Modified Capabilities

无，当前尚无已归档业务规格。

## Impact

工作包为 D01，Harry Xu 牵头，Android、backend、agent、vision 四方确认。受影响调用方为算法导出、后端导入与查询、Android 页面和 Agent 工具。存储采用 PostgreSQL + pgvector；Python + FastAPI 中 Backend 与 Agent 分模块、首版共用应用，Vision 独立处理。实际实现由 D04、D05、D07、D08、D09 交付，D10 验收。本 change 只对齐待确认规格，不实现服务，不改变 SQL／OpenAPI 字段，不把模拟样例计为真实能力，不引入持续直播、Web 或跨镜功能。
