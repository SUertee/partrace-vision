# 验证记录

下方首次验证使用原 `src/` 布局，命令作为历史凭据保留；2026-10-16 目录简化后的当前验证见末节，实际运行命令以 Backend README 为准。

2026-10-16，本地分支 `feat/d05-backend-mvp`，未提交或推送本轮代码。实现没有修改既有 SQL／OpenAPI：11 表、77 字段、27 个设计操作。默认挂载 Backend 14 项，显式启用 Agent 后 22 项；P1 包裹 5 项仍未实现。

## 环境与实际命令

- Python 3.12.13；统一 `backend/.venv`／`backend/uv.lock`。Agent 为可编辑本地依赖，LangChain 1.4.6、langchain-openai 1.7.2、LangGraph 1.2.15、LangSmith 0.14.9、Pydantic 2.14.1。
- 独立本地 PostgreSQL 17／pgvector 0.8.7；集成测试只使用 `partrace_mvp_tests`，每项建立并清理自己的随机 schema。DSN 从受保护本地环境在内存读取，未输出凭据。
- 生成的蓝色 64×64 MP4／PNG，FFmpeg 9.0.2 与现有 FFprobe 7.1；只验证解码、媒体归属和引用链，未检验真实驿站动作。既有无媒体 mock 事件用于权限、幂等、缺图和空结果测试。
- `uv sync --locked --offline --group dev --cache-dir /private/tmp/partrace-uv-cache` 通过，核对 71 个解析包／67 个已安装包。生产 requirements 由该锁导出，含哈希并排除本地包本身；容器从仓库白名单上下文复制 Agent 源码。

从 `backend/` 执行；下列代码读取已有私有 `.env`，仅派生测试库，未把地址或密码写进命令参数：

```sh
PYTHONPATH=src .venv/bin/python - <<'PY'
import os, subprocess
from partrace_backend.config import Settings
settings = Settings()
environment = os.environ.copy()
environment['PARTRACE_TEST_DATABASE_URL'] = (
    settings.database_url.get_secret_value().rsplit('/', 1)[0] + '/partrace_mvp_tests'
)
environment['PARTRACE_TEST_FFPROBE'] = '/Applications/screenpipe.app/Contents/MacOS/ffprobe'
raise SystemExit(subprocess.run(
    ['.venv/bin/python', '-m', 'pytest', '-ra', '--tb=short'], env=environment
).returncode)
PY
```

**287 passed in 12.36s，0 skipped。** 包含既有 Backend 161 项及本轮 126 项：

| 测试文件 | 本次通过 | 验证范围 |
| --- | ---: | --- |
| `agent/tests/test_engine.py` | 21 | 实际 create_agent 图／ToolStrategy，固定范围及文本、并行工具预算、超时、取消、半开窗口、伪造／缺图引用、禁用环境 tracing |
| `backend/tests/test_ai_history_postgres.py` | 16 | 真实 PG 的 owner、签名分页、幂等、并发、停止／完成竞争、整页撤权、完成事务及恢复 |
| `backend/tests/test_semantic_postgres.py` | 27 | float32、固定空间与维度、同源去重、MATERIALIZED 同空间余弦、最新描述、阈值前 coverage 与错误 |
| `backend/tests/test_semantic_api_postgres.py` | 31 | 两项 HTTP 契约、非法 JSON／字段、模型缺失、权限及编码等待期间即时撤权 |
| `backend/tests/test_agent_api_postgres.py` | 7 | 八操作 HTTP schema、202／200／409、无模型历史回读、停止迟到、动态鉴权、独占 lease／重启及越界工具 |
| `backend/tests/test_agent_runtime.py` | 7 | 调度失败补偿、缺模型前鉴权、短暂数据库故障后的待终结重试与释放并发位；依赖为显式替身 |
| `backend/tests/test_model_adapters.py` | 15 | 配置与 SecretStr、兼容模型构造、HTTP 模拟传输、错误脱敏、超时／取消；无外部模型请求 |
| `backend/tests/test_agent_graph_postgres.py` | 2 | 实际 create_agent 图串联五个真实 Backend 工具、上传生成媒体、PGvector 检索和唯一助手引用保存；虚构帧整次失败 |

