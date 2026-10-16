# Backend 本地运行

主责 Harry Xu；当前本地实现包括 D05 事件证据与语义检索、D09 人工留档，并可显式组合 D07 Agent。使用 **Python 3.12 + FastAPI + PostgreSQL / pgvector**，图片和录像保存在本地受限目录，数据库保存媒体键。

本页运行说明对应已合入 `dev@4cc9aa1` 的源码；最新进展与本地尚未合入的能力统一见 [项目实际状态](../docs/PROJECT_STATUS.md)。本次只同步文档，People 原片任务、source-first 发布与 Android 新接入的源码继续单独交付。

源码按职责放在 `backend/` 的子包内，外层目录本身是 Python 包；启动入口为 `backend.app`，管理命令为 `backend.manage`。发行包名为 `partrace-backend`。以下命令均从 `backend/` 执行。

```text
backend/
  __init__.py
  app.py                 FastAPI 应用组装与生命周期
  manage.py              显式建库、创建本地用户的命令入口
  api/                   HTTP 路由与请求边界
    __init__.py          Agent 路由挂载
    facts.py             用户、配置、录像、事件、证据、核查接口
    semantic.py          向量写入与语义检索接口
    agent.py             会话、计划、运行、停止与 SSE 状态接口
    boundary.py          请求大小限制、request_id 与文档访问边界
  core/                  共用配置、身份与数据结构
    settings.py          私有环境配置与默认资源路径
    auth.py              凭据解析与认证主体
    configuration.py     版本化镜头、区域与 ROI 配置
    errors.py            统一业务错误
    models.py            Pydantic 请求与配置结构
  services/              业务查询、校验与事务
    facts.py             录像整包导入、事件查询与人工核查
    semantic.py          向量校验、写入和同空间检索
  storage/               数据库探测与媒体存储
    database.py          PostgreSQL／pgvector 就绪检查
    media.py             视频与图片验证、私有文件存取和清理
  ai/                    Agent 在 Backend 中的宿主功能
    runtime.py           后台任务、并发、停止与重启恢复
    gateway.py           只读工具的权限与范围校验
    history.py           会话、消息、运行的持久化事务
    providers.py         聊天模型和文本编码器适配
  tests/                 HTTP、业务与 PostgreSQL 集成检查
  scripts/               本地环境准备脚本
  examples/              无凭据的配置样例
  pyproject.toml         当前包及子包声明
  uv.lock                两个本地包的统一依赖锁
  Dockerfile             使用仓库根目录构建上下文
```

`api` 解析请求，`services` 处理事实业务，`storage` 处理数据库探测和媒体文件；`ai` 托管 Agent 的任务、权限与历史，独立 `agent` 包负责模型执行。新增源码放入对应子包，并同步打包清单和 Docker 白名单。`.venv / __pycache__ / .pytest_cache / build / *.egg-info` 是本地环境或生成产物，不属于源码分类；保留在 Git 忽略范围内。

[模块与 DB／API 关联](../docs/modules/README.md) · [OpenAPI](../docs/api/openapi.json) · [SQL](../docs/database/schema.sql) · [开发计划](../docs/DEVELOPMENT_PLAN.md) · [D05 验证](../openspec/changes/d05-backend-mvp/verification.md) · [D07 验证](../openspec/changes/d07-create-agent/verification.md)

## 默认十四个数据操作

| 功能 | 既有 API |
| --- | --- |
| 当前用户 | `GET /api/v1/user` |
| 不可变区域配置 | `GET /api/v1/configs/{config_version}` |
| 录像导入、列表及详情 | `POST /api/v1/recordings`、`GET /api/v1/recordings`、`GET /api/v1/recordings/{recording_id}` |
| 事件查询及详情 | `GET /api/v1/events`、`GET /api/v1/events/{event_id}` |
| 关键帧元数据及真实字节 | `GET /api/v1/evidence/{evidence_id}`、`GET /api/v1/evidence/{evidence_id}/media` |
| 人工核查保存、列表及回读 | `POST /api/v1/review-records`、`GET /api/v1/review-records`、`GET /api/v1/review-records/{review_id}` |
| 描述文本向量写入与语义检索 | `POST /api/v1/event-embeddings`、`POST /api/v1/events/search` |

