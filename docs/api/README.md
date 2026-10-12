# 接口与数据设计草案

本目录约定 Android、后端、Agent 和视觉算法之间的数据交接。实际实现分别放在四个开发目录中。

| 资料 | 用途 |
| --- | --- |
| [API_DESIGN.md](API_DESIGN.md) | 端点清单、来源与状态、版本、导入、人工保存和开发顺序 |
| [openapi.json](openapi.json) | 可导入接口工具的 `0.2-draft` 请求、返回与错误定义 |
| [v0.2 模拟样例](examples/v0.2/) | 查询、缺口、保存与版本冲突样例；不包含真实媒体 |
| [数据库设计](../database/README.md) | 表结构、索引、关系、时间与幂等约束 |
| [离线关系图](../database/schema-canvas.html) | 下载 HTML 后双击，点表查看字段和关系，无需启动服务 |

依据 2026-10-12 读取的[技术方案 r51](https://q0ef12ao819.feishu.cn/wiki/RFLWwHFplicxpIk10bbcTBr6nqr) 与 [PRD r230](https://q0ef12ao819.feishu.cn/wiki/QHVvwxixBiJspmkoBD1cuU6InQc)。原文仍写管理员 Web，本草案按已确认的 Android 主端保持 API 端无关；匿名单镜、区域与时间检索、证据、人工复核的范围不变。

**状态：设计草案，待四人确认；没有运行后端，也未建生产数据库。** SQLite DDL 用于本地验证，主办方资源与生产引擎仍待确认。跨镜候选关联、复杂动作与向量检索作为后续增强。

API 时间统一为 ISO 8601 UTC，视频偏移用毫秒。来源、观测/候选状态、采集覆盖、索引完成度和媒体可用性分别记录；Agent 只读，管理员显式确认后保存。

原 [demo-bundle.json](examples/demo-bundle.json) 保留为 `0.1-draft` 历史讨论样例，不能直接当作 `0.2-draft` 的导入请求。样例接通不代表 D01 已冻结或真实业务已经验收。