最后两项的聊天模型输出和文本向量编码器是确定性替身；图、工具、鉴权、事务和媒体服务使用实际实现。测试没有 mock `create_agent`，也未调用外部 LLM／Embedding。

## 其他检查与本地进程

- OpenSpec 1.14.1 `validate --all --strict --no-interactive`：4 changes 通过。
- 五项 DB／API 来源及 HTML 同步 `--check` 通过；数据库目录检查为离线来源快照，不冒称另做生产库目录审计。SQL／OpenAPI 及静态工作台内容保持既有契约。
- 指定实现文档 111 个本地链接、尾随空白与 `git diff --check` 通过。
- `docker compose --env-file infra/.env.example -f infra/compose.yaml config --quiet` 使用无敏感信息的测试占位密码通过；未构建或部署容器，未运行 GitHub Actions。
- 已启用私有本地 `PARTRACE_AGENT_ENABLED=true`，以单 worker 启动 loopback `127.0.0.1:8000`。只读 smoke：readiness 200；OpenAPI 22 个操作，其中 Agent 8 个；本人历史 200，无凭据 401。没有通过 smoke 创建问题、分析、向量或核查记录，也未打印令牌。

## 当前边界

真实模型服务尚未配置，不以测试模型调用冒充外部模型验收。Agent 基于既有事件描述及关键帧元数据做只读检索、引用与说明，工具不向 VLM 发送图片像素。用户须在私有环境选择支持工具调用的 Chat 模型并填写密钥；语义编码的 model／version／dimensions 须与向量数据一致。真实模型兼容、费用／延迟／服务限额、真实视觉结果、Android、SDK 和 D10 同事件闭环未验收。

首版是单执行器、单进程，没有 SSE、运行列表、逐工具持久化日志、多实例队列或图 checkpoint 自动续跑。摄像头、持续直播分析、轨迹扩展、3D 与跨镜均不在本 change。没有生产部署、创建 PR、关闭 Issue 或宣称完整产品完成。

## 移除 src 层后的验证 · 2026-10-16

本次为保留行为的目录重构：源码移至 `backend/partrace_backend/`、`agent/partrace_agent/`，包名、API、数据库及依赖版本不变，无新增行为规格。已同步 setuptools 发现、pytest、默认仓库路径、准备脚本、Docker COPY／白名单、CI 与 README；保留之前未提交的其他工作。

- 在 `backend/` 执行 `uv sync --locked --offline --group dev --reinstall-package partrace-agent --cache-dir /private/tmp/partrace-uv-cache`，重建本地 editable 安装，不手工修改 `.pth`。
- 清除测试进程的 `PYTHONPATH`，仍用上述独立测试库和真实媒体工具执行 `.venv/bin/python -m pytest -ra --tb=short`：**288 passed in 13.01s，0 skipped**。新增一项回归，检查六个默认配置／媒体／SQL 路径仍指向仓库根目录。
- 从仓库外临时目录、不设置 `PYTHONPATH` 导入已安装 Agent，确认来源为 `agent/partrace_agent/__init__.py`；旧两个 `src/` 目录均已不存在。
- `uv build --wheel --offline --out-dir /private/tmp/partrace-flat-layout-wheel --cache-dir /private/tmp/partrace-uv-cache ../agent` 成功；检查 wheel 仅含包及发布元数据，没有旧 `src/` 或测试目录。构建产物留在临时目录，工程内生成元数据和缓存被忽略。
- Compose 使用无敏感测试占位值渲染通过。镜像内保留 `/app/backend`、`/app/agent` 层级，使默认资源根目录为 `/app`；本次未构建镜像或执行部署。
- 以不含 `PYTHONPATH=src` 或 `--app-dir src` 的命令重启 loopback 服务，只读 smoke：readiness 200，OpenAPI 正常列出 22 个已挂载操作。没有调用真实模型、创建分析或修改事实。
- `git diff --check` 通过；有效源码、配置和运行文档的旧路径搜索无残留，历史首次执行命令保留并加注。本次未提交、推送或创建 PR。