基础数据的原有验证保留在 [D05 记录](../openspec/changes/d05-backend-mvp/verification.md)，向量与 Agent 的历史验证见 [D07 记录](../openspec/changes/d07-create-agent/verification.md)，后续联调见 [项目实际状态](../docs/PROJECT_STATUS.md)。数据库不可用时明确失败，不回退成进程内数据或模拟成功；服务重启后从 PostgreSQL 回读。`/health/live` 与 `/health/ready` 是运行探测，不计入 28 个契约业务操作。

`dev` 默认提供 12 个基础数据操作及两个向量操作，共 14 个；显式 `PARTRACE_AGENT_ENABLED=true` 再挂载 9 个 Agent 操作（含卡片与 SSE），共实现 23／28。契约中的 P1 包裹 5 个操作尚未实现。空库 DDL 创建十一张表，空表不代表功能接通。[Backend／Agent 联动](../docs/modules/BACKEND_AGENT.md)采用同一 FastAPI 应用内的 Python 接口；独立 `agent/` 包使用 `create_agent`，通过五个受限工具调用 Backend，不直连数据库。D06 摄像头 SDK 验证独立推进。

本地未合入的 People 管线增加七个任务操作，默认 Backend 21 个、启用 Agent 后 30／35；source-first 使用 `POST /recordings` 的 `mode=source` 先保存 pending 录像和任务，Vision 完成后原子发布事实与完成状态。其隔离回归及真实 A／B 进展见 [项目实际状态](../docs/PROJECT_STATUS.md)。`migrate-recording-jobs`、`migrate-source-recordings` 与 `retry-recording-job` 管理命令也仅在该本地实现中存在，须随对应源码合入后使用；本页下述命令仍对应当前 `dev`。

## 本地准备

以下命令从仓库的 `backend/` 目录执行，需要 uv 和 Docker。准备脚本的 `--start-db` 会新建独立的 `partrace-mvp-dev` 容器、持久化卷，并仅将 PostgreSQL 发布在 `127.0.0.1:54391`；不接触其他项目的容器和数据库。管理命令本身不创建数据库或账号。

```sh
cd backend
uv sync --locked
uv run python scripts/setup-local.py --start-db
```

两个项目的 setuptools 配置将 `backend`、`agent` 分别映射到各自项目根目录。`uv sync --locked` 安装当前 Backend 和 `../agent` 两个本地可编辑包，导入使用 `backend.app`、`agent.engine`；无需设置 `PYTHONPATH` 或传入 `--app-dir`。

准备脚本写入 `backend/.env` 和仓库 `.local-state/` 下的受限本地文件；已有文件拒绝覆盖。随机令牌只保存到 `.local-state/bootstrap-credentials.json`，命令只显示保存位置，不打印令牌。按本地引导文件中的身份创建数据库用户，实际密码、令牌及媒体不得提交 Git。

Agent／Embedding 配置按 [.env.example](.env.example)补充到已生成的私有 `backend/.env`，不要覆盖已有数据库和身份配置。模型适配器使用 OpenAI 兼容接口；API key 只从私有环境读取，不发到聊天、源码、业务请求或日志。缺 Agent 模型时计划／新分析返回 503，缺编码模型时语义检索返回 503 `model_unavailable`，不自动换成测试模型或模拟向量。真实模型的计划、完整运行与 Embedding 验收分别记录在 [项目实际状态](../docs/PROJECT_STATUS.md)。

已有独立空库时，改为通过环境提供 `PARTRACE_DATABASE_URL` 后执行不带 `--start-db` 的准备命令。脚本不创建业务表和用户。不要把密码直接写进可记录的终端命令或截图；本地环境文件及 `.local-state/` 被忽略。

| 设置 | 用途／默认位置 |
| --- | --- |
| `PARTRACE_ENVIRONMENT` | 本地使用 `development`；静态引导鉴权和管理命令不用于 staging／production |
| `PARTRACE_DATABASE_URL` | 必填 PostgreSQL URL，包含明确主机及数据库名；不提供 SQLite 或内存回退 |
| `PARTRACE_DATABASE_TIMEOUT_SECONDS` | 连接／语句等待边界，默认 5 秒 |
| `PARTRACE_AUTH_FILE` | 仓库 `.local-state/auth.json`：只保存令牌 SHA-256 摘要、身份及授权 |
| `PARTRACE_CONFIGS_DIR` | 仓库 `.local-state/configs/`：每个 JSON 文件直接包含一份配置对象 |
| `PARTRACE_CONFIG_PINS_FILE` | 仓库 `.local-state/config-pins.json`：版本到规范配置 SHA-256 的映射，独立于配置目录 |
| `PARTRACE_MEDIA_ROOT` | 仓库 `.local-state/media/`：已有真实文件的受限存放根目录 |
| `PARTRACE_SCHEMA_PATH` | 仓库 `docs/database/schema.sql`，只供显式初始化读取 |

