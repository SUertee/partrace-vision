# Backend 本地运行

主责 Harry Xu；当前本地实现包括 D05 事件证据与语义检索、D09 人工留档、People 原片持久任务，并可显式组合 D07 Agent。使用 **Python 3.12 + FastAPI + PostgreSQL / pgvector**，图片和录像保存在本地受限目录，数据库保存媒体键。现状核对日期：2026-10-17。

源码按职责放在 `backend/` 的子包内，外层目录本身是 Python 包；启动入口为 `backend.app`，管理命令为 `backend.manage`。发行包名为 `partrace-backend`。以下命令均从 `backend/` 执行。

```text
backend/
  __init__.py
  app.py                 FastAPI 应用组装与生命周期
  manage.py              显式建库、迁移、任务重试与本地用户命令
  api/                   HTTP 路由与请求边界
    __init__.py          Agent 路由挂载
    facts.py             用户、配置、录像、事件、证据、核查接口
    recording_jobs.py    原片任务、续租及 Vision 完成回调接口
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
    facts.py             录像不可变发布、事件查询与人工核查
    recording_jobs.py    持久原片录入、任务租约与原子发布
    semantic.py          向量校验、写入和同空间检索
  storage/               数据库凭据、探测与媒体存储
    credentials.py       Aurora IAM 动态令牌与连接类工厂
    database.py          PostgreSQL／pgvector 就绪检查
    media.py             视频与图片验证、私有文件存取和清理
  ai/                    Agent 在 Backend 中的宿主功能
    runtime.py           后台任务、并发、停止与重启恢复
    gateway.py           只读工具的权限与范围校验
    history.py           会话、消息、运行的持久化事务
    providers.py         聊天模型和文本编码器适配
    push.py              受权运行状态 SSE 通知
  tests/                 HTTP、业务与 PostgreSQL 集成检查
  scripts/               本地环境准备脚本
  examples/              无凭据的配置样例
  pyproject.toml         当前包及子包声明
  uv.lock                两个本地包的统一依赖锁
  Dockerfile             使用仓库根目录构建上下文
```

`api` 解析请求，`services` 处理事实业务，`storage` 处理数据库连接凭据、就绪探测和媒体文件；`ai` 托管 Agent 的任务、权限与历史，独立 `agent` 包负责模型执行。新增源码放入对应子包，并同步打包清单和 Docker 白名单。`.venv / __pycache__ / .pytest_cache / build / *.egg-info` 是本地环境或生成产物，不属于源码分类；保留在 Git 忽略范围内。

[模块与 DB／API 关联](../docs/modules/README.md) · [OpenAPI](../docs/api/openapi.json) · [SQL](../docs/database/schema.sql) · [开发计划](../docs/DEVELOPMENT_PLAN.md) · [D05 验证](../openspec/changes/d05-backend-mvp/verification.md) · [D07 验证](../openspec/changes/d07-create-agent/verification.md)

## 默认二十一个数据操作