## 源码直接平铺到模块目录 · 2026-10-16

按用户进一步要求，删除两个重复内层包目录，源码直接放在 `backend/` 和 `agent/`；外层目录各有 `__init__.py`，导入为 `backend.app`／`agent.engine`。这是目录与导入重构，业务行为、SQL、API 及第三方依赖版本保持原样。

- 两个发行项目仍名为 `partrace-backend`／`partrace-agent`；均通过显式 setuptools `package-dir` 映射到当前模块目录，统一 `uv sync` 安装。Backend 锁文件来源由 virtual 更新为 editable，并离线强制重装两个包。
- 生产 requirements 重新导出，同时排除 Backend 项目和本地 Agent，不含 `-e` 或本地源码路径；测试去掉额外 pythonpath。Docker、准备脚本、管理命令、README、AGENTS 同步新入口。
- `http.py` 改名 `http_boundary.py`，避免在 Backend 工作目录遮蔽标准库 `http`；六处默认资源根定位调整为 `parents[1]`。
- 从 Backend 工作目录、仓库根和仓库外临时目录，不设置 `PYTHONPATH` 导入两个已安装包及标准库 `http.client`，均通过；源码路径为根目录下的 `backend/__init__.py` 和 `agent/__init__.py`。
- 使用既有独立 PG 测试库，执行 `.venv/bin/python -m pytest -ra --tb=short`：**288 passed in 15.06s，0 skipped**；没有依靠 pytest 临时添加源码路径来掩盖安装问题。
- 两份 wheel 均离线构建成功，检查只包含 `backend/` 或 `agent/` 源码与发行元数据，没有旧包目录、src、tests 或本地配置。Compose 用无敏感占位值渲染通过；未构建运行镜像或部署。
- 本地服务以 `uvicorn backend.app:app` 单进程重启；验证与源码组织保持最新，旧阶段命令仅作历史凭据。本轮未提交或推送。


## 按职责整理实际代码目录 · 2026-10-16

本次在外层 Python 包内归类源码，保留 `backend.app`、`backend.manage` 与 `agent.engine` 入口；没有新增业务行为，SQL、OpenAPI、运行状态和权限语义沿用既有约定，因此不新增行为规格。

- Backend 的 18 个实现模块归入 `api / core / services / storage / ai`；同步业务代码、测试、准备脚本的导入及六个默认资源路径。Agent 原样抽出 `prompts.py`，将工具注册和证据校验放入 `tools/` 子包，保留 `agent.tools` 公共导入。
- setuptools 显式包含 Backend 六个包、Agent 两个包。`uv sync --locked --offline --group dev --reinstall-package partrace-backend --reinstall-package partrace-agent --cache-dir /private/tmp/partrace-uv-cache` 通过，第三方依赖锁保持原样。
- 使用受保护本地配置在内存派生独立 `partrace_mvp_tests` URL，测试进程移除 `PYTHONPATH`，设置已有 FFprobe 路径；`.venv/bin/python -m pytest -ra --tb=short --maxfail=3`：**288 passed in 15.37s，0 skipped**。覆盖真实 PostgreSQL／pgvector、生成媒体、工具图、历史事务、停止与恢复；没有外部模型请求。前一次启动器传入了应用不接受的 conninfo 格式，其三个失败 setup 留下的临时 schema 已核对并清理，再用 URL 完成全量验证。
- 两个项目分别执行锁定版本的 `uv build --wheel --offline` 成功；Backend wheel 包含 25 个源码模块，Agent 包含 8 个，无测试、私有配置、缓存或旧平铺源码。wheel 解包到仓库外临时目录后，遍历导入全部模块通过。
- 从 Backend 工作目录、仓库根及外部临时目录导入已安装的两个包和标准库 `http.client` 通过，默认配置与 SQL 路径仍指向仓库根；`python -m backend.manage --help` 通过。
- AST 对比确认 Backend 移动前后仅变更导入和默认路径，Agent 函数、类及提示词保持原样。Docker COPY 与两份白名单已包含全部新子包，静态核对仍排除私有环境、测试与生成产物；未构建 Docker 镜像或部署。
- 模块 README、AGENTS 和联动文档已同步实际目录，相关本地链接与 `git diff --check` 通过。此次没有提交或推送，保留既有其他工作区改动。


