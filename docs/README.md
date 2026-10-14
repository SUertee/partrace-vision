# ParTrace Vision 设计文档

当前为待评审的设计草案：**PostgreSQL + pgvector、11 张表 / 77 个字段、27 个 API 操作**。接口统一使用 `/api/v1`；扫码入出库为 P1 拓展，在首轮视频核查闭环之后开发。

| 要查看的内容 | 入口 |
| --- | --- |
| 模块分工、开发顺序与验收 | [开发计划](DEVELOPMENT_PLAN.md) |
| 数据库表、字段与关系 | [数据库说明](database/README.md) · [建表 SQL](database/schema.sql) · [检索 SQL 示例](database/semantic-search.sql) |
| API、参数与返回 | [API 设计](api/API_DESIGN.md) · [OpenAPI](api/openapi.json) · [模拟样例](api/examples/v0.3/) |
| 功能如何对应表和 API | [功能对照](api/FEATURE_DATA_API_MAP.md) |
| P1 二维码与入出库 | [包裹设计](database/PARCEL_DESIGN.md) |
| 图与交互原型 | [在线工作台](https://yizhan-trace-workbench.vercel.app/) · [离线工作台](workbench/index.html) · [数据库图](database/schema-canvas.html) · [API 图](api/api-canvas.html) |

GitHub 可以直接阅读 Markdown、SQL 和 JSON。HTML 文件在 GitHub 显示源码；下载后可直接打开，或使用在线工作台。

## 目录与维护

```text
docs/
  README.md                  文档入口
  DEVELOPMENT_PLAN.md        分工、依赖与验收
  database/                  数据库设计、SQL、结构快照与关系图
  api/                       API 设计、OpenAPI、模块映射与样例
  workbench/                 完整离线工作台
```

数据库定义以 `database/schema.sql` 为准；结构快照来自真实 PostgreSQL 系统目录。API 字段以 `api/openapi.json` 为准，模块映射与样例保存在同目录。结构快照、HTML 图和内嵌工作台也一并提交，供团队下载查看；生成与同步工具放在 [scripts/](../scripts/)。当前样例为 `api/examples/v0.3/`，`archive/` 和 v0.2 样例仅供历史参考。

在仓库根目录可检查已保存设计与页面是否同步：

```sh
python3 scripts/export-schema-catalog.py --check
python3 scripts/build-schema-canvas.py --check
python3 scripts/sync-workbench-schema.py --check
python3 scripts/build-api-canvas.py --check
python3 scripts/sync-workbench-api.py --check
```

SQL 改动后先在隔离空库加载并重新导出结构，再生成页面，步骤见 [数据库说明](database/README.md#建表检索与验证)。上述离线检查不部署数据库，也不执行真实业务 API。

实际实现放在 `android/`、`backend/`、`agent/` 和 `vision/`；工作台是设计评审材料。开发与提交规则见 [AGENTS.md](../AGENTS.md)。
