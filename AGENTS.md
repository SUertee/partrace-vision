# ParTrace Vision 开发协作规范

本文件适用于整个仓库，供团队成员和 AI 编程助手共同遵循。产品名称为 **ParTrace Vision · 驿站追迹**，slogan 为 **不盯屏，也能看清驿站每一天。**

## 开始工作

- 先读 [README.md](README.md)、[开发计划](docs/DEVELOPMENT_PLAN.md) 和对应模块的 README；跨模块改动再读 [接口约定](docs/api/README.md)。
- 检查当前分支、工作区和相关 Issue。一次围绕一个明确工作包，先确认依赖、交付物和验收条件，再实现、验证和交接。
- GitHub Issues 维护当前任务状态；开发计划维护范围、依赖和验收条件。HTML 工作台用于评审原型，不代表实际开发进度。
- 保留他人未提交的修改；多人或多个 Agent 并行时明确文件范围，同一文件的修改串行整合。

## 模块与接口

| 目录 | 主责 | 边界 |
| --- | --- | --- |
| `android/` | 李果钦 | Android 页面、查询与证据联动、Agent 交互、人工核查与记录 |
| `backend/` | Harry Xu | 事件、证据、覆盖与核查接口，持久化；另负责摄像头 SDK 验证 |
| `agent/` | 韩广宁 | 问题转计划、只读工具、运行控制和带引用的结果 |
| `vision/` | 徐泽华 | 固定素材、匿名片段、区域事件、真实关键帧及效果评估 |

- 数据库字段与约束以 `docs/database/schema.sql` 为准；API 参数与返回以 `docs/api/openapi.json` 为准，说明和模拟样例放 `docs/api/`。实际实现放对应开发目录。共享约定由 Harry Xu 牵头，四人共同确认。
- 全部业务模块及其开发内容、表读写、API 提供／调用和前置依赖记录在 `docs/modules/`。新增或调整功能时同时更新模块设计及相关 DB／API 资料；各代码目录 README 维护工程准备与本地运行说明。
- 对外接口统一使用 `/api/v1`：当前用户为 `GET /api/v1/user`，Agent 使用 `/api/v1/agent/...`。当前用户由认证身份确定；路由主版本与文档草案版本分开维护。
- 跨模块字段变更须在同一 PR 更新模块说明、SQL／OpenAPI、样例和受影响的 OpenSpec；重新导出结构快照、生成 HTML 图并同步离线工作台，说明调用方及兼容方式，检查命令见 `docs/README.md`。
- Event/Evidence 等 ID 保持稳定。时间使用 ISO 8601 UTC，视频相对时间使用毫秒；SDK 时间换算须验证后记录。
- 区分成功、覆盖内零事件、无采集覆盖和接口错误；证据失效、重复导入及重复保存按约定处理。
- `backend/` 已有 development 最小数据服务，实际范围、启动和测试凭据见其 README 与 D05 提案；不代表正式认证或 D10 已完成。`agent/`、`vision/` 的实现及实测按各自凭据判断。新增模块时写清真实依赖、配置和启动验证命令。

## 实现约定