空凭据模板为 [auth.example.json](examples/auth.example.json)。正式本地配置的每个 token 项包含 `token_sha256`、`user_id`、`scopes`、`camera_ids`，以及可选 `recording_ids`；没有通配符。省略 `recording_ids` 仍只授权列出的镜头。服务使用数据库用户的 active／role 状态，不相信请求中的操作人。

[config-demo.example.json](examples/config-demo.example.json) 来自既有模拟样例，仅演示 `demo_v1 / cam_demo_01 / E3`，不对应真实设备。配置与 pin 在本实例第一次配置访问时校验并成为内存固定快照；修改参数须增加版本，不覆盖已发布录像的版本。真实录像与关键帧通过 multipart 上传，服务分配私有存储键；不接受客户端路径。服务不会生成占位图冒充证据。

## 显式建库与创建用户

```sh
uv run python -m backend.manage init-db
uv run python -m backend.manage seed-user \
  --user-id local_admin --display-name '本地管理员' --role admin
uv run python -m backend.manage seed-user \
  --user-id local_viewer --display-name '本地查看者' --role viewer
```

`local_admin` 为示例，须与准备脚本生成的本地鉴权 user_id 一致。也可用 `--role viewer` 创建只读用户；scope 与镜头授权另在本地摘要配置中限制，不因用户存在而获得全库权限。

两个命令仅允许 `development`，都只连接已明确配置的数据库。`init-db` 校验原 SQL 的外层事务包装，在一个事务内取得 advisory 锁、检查 public schema 为空，再完整执行现有 DDL。已有表／视图／序列、重复初始化或 DDL 失败均拒绝，不 DROP、不重建、不改写已有结构；并发初始化串行检查。服务启动和 readiness 不会自动建表。

`seed-user` 使用参数化 INSERT 创建 active 用户，创建时间取服务端 UTC。已有 user_id 明确报错，**不会 UPSERT 或静默修改角色、名字及历史归属**；正式用户管理和认证生命周期另行设计。

## 启动与验证

```sh
uv run uvicorn backend.app:app --host 127.0.0.1 --port 8000 --workers 1
```

Android 使用统一的 API 地址；本地服务绑定 loopback，真机连接地址和正式认证另行配置。调用业务 API 时以本地保存的令牌发送 `Authorization: Bearer ...`，不要在源码、URL、截图或日志中记录令牌。写入要求匹配 role／scope，人工核查由管理员确认后提交。

```sh
uv run pytest
uv run python -m backend.manage --help
```

`uv sync --locked` 同时安装 `../agent` 本地依赖，`uv run pytest` 同时覆盖 Backend 与 Agent 测试。真实 PostgreSQL + pgvector 已用于隔离测试；`create_agent` 的实际图使用显式 fake 模型验证，这不代替真实模型。导入、失败回滚和回读凭据见 [D05 记录](../openspec/changes/d05-backend-mvp/verification.md)，向量与 Agent 凭据见 [D07 记录](../openspec/changes/d07-create-agent/verification.md)。外部 LLM／Embedding、真实视频的业务理解、SDK 和 Android D10 联调仍待单独验收，未声明部署或全面业务完成。

## 显式启用 Agent

在私有环境设置 `PARTRACE_AGENT_ENABLED=true` 后，启动工厂挂载九个 Agent 操作；保持默认 false 时不挂载这些业务路由。运行和工具细节见 [Agent README](../agent/README.md)。

`GET /api/v1/agent/runs/{run_id}/events` 已提供 SSE：先回读运行快照，再推送已提交的终态；完成卡片保存在 `runs.cards_json`。这属于 Agent 运行状态，不是 Vision 任务完成通知；Android 本地接入目前使用轮询，详见 [项目实际状态](../docs/PROJECT_STATUS.md)。

