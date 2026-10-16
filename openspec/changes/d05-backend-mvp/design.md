## Context

现有 Backend 仅提供配置、健康探测及可选 Agent router 挂载点。此次在同一个 Python + FastAPI 应用落地数据操作，保持 [现有 27 个 API](../../../docs/api/openapi.json) 与 [11 表 SQL](../../../docs/database/schema.sql) 不变。十二个实现操作以 [Backend README](../../../backend/README.md) 为入口；其余十五个仍待实现。

## Goals / Non-Goals

目标是固定视频的完整结果包原子发布、按已授权范围读取真实证据、管理员保存人工决定及重启回读。数据库连接或配置出错须明确失败，不能回退成内存或模拟成功。

本轮不实现 Agent、向量编码／查询、包裹、正式登录和多租户管理，不改变已确认的事实不可覆盖、核查 append-only 及稳定 ID 幂等规则。

## Decisions

1. **PostgreSQL 是唯一业务存储。** 一次录像导入在单事务写入视频、事件及关键帧，幂等重放比较规范内容；失败整体回滚。人工核查只保存管理员显式决定，身份和时间由服务端填充。
2. **本地静态鉴权仅保存摘要。** 真实令牌由准备脚本随机生成到被忽略且权限受限的本地文件。API 通过摘要匹配映射 user_id／scope／录像或镜头授权，再查数据库用户 active／role；示例不含可用凭据，日志不打印令牌或 DSN。production 不启用这一引导方案。
3. **配置在启动时成为固定快照。** 配置版本文件及其规范 SHA-256 pin 必须一致，重复版本或校验失败拒绝启动。运行请求不读取被覆盖的新内容；后续更新通过新版本和重启完成，不覆盖录像引用版本。
4. **媒体与业务行分开。** 数据库保存 storage_key，服务在受限媒体根目录验证实际文件；拒绝路径穿越及逃逸的符号链接。没有文件的关键帧返回明确缺失状态，不生成占位图冒充真实证据。
5. **初始化是 development 显式管理操作。** `init-db` 只连接设置中明确的 PostgreSQL URL，校验当前 schema.sql 外层 BEGIN／COMMIT 后去掉该包装，在一个显式事务内取得 advisory transaction lock、检查 public schema 无业务关系、执行原 DDL。已有库拒绝；并发第二个命令在首个完成后重新检查，不能重复建表。不增加迁移表，也不由应用启动触发。
6. **种子用户只首次插入。** `seed-user` 同样只用于 development；参数化插入 active 用户，created_at 为服务端 aware UTC，角色仅 admin／viewer。重复 user_id 返回明确冲突，不使用 UPSERT 改写用户资料、角色或历史归属。

## Risks / Trade-offs

- 静态 Bearer 适合受控本地联调，不能代替正式认证和令牌生命周期；生产禁用，后续正式身份体系另行提案。
- 本地文件媒体不代表对象存储和下载凭据方案已完成，当前只验证根目录内已有文件。
- 全量执行现有 SQL 会创建十一张表，其中向量／Agent／包裹表可为空；空表不表示相应服务已实现。
- 单元测试和模拟样例只验证实现行为；真实 PostgreSQL 集成、真实视频来源、Android 与 Agent 联调须分别记录。

## Migration Plan

这是空 development 数据库首次建库，不是迁移既有库。遇到已有 public 关系、DDL 错误或缺少扩展权限时整笔回滚，管理员须使用独立空库解决，不自动删除、覆盖或重建。服务启动只验证依赖，不自动执行 DDL。

## Open Questions

正式认证、对象存储、部署配置和 Agent 执行器均留待对应工作包；本 change 的验证记录不得将未实现能力标为接通。