## 整合最新 dev 后的提交前验证 · 2026-10-16

- 当前功能分支已快进整合 `origin/dev` 的 `4b1a46b`，包含视觉模块说明及后端写库对接文档；暂存恢复后逐一核对 95 个原有改动文件 SHA-256，内容全部保留。
- 在独立 `partrace_mvp_tests` 中重新执行当前两模块测试，移除测试进程的 `PYTHONPATH` 并使用已有 FFprobe：**288 passed in 19.66s，0 skipped**。测试输入仍是显式模拟模型／编码器和生成媒体，不代表外部模型、真实视觉或 Aurora IAM 接入。
- 五项设计快照／HTML 离线检查全部通过，SQL／OpenAPI 契约未变；新拉取写库文档已补充本地实现与 Aurora 待接入的状态区分。
- 提交范围包含 Backend／Agent 实现、目录分层、依赖、测试、CI、infra、相关 D05／D07 提案与实现文档；Android 缓存代码、Android README、展示策略和 `.finesse` 改动继续留在工作区，不纳入本次提交。

- 使用临时 npm 缓存中的固定 OpenSpec 1.14.1 校验：4 个 active change 与 1 个 archived change 均通过；本机全局旧版未用于最终规格凭据。暂存新文件的空白检查发现两处文件末尾多余空行，已移除并重新检查。


## PR #14 审查修复 · 2026-10-16

本轮在既有媒体有效性和上传限额约定内修复两个问题，没有新增公开 API、数据库字段或业务状态。

- 关键帧元数据、实际字节读取和 Agent 完成引用统一调用媒体有效性检查。空文件、截断 PNG／JPEG、非空坏图、字节或像素超限图片均视为 `missing`，字节端点返回 404 `media_missing`；事件、核查和已完成 AI 历史仍保留原 ID。恢复合法文件后重新可用。
- 为避免列表及 Agent 回查重复解码，媒体实例维护带锁、最多 8192 项的文件指纹校验缓存，仅保存 dev／inode／size／mtime_ns／ctime_ns 和校验结果，不保存图片字节。文件变化使缓存失效，MP4 元数据不进行重解码。
- Compose 原 16 MiB `/tmp` 小于合法 64 MiB multipart 媒体包；修正为 160 MiB 临时盘、API 512 MiB 内存和 1 GiB 内存加交换空间总额，PostgreSQL 原限额保持不变。文档明确上传尚无单独并发限流，增加并发或上传大小需重新评估资源。
- 使用已有 Alpine 3.20 镜像，无网络、无卷、只读根目录的自动删除容器复现：旧 16 MiB tmpfs 写 20 MiB 会 ENOSPC；新 160 MiB tmpfs 成功同时保存两份 66 MiB 文件（各包含默认媒体包和清单的容量）。这只是容器临时盘容量验证，不是应用镜像构建或部署。
- 当前合并修复后的独立 PostgreSQL／pgvector 全量回归：**296 passed in 16.73s，0 skipped**；包含实际视频导入后坏图的 HTTP／核查／AI 引用校验、图片修复恢复、缓存失效与上限场景。没有外部模型请求。
- Compose 使用无敏感占位值渲染，核对 API／PostgreSQL 资源配置通过。OpenSpec 1.14.1 的 4 个 active 和 1 个 archived change、五项离线设计同步检查及 `git diff --check` 全部通过。