- 使用一个执行进程与 `--workers 1`。专用 PostgreSQL 连接取得 session advisory lease；同数据库／schema 的第二执行器拒绝启动，lease 不是多实例任务调度方案。
- 默认每次模型最多 8 次调用、工具 12 次、60 秒总超时，并发运行 2 个；环境配置名见 [.env.example](.env.example)。
- 首次启用自动创建 `.local-state/agent-cursor.key`，默认 32 字节、0600；`PARTRACE_AGENT_CURSOR_SECRET_FILE` 可指定私有路径。必须持久保留，不能每次重启更换，否则旧历史分页游标失效。
- 停止原子提交 stopped，再通知任务取消；完成和唯一 assistant 消息同事务，迟到结果不覆盖终态。启动时将遗留 running 标为 failed／service_restarted，不自动续跑。
- 失败终结遇到短暂数据库中断时，内存待终结队列保留任务并每 5 秒重试；数据库仍是权威，进程崩溃后由下次取得独占 lease 的执行器恢复，而不是从内存或图 checkpoint 续跑。
- 工具读取元数据和已有描述，没有向 VLM 传图片；引用有效不代表模型已看图或核实动作。


## 真实媒体与开发文档

打开 `http://127.0.0.1:8000/docs` 可查看并调用当前 `dev` 已挂载操作（默认 14 个，启用 Agent 后 23 个，Authorize 使用本地令牌）；`/openapi.json` 复用仓库契约并只列已挂载业务操作。开发文档在 staging／production 不开放，静态令牌也在这两个环境 fail closed。

真实视频上传需要 **ffprobe + ffmpeg**（本地安装后放 PATH，或设置 `PARTRACE_FFPROBE_PATH`／`PARTRACE_FFMPEG_PATH`）。Dockerfile 提供 FFmpeg 工具。检查 MP4 格式、持续时间和完整解码，PNG／JPEG 使用 Pillow 验证；缺工具返回503，坏媒体返回422，不伪装成功。清单默认限2MiB、媒体合计64MiB、事件和关键帧分别限5000项；超限明确拒绝，读取列表也不静默截断。图片解码只证明文件有效，bbox／对象对应和动作仍是授权 Vision 产物声明，须按真实素材验收。

Compose 为 API 配置 160 MiB 临时盘、512 MiB 内存与 1 GiB 内存加交换空间总额。multipart 解析会先把大文件写入临时盘，再复制为待校验媒体；临时盘预留两份默认媒体包及清单的容量。上传并发目前没有单独限流，调整上传限额或增加并发前须同时评估临时盘、解码内存与宿主资源；这些限额不代表任意并发下都能完成上传。

关键帧元数据、图片读取和 Agent 引用校验共用可用性检查：空文件、损坏图片或超出媒体／像素限额的帧按 `missing` 处理，图片接口返回 404 `media_missing`，历史 ID 与定位仍保留。未改变的图片复用有界文件指纹校验缓存，文件写入或替换后重新校验；录像元数据查询不重新解码 MP4。

默认 `started_at=null`，使用视频偏移。非空 `started_at` 按导入方声明原样保存，仅用于 UTC 窗口换算；服务端不再要求独立时间校准记录，上传时间不能证明录像实际拍摄时间，声明值也不作拍摄时刻证明。

配置与签名放受保护的本地目录；auth摘要文件仅当前用户／root可读、权限0600。`infra/compose.yaml` 挂载 `.local-state`，启动前需把 `PARTRACE_UID`／`PARTRACE_GID` 设置为该目录文件所有者，并按Compose连接地址准备环境；本地host准备脚本生成的数据库URL不可直接当作容器内部URL。不要把本地演示令牌用于公网。

跨文件系统与数据库无法构成同一事务：服务先原子发布私有媒体，再提交PG，确定失败会清理本次文件；COMMIT连接中断、结果不明时保留可能已关联的媒体，并以相同ID／内容重试核对结果。进程崩溃遗留的未关联暂存文件需运维核对后清理；不会通过公开API列出这些文件。

集成测试须显式设置 `PARTRACE_TEST_DATABASE_URL` 指向名称以 `_tests` 结尾的独立库，预装pgvector，并授予测试账号该测试库建schema权限。每项测试使用随机 `mvp_test_*` schema，仅清理自身。`PARTRACE_TEST_FFPROBE` 可指定真实probe路径；缺工具会跳过真实上传项，不能据此宣称媒体验证已通过。
