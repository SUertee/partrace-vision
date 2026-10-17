# 本次验证

日期：2026-10-17（Asia/Shanghai）。本地修改，未提交、合并或部署，不关闭 D07／Issue #7 或 D10。归档仅表示本次代码与离线验证交付，不表示外部模型或真实业务验收。

## 实际执行

环境：现有 `backend/.venv`，Python 3.12.13；锁定的 LangChain／LangGraph 实际执行图，显式 fake chat model、Gateway／权限替身；不调用外部模型，不读取私有凭据。

从 `backend/` 执行：

```sh
.venv/bin/python -m pytest ../agent/tests tests/test_agent_runtime.py tests/test_agent_gateway.py tests/test_model_adapters.py -o addopts='' -q
.venv/bin/python -m pytest -m 'not integration' -o addopts='' -q
```

第一项在最后补并发用例前为 **86 passed**；最后一项已包含并发用例及全部离线回归，最终为 **280 passed, 108 deselected**（2.02 秒）。`PARTRACE_TEST_DATABASE_URL` 未配置，因此显式排除需要隔离测试库的 integration 测试，没有沿用旧 PG 验证作为本次凭据。

覆盖 skill 目录、三种流程加载、未知名称和路径、不可注入 scope、skill 不产生证据、真实工具回读引用、参数错误修正、语义不可用转普通查询、最终摘要限制披露、并发与重复故障调用仅联系编码器一次、部分索引覆盖卡片、撤权／身份变化／存储错误不降级、三种预算／超时／取消及已有 Backend 离线回归。

最初新增完整 skill＋故障重复调用场景在默认图步数 30 时失败，虽仍在 8 次模型与 12 次工具预算内。将默认步数调整为 60 后通过；显式低步数仍返回 `agent_recursion_limit`。

使用本机已有 OpenSpec **1.14.1** 与 Node **22** 执行：

```sh
OPENSPEC_TELEMETRY=0 openspec validate --all --strict --no-interactive
OPENSPEC_TELEMETRY=0 openspec validate --archived --no-interactive
git diff --check
```

实际 CLI 使用已有 npm 缓存中的 `@fission-ai/openspec/bin/openspec.js`，由 `/opt/homebrew/opt/node@22/bin/node` 调用，没有安装依赖。全量规格、归档检查和差异检查均通过；变更 Markdown 相对文件链接已检查。打包沿用 `agent.tools` 与 Docker 的 `agent/tools/*.py` 白名单，未增加包、资源或依赖。

## 未验证

- 外部模型是否正确选择、加载和遵循 skill；真实 Embedding、既有失败运行的完整根因。
- PostgreSQL／pgvector 本次集成、Android 操作、真实摄像头事件与有效帧引用、人工保存及重启回读的 D10 闭环。
- 运行服务的重新加载、部署、GitHub 状态；未发送消息或修改团队任务。

公开 API、SQL 和卡片字段不变。语义 HTTP 服务仍明确返回 503；只有执行图中的已验权可选语义模型故障能够提示改用真实普通查询，权限／存储错误继续失败。