- 首轮围绕固定区域的真实预录素材完成“事件入库 → Android 查询与开帧 → Agent 引用 → 人工保存 → 重启回读”。P1 包裹扫码入出库在 D10 后实现：扫码只查询，管理员确认后写流水，Agent 只读历史。订单关联、跨镜和 Web 查阅后置；SDK 验证单独记录。
- 沿用各模块已有风格，一次提交围绕一个目的。公共逻辑避免复制；业务实现不放进 `docs/`，临时脚本不代替正式模块。
- 服务端统一 Python：`backend/` 使用 FastAPI，`agent/` 首版作为独立模块接入同一 API 应用；`vision/` 在独立工作进程执行推理，按共享结果包交接后端。视觉重计算不阻塞 API 请求，模型与媒体存储方案另行确认。
- Python 源码在 `backend/`、`agent/` 包内按职责组织，不使用 `src/` 或重复的内层包目录。Backend 分为 `api / core / services / storage / ai`，Agent 保留引擎、协议与结构文件，将提示词独立到 `prompts.py`，工具放在 `tools/` 子包；目录说明见各模块 README。启动与公共导入使用 `backend.app`、`backend.manage`、`agent.engine`；发行包名保持 `partrace-backend`、`partrace-agent`。setuptools 显式包含业务子包，新增子包同步打包清单和 Docker 白名单。统一从 `backend/` 执行 `uv sync --locked` 安装两个本地可编辑包；启动使用 `uv run uvicorn backend.app:app --host 127.0.0.1 --port 8000 --workers 1`，管理使用 `uv run python -m backend.manage`，无需 `PYTHONPATH` 或 `--app-dir`。
- 存储使用 PostgreSQL + pgvector，业务与向量同库分表；模型空间与维度仍待确认。数据库查询由后端提供，Agent 不直接连接数据库。
- Android 使用 Kotlin + Compose。类和 Compose 组件采用 PascalCase，函数及属性采用 camelCase；跨端公共逻辑放 Common，Android 专有实现放对应 Android 源集。
- 保持官方 Android/Common 两个工程同级及现有引用关系。工具链以 [Android 说明](android/README.md) 为准；升级依赖、迁移工程或改包名须单独说明并验证。
- 网络和耗时操作不阻塞 UI 线程；取消运行后停止追加结果，正确释放订阅、播放器和采集资源。配置通过明确入口传入，失败返回可诊断错误。
- 明示模拟、人工标注、真实预录或 SDK 来源。真实调用失败不得用模拟结果冒充成功；官方 SDK 事件和团队自定义动作事件分开记录。
- Agent 只读检索，并引用有效 Event/Evidence ID。管理员显式确认后才保存核查决定，人工决定与原始事件分开持久化。
- 未知区间保留未知。离开画面不等于离站，缩略图不证明连续轨迹，单次检测不直接认定身份、运单或责任。

## 分支、提交与 PR

- `main` 保存稳定基线与阶段交付版本，`dev` 用于四人日常集成和联调。功能开发从最新 `dev` 创建短期分支，PR 的目标分支选 `dev`。
- 联调通过后，由 `dev` 向 `main` 提交阶段交付 PR，使用 merge commit 保留长期分支共同历史；功能 PR 可 squash。`main` 的所有变更（含文档）必须通过 PR；小型文档或配置修改可按用户已有授权直接提交到 `dev`。
- 分支格式为 `<type>/<dxx-topic>`，如 `feat/d08-evidence-view`、`fix/d07-stop-run`、`docs/team-guidelines`；常用类型为 `feat`、`fix`、`docs`、`refactor`、`test`、`build`、`ci`、`chore`。
- 提交和 PR 标题使用 `<type>(<scope>): <subject>`，scope 可省略，可用 `android`、`backend`、`agent`、`vision`、`api`、`docs`。标题简洁说明具体变化，正文交代复杂改动的原因与影响。
- 例如：`feat(android): 添加关键帧联动`、`fix(agent): 停止后不再追加结果`、`docs(api): 补充无覆盖返回样例`。
- 使用真实 Issue 编号关联工作包；仅满足验收条件、确实应关闭任务时使用 `Closes #编号`。不因样例接通就关闭真实业务任务。
- 按 [PR 模板](.github/PULL_REQUEST_TEMPLATE.md) 写清改动、实际验证、输入来源和交接。页面改动附截图或可用预览；接口改动注明消费方。
- 不强推、删除共享分支、使用 `reset --hard` 或覆盖他人改动。合并、部署和权限变更按用户授权及实际仓库规则执行。
- `main` 已启用分支保护：必须通过 PR，管理员同样适用，禁止强推和删除，合并前须解决所有讨论。审批数要求为 0，未配置必需 CI 检查；`dev` 保持日常开发使用。命名和本地验证仍是协作约定，不声称已配置自动检查。