| 功能 | 既有 API |
| --- | --- |
| 当前用户 | `GET /api/v1/user` |
| 不可变区域配置 | `GET /api/v1/configs/{config_version}` |
| 录像导入、列表及详情 | `POST /api/v1/recordings`、`GET /api/v1/recordings`、`GET /api/v1/recordings/{recording_id}` |
| 事件查询及详情 | `GET /api/v1/events`、`GET /api/v1/events/{event_id}` |
| 关键帧元数据及真实字节 | `GET /api/v1/evidence/{evidence_id}`、`GET /api/v1/evidence/{evidence_id}/media` |
| 人工核查保存、列表及回读 | `POST /api/v1/review-records`、`GET /api/v1/review-records`、`GET /api/v1/review-records/{review_id}` |
| 描述文本向量写入与语义检索 | `POST /api/v1/event-embeddings`、`POST /api/v1/events/search` |
| People 原片持久任务 | 七个 `recording-jobs` 操作，完整列表见下方 [People 事件原片与 Vision 任务](#people-事件原片与-vision-任务) |

基础数据的原有验证保留在 [D05 记录](../openspec/changes/d05-backend-mvp/verification.md)，向量与 Agent 的当前验证见 [D07 记录](../openspec/changes/d07-create-agent/verification.md)。数据库不可用时明确失败，不回退成进程内数据或模拟成功；服务重启后从 PostgreSQL 回读。`/health/live` 与 `/health/ready` 是运行探测，不计入 35 个业务操作。

基础数据原有 14 个操作，本轮增加七个原片任务操作，默认共 21 个；显式 `PARTRACE_AGENT_ENABLED=true` 再挂载 9 个 Agent 操作（含 SSE 状态推送），共 30／35，P1 包裹 5 个操作尚未实现。空库 DDL 包含十二张表、106 个字段，`recording_jobs` 保存原片任务；契约与空表不代表算法或业务验收完成。[Backend／Agent 联动](../docs/modules/BACKEND_AGENT.md)采用同一 FastAPI 应用内的 Python 接口；独立 `agent/` 包使用 `create_agent`，通过五个受限工具调用 Backend，不直连数据库。D06 摄像头 SDK 验证独立推进。

## People 事件原片与 Vision 任务

`recordings`（不是 `recoding`）先保存原片元数据，再发布完整分析事实。`POST /api/v1/recordings` 使用 multipart `mode=source`、原片任务 `manifest` 和恰好一个 `files` 文件 `source.mp4`，在同一事务内创建 `publication_status=pending` 录像与 `queued` 任务；新录入202，同内容重放200，返回 `{api_version, recording, job, replayed}`。缺省 `mode=result` 保持原“完整原片 + events + evidence”导入，JSON mock 包也保持兼容。SDK People 原生检测元数据与团队动作事实分开保存。

待解析录像的 `GET /recordings/{id}` 可回读来源、时长、`publication_status=pending` 与 `manifest_sha256=null`；默认 `GET /recordings` 只列 `published`。事件、语义与 Agent 事实查询遇到待解析录像先返回409 `recording_not_ready`，不把未完成解析当作零事件、无覆盖或 ROI 无效。最终发布后 `publication_status=published`，`manifest_sha256` 是完整结果包摘要，事实保持不可变。处理中的 `queued/processing/failed` 仍只在 `recording_jobs.status` 中表达。

| 新增接口 | 请求与权限 |
| --- | --- |
| `POST /api/v1/recording-jobs` | 兼容入口，multipart `manifest` + 单个 `video` MP4；也原子创建 pending 录像，维持 `{api_version,job,replayed}` 返回；active admin + `data:ingest`，检查 camera／recording 授权；新任务202，同内容重放200，异内容409 |
| `GET /api/v1/recording-jobs/{job_id}` | `data:read` + 当前资源授权；回读真实持久任务状态 |
| `POST /api/v1/recording-jobs/claim` | JSON `{worker_id}`；active admin + `vision:work`，只认领获授权镜头；无任务200 `job:null` |
| `GET /api/v1/recording-jobs/{job_id}/media` | active admin + `vision:work`、当前资源授权及 `X-Lease-Token`；返回受限原片字节 |
| `POST /api/v1/recording-jobs/{job_id}/heartbeat` | JSON `{}` + `X-Lease-Token`；当前 worker 续租，期限见返回 `job.lease_expires_at` |
| `POST /api/v1/recording-jobs/{job_id}/complete` | multipart `manifest`（既有 `RecordingManifest`）+ 重复 `files`（证据图片）及 `X-Lease-Token`；active admin + `vision:work` + `data:ingest`；新发布201，同结果重放200 |
| `POST /api/v1/recording-jobs/{job_id}/fail` | JSON `{code}` + `X-Lease-Token`；active admin + `vision:work`；安全错误码，不接收内部日志或凭据 |

原片清单字段为 `job_id / camera_id / config_version / sdk_event_id / duration_ms`，可选 `event_type` 只允许 `people`，可选 `sdk_timestamp_ms` 默认为 `null`。`job_id` 是 Android 根据设备／事件稳定生成的资源 ID，最终 `recording_id=job_id`；`sdk_event_id` 是原样保存的 SDK 不透明标识，允许 `/`、`@`、`|` 等原生符号，严格字符串长1–200，拒绝全空白及控制字符，不用于 URL 或存储路径。同一镜头／SDK 事件只建立一个任务。时长来自真实 MP4，SDK 时间仅保留来源元数据，不能当作已校准 UTC。

Worker 完成清单必须保留任务的 camera／config／duration、`source_mode=sdk`、`started_at=null` 与 `recording_id=job_id`，原片文件名固定 `source.mp4`；后端复用已保存原片，worker 不再次上传视频。录像 pending→published、events／evidence 与任务 `completed` 同一 PostgreSQL 事务提交；校验失败、撤权、旧租约或到期结果不能发布部分事实，原片及 pending 元数据保留。原始视频、证据图片均为私有服务端存储键，响应不泄露路径、租约摘要或设备凭据。

状态为 `queued → processing → completed / failed`。`FOR UPDATE SKIP LOCKED` 支持多个 worker；`processing` 租约到期可重领且更换随机令牌，旧 worker 的续租／失败／完成回调返回409。默认租约60秒，可通过 `PARTRACE_RECORDING_JOB_LEASE_SECONDS` 调整；长分析必须定期 heartbeat。失败不会自动反复重跑，同内容上传仅回读原终态；操作者修复依赖或配置问题后可显式重排：

```sh
uv run python -m backend.manage retry-recording-job JOB_ID
```

命令只允许 development 的 `failed` 任务，清除旧 worker／租约／错误和结果字段，保留尝试次数与原片；`completed`、运行中或不存在的任务不能修改。Vision 独立进程只通过上述受限 API 工作，不直连数据库，也不接收 Android 的 SDK 凭据。真实算法与部署环境仍以 Vision 自身验证结果为准。

**已有数据库在启动本版本前必须显式执行增量迁移，包括仍只使用原 `POST /recordings` 的实例：**

```sh
uv run python -m backend.manage migrate-recording-jobs
uv run python -m backend.manage migrate-source-recordings
```

两个 development 命令使用当前 PostgreSQL／Aurora 连接配置：尚无 `recording_jobs` 时先执行 [001 新增表迁移](../docs/database/migrations/001-recording-jobs.sql)；已有任务表则跳过第一条。再执行 [002 原片录像迁移](../docs/database/migrations/002-source-recordings.sql)，仅新增录像发布状态、放宽 pending 摘要空值并为未发布任务补建 pending 元数据，复用原片键且保留任务状态、租约、尝试次数及已发布事实；异常 completed 任务缺少事实或来源冲突时整体回滚。两者拒绝重复执行。不要对现有数据库运行 `init-db`；空库才使用原初始化命令，最新 DDL 已包含两次迁移。服务启动和 readiness 不会自动迁移。

2026-10-17 的[真实 People 验证](../openspec/changes/d06-people-recording-pipeline/verification.md)已跑通 A 门铃的历史手动录像：真实 SDK 下载、App 上传、本地 API 入库、CPU 建档、Backend completed 与 App 完成回读。30.466 秒原片产生 5 条匿名局部轨迹与 10 张内部选帧；A 的业务 event／evidence 为 0，表示该建档阶段不发布区域动作，不能解释为无人，也未验证自然人身份。最新记录为 13 条 App 任务均已受理（1 completed、12 queued、0 failed）；B 球机仍缺现场货架标定及 zone_id 映射，排队不等于解析完成，旧配置任务也不会自动改用新配置。自动发现仅在 App 前台且 SDK 已登录时运行；SDK 时间未独立校准，`started_at=null`。以上为此前本机验收；后续独立 Aurora 公网部署的验证见文末，D10 仍未完成。

旧版 Agent 数据库若已有 `runs` 但缺少 `cards_json`，停用旧服务后显式执行 [003 Agent 卡片迁移](../docs/database/migrations/003-agent-cards.sql)，再重启服务以读取最新 `.env`：

```sh
uv run python -m backend.manage migrate-agent-cards
```

此命令仅允许 `development`，使用当前数据库／schema 配置，在同一事务中加锁、新增 `cards_json` 和 `ck_run_cards`，并更新终态约束；running／stopped／failed 记录保持原样。目标缺少 `runs`、已存在 `cards_json` 或存在任意旧 `completed` 运行时均明确拒绝；旧完成结果没有经过校验的卡片，禁止伪造或回填。DDL 或检查失败会整体回滚，保留已有结构和数据。该命令不会自动运行，也不删除历史；已有 completed 数据须另行制定保留与升级方案。新空库的最新 `schema.sql` 已包含卡片约束，无需再运行 003。

## 本地准备

以下命令从仓库的 `backend/` 目录执行，需要 uv 和 Docker。准备脚本的 `--start-db` 会新建独立的 `partrace-mvp-dev` 容器、持久化卷，并仅将 PostgreSQL 发布在 `127.0.0.1:54391`；不接触其他项目的容器和数据库。管理命令本身不创建数据库或账号。

```sh
cd backend
uv sync --locked
uv run python scripts/setup-local.py --start-db
```

两个项目的 setuptools 配置将 `backend`、`agent` 分别映射到各自项目根目录。`uv sync --locked` 安装当前 Backend 和 `../agent` 两个本地可编辑包，导入使用 `backend.app`、`agent.engine`；无需设置 `PYTHONPATH` 或传入 `--app-dir`。

准备脚本写入 `backend/.env` 和仓库 `.local-state/` 下的受限本地文件；已有文件拒绝覆盖。随机令牌只保存到 `.local-state/bootstrap-credentials.json`，命令只显示保存位置，不打印令牌。按本地引导文件中的身份创建数据库用户，实际密码、令牌及媒体不得提交 Git。

Agent／Embedding 配置按 [.env.example](.env.example)补充到已生成的私有 `backend/.env`，不要覆盖已有数据库和身份配置。模型适配器使用 OpenAI 兼容接口；API key 只从私有环境读取，不发到聊天、源码、业务请求或日志。缺 Agent 模型时计划／新分析返回 503，缺编码模型时语义检索返回 503 `model_unavailable`，不自动换成测试模型或模拟向量。真实外部聊天模型已返回计划，但完整分析未成功；当前本地 Embedding 仍缺配置，详见下方验证边界。

已有独立空库时，改为通过环境提供 `PARTRACE_DATABASE_URL` 后执行不带 `--start-db` 的准备命令。脚本不创建业务表和用户。不要把密码直接写进可记录的终端命令或截图；本地环境文件及 `.local-state/` 被忽略。

| 设置 | 用途／默认位置 |
| --- | --- |
| `PARTRACE_ENVIRONMENT` | 本地使用 `development`；静态引导鉴权和管理命令不用于 staging／production |
| `PARTRACE_DATABASE_URL` | 必填 PostgreSQL URL，包含明确主机及数据库名；不提供 SQLite 或内存回退 |
| `PARTRACE_DATABASE_IAM_AUTH` | 默认 `false`；`true` 时改用 Aurora IAM 认证，为每条新物理连接现签 15 分钟令牌 |
| `PARTRACE_DATABASE_IAM_REGION` | IAM 签名区域（如 `us-west-2`）；启用 IAM 时必填，且 URL 必须包含数据库用户 |
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

`uv sync --locked` 同时安装 `../agent` 本地依赖，`uv run pytest` 同时覆盖 Backend 与 Agent 测试。真实 PostgreSQL + pgvector 已用于隔离测试；`create_agent` 的实际图使用显式 fake 模型验证，这不代替真实模型。导入、失败回滚和回读凭据见 [D05 记录](../openspec/changes/d05-backend-mvp/verification.md)，引擎与向量凭据见 [D07 记录](../openspec/changes/d07-create-agent/verification.md)。[People 最新验证](../openspec/changes/d06-people-recording-pipeline/verification.md)记录 Backend／Agent 338 项通过、无跳过，Vision 101 项通过；这些是 2026-10-17 已留存凭据，本次文档核对未重跑业务测试。

[Android Agent 验证](../openspec/changes/d07-android-agent/verification.md)已确认真实聊天模型返回计划；独立 API 运行终态为 `failed / agent_execution_failed`，Android 确认的另一次运行终态为 `failed / agent_timeout`，失败状态已在冷启动后回读。当前使用模拟录像，Embedding model／dimensions／key 未配置，语义接口返回 503；未保存上述失败运行的完整工具轨迹，不能据此认定具体根因。真实摄像头事件的 Agent 引用、人工保存与重启回读仍待 D10，未声明部署或全面业务完成。

## 显式启用 Agent

在私有环境设置 `PARTRACE_AGENT_ENABLED=true` 后，启动工厂挂载九个既有 Agent 路由；保持默认 false 时不挂载这些业务路由。运行和工具细节见 [Agent README](../agent/README.md)。

服务端 SSE 状态路由与终态卡片包已实现；Android 当前仍每 2 秒轮询，不订阅 SSE。Agent SSE 不负责 Vision 原片任务的完成回调。

- 使用一个执行进程与 `--workers 1`。专用 PostgreSQL 连接取得 session advisory lease；同数据库／schema 的第二执行器拒绝启动，lease 不是多实例任务调度方案。
- 默认每次模型最多 8 次调用、工具 12 次、60 秒总超时，并发运行 2 个；环境配置名见 [.env.example](.env.example)。
- 首次启用自动创建 `.local-state/agent-cursor.key`，默认 32 字节、0600；`PARTRACE_AGENT_CURSOR_SECRET_FILE` 可指定私有路径。必须持久保留，不能每次重启更换，否则旧历史分页游标失效。
- 停止原子提交 stopped，再通知任务取消；完成和唯一 assistant 消息同事务，迟到结果不覆盖终态。启动时将遗留 running 标为 failed／service_restarted，不自动续跑。
- 失败终结遇到短暂数据库中断时，内存待终结队列保留任务并每 5 秒重试；数据库仍是权威，进程崩溃后由下次取得独占 lease 的执行器恢复，而不是从内存或图 checkpoint 续跑。
- 工具读取元数据和已有描述，没有向 VLM 传图片；引用有效不代表模型已看图或核实动作。


## 真实媒体与开发文档

打开 `http://127.0.0.1:8000/docs` 可查看并调用当前已挂载操作（默认 21 个，启用 Agent 后 30 个，Authorize 使用本地令牌）；`/openapi.json` 复用仓库契约并只列已挂载业务操作。开发文档在 staging／production 不开放，静态令牌也在这两个环境 fail closed。

真实视频上传需要 **ffprobe + ffmpeg**（本地安装后放 PATH，或设置 `PARTRACE_FFPROBE_PATH`／`PARTRACE_FFMPEG_PATH`）。Dockerfile 提供 FFmpeg 工具。检查 MP4 格式、持续时间和完整解码，PNG／JPEG 使用 Pillow 验证；缺工具返回503，坏媒体返回422，不伪装成功。清单默认限2MiB、媒体合计64MiB、事件和关键帧分别限5000项；超限明确拒绝，读取列表也不静默截断。图片解码只证明文件有效，bbox／对象对应和动作仍是授权 Vision 产物声明，须按真实素材验收。

Compose 为 API 配置 160 MiB 临时盘、512 MiB 内存与 1 GiB 内存加交换空间总额。multipart 解析会先把大文件写入临时盘，再复制为待校验媒体；临时盘预留两份默认媒体包及清单的容量。上传并发目前没有单独限流，调整上传限额或增加并发前须同时评估临时盘、解码内存与宿主资源；这些限额不代表任意并发下都能完成上传。

关键帧元数据、图片读取和 Agent 引用校验共用可用性检查：空文件、损坏图片或超出媒体／像素限额的帧按 `missing` 处理，图片接口返回 404 `media_missing`，历史 ID 与定位仍保留。未改变的图片复用有界文件指纹校验缓存，文件写入或替换后重新校验；录像元数据查询不重新解码 MP4。

默认 `started_at=null`，使用视频偏移。沿用最新 dev 的完整包输入规则：非空 `started_at` 作为导入方声明保存，API 可以据此换算 UTC，但不证明它是真实拍摄时刻；本版不读取 `PARTRACE_CLOCK_FILE`。SDK People 原片及 worker 发布始终保留未知起点 `null`，SDK 时间只保存为来源元数据。独立拍摄时钟校准仍须另行验收。

配置与签名放受保护的本地目录；auth摘要文件仅当前用户／root可读、权限0600。`infra/compose.yaml` 挂载 `.local-state`，启动前需把 `PARTRACE_UID`／`PARTRACE_GID` 设置为该目录文件所有者，并按Compose连接地址准备环境；本地host准备脚本生成的数据库URL不可直接当作容器内部URL。不要把本地演示令牌用于公网。

Aurora 部署改用 `infra/compose.aurora.yaml` 叠加层，只启动 API 并指向托管集群：

```sh
docker compose -f infra/compose.yaml -f infra/compose.aurora.yaml up -d --no-deps api
```

在 `infra/.env` 设置 `PARTRACE_AURORA_HOST` 与 `PARTRACE_AURORA_REGION`；叠加层生成无密码、`sslmode=require` 的连接 URL 并强制开启 IAM 认证，捆绑的 postgres 变量仍须保留。该集群强制 IAM 认证、不接受任何密码登录；服务从 EC2 实例角色（IMDSv2）读取临时凭据，仅在内存中为每条新物理连接现签 15 分钟令牌，不配置静态密码、不落盘、不新增依赖。连接池、就绪检查、Agent 执行器和管理命令共用 [credentials.py](storage/credentials.py) 的同一连接类工厂；IAM 关闭时返回原生 psycopg 类，密码部署行为不变。IAM 路径只在带实例角色的 EC2 上可用，本地开发继续使用本地 PostgreSQL 密码连接。签名正确性由 `tests/test_credentials.py` 锁定（冻结时间戳下与 AWS 参考签名逐字节一致）；与真实 Aurora 集群的联调验证尚未执行，接入口径见 [后端写库对接文档](../docs/database/BACKEND_WRITE_GUIDE.md)。

跨文件系统与数据库无法构成同一事务：服务先原子发布私有媒体，再提交PG，确定失败会清理本次文件；COMMIT连接中断、结果不明时保留可能已关联的媒体，并以相同ID／内容重试核对结果。进程崩溃遗留的未关联暂存文件需运维核对后清理；不会通过公开API列出这些文件。

集成测试须显式设置 `PARTRACE_TEST_DATABASE_URL` 指向名称以 `_tests` 结尾的独立库，预装pgvector，并授予测试账号该测试库建schema权限。每项测试使用随机 `mvp_test_*` schema，仅清理自身。`PARTRACE_TEST_FFPROBE` 可指定真实probe路径；缺工具会跳过真实上传项，不能据此宣称媒体验证已通过。

## Aurora People 公网实测 · 2026-10-17

当前35.84.190.14新增People容器，公网HTTPS实际提供21个数据/任务操作、Agent关闭。Aurora `partrace/public` 原5录像/3事件/1证据及全部旧行保留，增量新增recording_jobs与发布状态。门铃A真实历史22.266秒与新自动30.066秒均已published/completed，App同ID完成回调标记已验证；球机B新自动原片已pending/queued，等待现场标定。原片字节位于EC2私有媒体目录，Aurora保存元数据、状态和存储键。A worker目前在本机CPU持续运行。部署/回退与可直接执行的SQL见[运维说明](../infra/people-aurora/README.md)，实际ID、时间、错误边界见[本轮验证](../openspec/changes/d06-people-recording-pipeline/verification.md#aurora-公网与真实自动事件2026-10-17)。本节是新的部署凭据，不代表本地全部未提交实现已合并dev，也不代表production认证或B解析完成。

### events.person_id 增量

事件新增可空A图库G编号person_id，保留局部track_key。已有库须先显式执行 `uv run python -m backend.manage migrate-event-person-id`，只加列和格式约束，不回填或改写旧事实。旧清单省略/显式null保持旧幂等摘要兼容；匹配G编号变化不可覆盖旧结果。定义与输入边界见[004迁移](../docs/database/migrations/004-event-person-id.sql)、[字段说明](../docs/database/README.md)。
