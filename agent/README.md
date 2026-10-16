# Agent 执行模块

主责：韩广宁。对应 D07，并在 D10 补齐首页简报。现状核对日期：2026-10-17。

业务功能与表／API 关联见 [M06 AI 会话与分析](../docs/modules/README.md#m06)；只读检索接 [M04 事件证据](../docs/modules/README.md#m04) 和 [M05 语义检索](../docs/modules/README.md#m05)，后续读取 [M10 包裹历史](../docs/modules/README.md#m10)。首页简报属于 [M08](../docs/modules/README.md#m08)。

源码组织在 `agent/` 包内，外层目录通过 `__init__.py` 成为 Python 包，工具实现放在 `tools/` 子包，使用 `agent.engine` 等导入；不使用 `src/`，也不再套一层同名源码目录。发行包名保持 `partrace-agent`。模块使用 LangChain `create_agent` 运行受限工具循环，只依赖模型和 `ToolGateway` 协议，不直连数据库，不单独启动 HTTP 服务。Backend 工厂组合 Agent 引擎、权限工具、运行控制和 AI 历史；九个 `/api/v1/agent/...` 路由由 Backend 显式挂载，包含运行状态 SSE。交接与状态规则见 [联动说明](../docs/modules/BACKEND_AGENT.md)，引擎验证见 [D07 记录](../openspec/changes/d07-create-agent/verification.md)，Android 接入与真实模型调用边界见 [Android Agent 验证](../openspec/changes/d07-android-agent/verification.md)。

实现 AI 会话与消息、问题转计划、后端只读检索工具、有效证据引用、run_id 状态、停止与错误回退。聊天和分析历史保存到 `conversations / messages / runs`，与后端共同落实存储；会话按用户隔离，不固定录像；每次运行单独保存一段录像的查询范围，同一会话不同轮次可查询不同录像。保存最终执行的语义检索文本，完成答复只存一份；停止或失败不伪造完成消息，重启后的旧运行明确标为中断失败。

## 代码结构

```text
agent/
  __init__.py          对外导出引擎、运行限制和 ToolGateway
  engine.py            生成计划、执行模型与工具循环、限制运行预算
  prompts.py           计划与执行阶段的系统提示词
  contracts.py         Backend 提供的只读 ToolGateway 协议
  models.py            查询范围、计划、答复与引用的数据结构
  tools/
    __init__.py        工具公共入口，保留 agent.tools 导入
    registry.py        工具参数校验、注册与固定查询闭包
    skills.py          内置业务 skill 目录与按名称加载的只读流程
    evidence.py        事件与帧的读取记录、引用有效性校验
  tests/               使用显式测试模型的执行与边界测试
```

工具与证据校验放在 `tools/`，提示词独立维护；HTTP 路由、后台任务、权限与持久化由 `backend/` 负责。现有 `agent.engine`、`agent.contracts`、`agent.models` 和包级导入保持不变。

## 五个只读工具

| 工具 | 作用 |
| --- | --- |
| `search_events` | 在已确认的单录像、区域和时间范围读取事件 |
| `semantic_search_events` | 使用固定 query_text 做描述文本向量检索，报告索引缺口 |
| `get_event` | 回读候选及固定关键帧 ID，检查范围归属 |
| `get_evidence` | 回读关键帧元数据与当前媒体状态，验证引用 |
| `read_recording` | 读取固定录像的来源和时长 |

录像、区域、窗口和语义文本由确认计划固定在工具闭包中，模型不能通过参数改范围。Gateway 在每次工具及完成提交时重新验证身份、权限和引用；包内还记录本次实际回读的事件／帧，拒绝引用未读、缺失或无关证据。工具读取元数据和已有描述，**没有把图片字节交给 VLM 看图**；媒体可用和引用有效也不等于视觉动作验真。相似度只作候选排序，未索引、无匹配和故障分别处理。

模型没有 SQL、文件系统、Shell 或业务写工具。会话、消息与运行历史通过 Backend 的受限服务保存；向量写入、人工核查、包裹入出库不交给模型。

## Skill 发现与调用

执行提示词提供三个内置 skill 的名称和用途，模型通过 `load_skill(skill_name)` 按需读取完整指引，再调用上述五个业务工具：

| Skill | 指引 |
| --- | --- |
| `event-evidence-review` | 普通事件检索 → 事件详情 → 逐帧回查 → 有效引用 |
| `semantic-event-search` | 描述候选检索 → 检查索引／服务缺口 → 回查事件与帧 |
| `recording-provenance` | 读取当前录像来源与时长，说明数据边界 |

Skill 由 `agent/tools/skills.py` 固定注册，不加载外部路径、用户脚本或全局 skill。`load_skill` 只返回指引，不产生事实或证据；未知名称明确返回 `unknown_skill`。加载也消耗工具预算，不能绕过固定 Query、身份／权限及引用双回读校验；计划阶段仍只返回 DraftPlan。

语义编码器明确返回 `503/model_unavailable` 时，Gateway 再次验权后将其转换为安全协议错误，工具向模型返回 `status=unavailable` 和普通查询提示。本轮后续语义尝试不重复访问故障服务，可以用 `search_events` 继续读取确认范围；最终答复和摘要卡确定性补充语义不可用说明，不伪造匹配、覆盖计数或向量。权限、范围、存储和未知异常仍终止。直接调用语义 HTTP 接口仍返回原有 503，模型本身不可用也仍失败。

语义检索即使命中候选，只要存在未索引事件，也会生成现有覆盖缺口卡。外部模型能否正确选择并遵循 skill，仍需实际验收；本地 fake 模型仅验证执行链与边界。

## 安装、启用与运行

统一从仓库 `backend/` 安装和运行，`backend/uv.lock` 锁定依赖。两个项目的 setuptools 配置分别将包 `backend`、`agent` 映射到各自项目根目录，安装时保留发行包名 `partrace-backend`、`partrace-agent`；一次同步安装两个本地可编辑包：

```sh
cd backend
uv sync --locked
uv run pytest
```

安装后通过 `backend.app`、`agent.engine` 导入，无需设置 `PYTHONPATH` 或传入 `--app-dir`。该测试命令同时覆盖 Backend 和 `agent/tests`。实际准备数据库、身份、配置见 [Backend README](../backend/README.md)，统一启动命令为：

```sh
uv run uvicorn backend.app:app --host 127.0.0.1 --port 8000 --workers 1
```

默认 Backend 提供 21 个数据操作（原有 14 个及七个 People 原片任务操作）；在私有 `backend/.env` 显式设置 `PARTRACE_AGENT_ENABLED=true` 后挂载 9 个 Agent 操作，共 30／35。契约包含 12 张表、104 个字段；P1 包裹 5 个操作尚未实现，契约计数不等于业务验收完成。

服务端 `GET /api/v1/agent/runs/{run_id}/events` 已提供受权 SSE 状态通知与终态卡片包。Android 当前每 2 秒轮询，不订阅 SSE；Agent SSE 也不承担 People／Vision 任务完成通知。

模型与 Embedding 的 OpenAI 兼容配置见 [环境模板](../backend/.env.example)。只在私有环境文件／进程环境提供密钥，不通过聊天、源码、请求正文或日志传递；缺模型时生成计划或新分析返回 503 `model_unavailable`，不使用测试模型或模拟向量回退。

首版只支持一个执行进程，使用 `--workers 1`；PostgreSQL 专用连接的 session advisory lease 防止第二个执行器误恢复正在执行的运行。默认每次运行最多调用模型 8 次、工具 12 次，总超时 60 秒，并发运行 2 个。停止先提交 stopped 再取消任务，迟到结果不写完成答复；重启将遗留 running 标为 failed／service_restarted，不自动续跑。

首次启用会生成 `.local-state/agent-cursor.key`（32 字节、0600），用于历史分页签名，须与其他本地状态一起持久保留。运行失败时遇到短暂数据库中断，会保留内存中的待终结项并每 5 秒重试；内存队列不替代 PostgreSQL，进程重启仍通过独占执行器恢复遗留运行，不恢复模型执行过程。

## 验证边界

本次 Skill 迭代实际运行 Backend／Agent 离线测试 **280 项通过，108 项数据库集成测试未选择**。使用 Python 3.12.13 与真实 `create_agent` 图，模型、权限与服务输入为显式替身，没有调用外部模型或数据库。默认图步数上限从 30 调整为 60，避免 skill 加载在既定 8 次模型／12 次工具预算内提前耗尽图节点；显式较低步数仍生效。预算失败分别返回 `agent_model_limit`、`agent_tool_limit`、`agent_recursion_limit`，超时保持 `agent_timeout`。详细命令与边界见 [本次验证](../openspec/changes/archive/2026-10-17-d07-agent-skills/verification.md)。

已经使用真实 `create_agent` 图与显式测试模型检查工具循环，并在真实 PostgreSQL + pgvector 上验证历史、权限和终态事务。[People 管线最新验证](../openspec/changes/d06-people-recording-pipeline/verification.md)记录 2026-10-17 Backend／Agent 338 项通过、无跳过；这是既有验证凭据，不作为本次 Skill 迭代的数据库或真实业务验证结果。

[Android Agent 验证](../openspec/changes/d07-android-agent/verification.md)记录了真实外部聊天模型返回可确认计划；当前输入仍为明确标注的模拟录像。独立 API 执行最终为 `failed / agent_execution_failed`，Android 确认的另一次运行最终为 `failed / agent_timeout`，尚无成功答复、引用或卡片的真实业务闭环。当前私有环境缺少 Embedding model／dimensions／key，语义检索明确返回 503 `model_unavailable`；没有该失败运行的完整工具轨迹，不能把缺少 Embedding 配置直接定为上述运行失败根因。

Android 已接入提问、计划确认、运行状态与服务端历史，失败状态在模拟器冷启动后回读显示；这不代表真实摄像头事件的 Agent 引用、人工核查保存与重启回读已验收。外部 Embedding、完整分析成功、真实视频业务效果、部署与 D10 同事件端到端仍待验证。

交付与验收见 [开发计划](../docs/DEVELOPMENT_PLAN.md)。

共享接口与数据格式见 [docs/api](../docs/api/README.md)。

运行列表、逐工具持久化日志、跨镜追踪和 P1 包裹工具尚未实现，不计入当前九个 Agent 操作。