## OpenSpec 工作流

- 固定使用 OpenSpec `1.14.1`，默认 `spec-driven` schema；安装、工具入口和日常流程见 [OpenSpec 说明](docs/OPENSPEC.md)。
- 新功能、跨模块接口或事件语义变化、Agent 工具行为及证据策略变化，先在根目录 `openspec/changes/` 创建提案。文档纠错、格式整理和恢复既定行为的小修复可直接走 PR，说明无需 change 的原因。
- `docs/modules/` 维护模块与 DB／API 关联，SQL 和 OpenAPI 维护字段契约；OpenSpec 维护行为要求与验收场景，通过链接关联契约，不复制字段。开发计划维护工作包和依赖，GitHub Issues 维护实际进度与凭据。
- 每个 change 说明 Dxx 工作包、真实 Issue、受影响模块、依赖和验证方法；一个工作包可有多个 change。纯工具或文档 change 不改变业务行为时使用 `skip_specs: true`，不虚构业务规格。
- 提案与已接受的需求有冲突时，说明差异并同步修正规格和契约。已归档规格通过 change 更新；规格记录约定，不代表功能已实现或已验收。
- 完成提案自身约定的交付与验证后再归档。文档基线的归档只表示约定已确认；业务功能的归档须有相应实现和验证。归档不自动关闭 Issue，不替代 D10。
- 不修改工具生成的 `openspec-*` skills 和 `opsx` commands；项目规则写入本文件和 `openspec/config.yaml`，升级时按固定版本重新生成入口。
- CI 分别检查 OpenSpec 格式／归档任务和设计文档快照／HTML 同步；是否通过以具体 Actions 记录为准。两者不验证业务实现，也不等于分支保护已将其设为必需检查。

## 验证与完成

按改动范围运行检查，不为纯文档调整构建整个 Android 工程，也不为低风险修改添加重复实现的测试。

| 改动 | 验证要求 |
| --- | --- |
| 文档、配置、接口样例 | `git diff --check`、相对链接及 JSON 校验；DB／API 契约或页面变化另运行 `docs/README.md` 中五项设计同步检查 |
| Android 代码 | 配置、SDK 和工具链就绪后，在 Android 工程目录运行 `./gradlew :app:assembleDebug`；涉及共享逻辑运行相关单元测试，涉及资源或平台 API 运行 `:app:lintDebug` |
| Android 页面或 SDK | 除构建外，记录真机操作、版本及结果；模拟器或构建成功不能代替摄像头实测 |
| 后端、Agent、视觉算法 | 运行模块 README 中实际存在的检查；按工作包覆盖相关空结果、错误、取消、引用及数据持久化场景 |
| 端到端交接 | 按 D10 用同一真实事件验证入库、开帧、Agent 引用、人工保存与重启回读 |

- 记录本次实际执行的命令或操作、环境、结果及未验证项；不复用旧测试结果作为本次凭据，不把设计、工具调用成功或 HTML 原型检查当作业务完成。
- 交付时在 Issue/PR 附代码、样例或运行凭据，说明仍等待的依赖；满足该工作包验收条件后再标记完成。

## 配置与数据

- 不提交密钥、令牌、生成的本地配置、SDK AAR、签名文件、构建缓存、录像或采集数据；遵循 `.gitignore`。日志和截图也不得暴露凭据或真实用户信息。
- 只提交空凭据配置模板；用 `scripts/setup-android.py` 准备本地配置和 SDK，保留已有配置。示例使用占位值，文档使用仓库相对路径。
- 保留 [官方材料来源与授权说明](THIRD_PARTY_NOTICES.md)。不擅自修改仓库可见性、账户、权限或全局 Agent 配置来绕过缺失能力。
