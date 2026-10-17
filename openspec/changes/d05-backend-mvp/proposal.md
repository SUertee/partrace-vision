## Why

当前数据库和 API 已有草案，但 Backend 尚未提供可持久化的事件／证据及人工核查闭环。D05 需要让 Android 和后续 Agent 读取同一事件；D09 需要让管理员显式保存核查后，重启仍能回读。健康探测、模拟输入和工作台展示不能替代这些运行能力。

本 change 对应 [D05 / Issue #5](https://github.com/SUertee/partrace-vision/issues/5) 与 [D09 / Issue #9](https://github.com/SUertee/partrace-vision/issues/9)，先实现既有契约中的十二个非向量、非 Agent、非包裹操作。它是最小实现提案，不代表 D05、D09 或 D10 已验收。

## What Changes

- 在现有 FastAPI 应用实现当前用户、不可变配置、录像导入／查询、事件与关键帧查询／取图、人工核查保存／回读。
- 使用真实 PostgreSQL 事务和现有十一张表；不使用内存数据回退，不修改 SQL／OpenAPI 的字段或操作数量。
- 首版本地鉴权使用受限配置文件中的 Bearer SHA-256 摘要；身份、角色及 active 状态来自数据库，读取仍验证资源授权，写入要求对应 scope 及角色。它不是正式账号登录系统。
- 启动时载入版本配置的不可变快照，本地媒体只按服务端 storage_key 在受限根目录读取；缺图不得伪造成功。
- 提供显式 development 管理命令：空库初始化、首次创建用户。启动不会自动建表，已有库不重建，已有用户不静默更新。
- 补充可复制的本地配置、启动、管理命令和测试凭据说明。

## Capabilities

### New Capabilities
- `backend-mvp`: 十二个既有数据操作的最小 PostgreSQL 实现、受限本地鉴权、配置／媒体边界和显式初始化。

### Modified Capabilities
- 无。字段权威仍是现有 SQL／OpenAPI，既有 `shared-contracts` 与 `backend-agent-integration` 不在本轮宣称完成。

## Impact

实现影响 `backend/`、本地准备脚本及运行配置；关联 [M01–M04、M07](../../../docs/modules/README.md)、[开发计划](../../../docs/DEVELOPMENT_PLAN.md)、[当前 OpenAPI](../../../docs/api/openapi.json) 和 [SQL](../../../docs/database/schema.sql)。

依赖 PostgreSQL 与可安装的 pgvector 扩展、获授权的配置／媒体和输入结果包。Agent 八个操作、向量两个操作、P1 包裹五个操作、自动迁移、正式登录、生产部署、实时推理、SDK 真机以及 D10 真实端到端验收均不包含。
