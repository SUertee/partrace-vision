# Proposal

## Why

驿站追迹已有受限 Agent、单执行器运行控制、本地媒体与 PostgreSQL 视频任务，但外部模型完整执行、语义索引和云端排障尚未形成可重复验收。需要让 AWS 承接模型、证据存储与可观测性，同时保持 Android、Vision、事实事务和人工核查的既有边界。

日期：2026-10-17（Asia/Shanghai）。本 change 是待评审设计；本轮仅生成规划产物，没有创建云资源、修改业务代码或完成 AWS 联调。

## What Changes

- 一期增加显式 Bedrock Chat／Embedding provider，复用 `create_agent`、内置 skill、固定 Query、预算及证据校验；增加受限的事件描述编码／索引作业、按模型空间重建和切换流程。
- 一期抽象本地／S3 媒体存储。保留逻辑 `storage_key`、现有鉴权媒体 API 和 worker 协议，定义对象发布、数据库提交不确定性、迁移、回滚及孤儿清理。
- 一期增加结构化运行日志、CloudWatch 日志采集、低基数指标、排障查询和告警；日志故障不改变业务终态，业务审计仍以数据库为准。
- 一期沿用 EC2 单执行器与 Aurora PostgreSQL／pgvector，核对实际部署版本、IAM、TLS、迁移与恢复。历史 HTTPS 记录曾记载远端使用 Aurora，而 Backend README 仍称联调未执行；本次不核验远端，按部署清单重新验收。
- 一期真实服务／D10 验证限定为受控 development 演示环境；现有业务凭据仅支持该环境。正式 staging／production 身份接入由独立变更完成，在此之前保持原有拒绝，不能将本次 AWS 接入称为生产上线。
- 二期只给出 SQS 与 AgentCore 的独立接入条件：任务状态仍以数据库为真源，Agent 执行归属、身份与图库文件锁须先完成分布式设计。本 change 的实施任务不启动二期服务。

## Capabilities

### New Capabilities

- `aws-model-providers`：Bedrock 对话与双端语义编码、模型空间、provider 选择和失败边界。
- `evidence-object-storage`：可替换媒体存储、受权访问、事务发布、迁移及安全回收。
- `runtime-observability`：跨 API／Agent／Vision 的受限诊断事件、CloudWatch 指标与告警。
- `aws-deployment`：AWS 配置／身份边界、单执行器分阶段上线、回滚和真实验收。

### Modified Capabilities

无。已有 [agent-skills](../../specs/agent-skills/spec.md) 的白名单、范围、故障区分与引用要求保持不变；本 change 新增 provider／存储／运维能力，不改写其要求。D05／D07 的在途规格继续作为兼容约束。

## Impact

- 工作包：D05／[Issue #5](https://github.com/SUertee/partrace-vision/issues/5)、D07／[Issue #7](https://github.com/SUertee/partrace-vision/issues/7)，协作 D04／Issue #4、D06／Issue #6、D08／Issue #8；D10 验收另留真实凭据。Issue 编号沿用现有提案，本次未远端查询或改状态。
- 模块：M03 原片与发布、M04 事件／证据、M05 向量、M06 会话／分析、M07 人工核查；Backend／Agent 主改，Vision 适配媒体读取与索引交接，Android 一期接口兼容。
- 受影响入口：`backend/ai/providers.py`、`services/semantic.py`、`storage/media.py`、`services/facts.py`、`services/recording_jobs.py`、`core/settings.py`、`ai/runtime.py`、`vision/worker.py` 与 `infra/`。依赖拟增加 AWS SDK／LangChain Bedrock 适配，实施时固定兼容版本并更新两份锁文件和镜像清单。
- 一期目标不增加公开 API、业务表或卡片字段；通过内部存储接口和管理入口完成。原有模型配置默认兼容；外部调用目标及新配置语义在设计中定义。若实现确需字段变更，先修订本提案并同步契约、迁移、样例与离线图。
- 依据：[模块联动](../../../docs/modules/BACKEND_AGENT.md)、[Vision 联动](../../../docs/modules/BACKEND_VISION.md)、[SQL](../../../docs/database/schema.sql)、[OpenAPI](../../../docs/api/openapi.json)、[v0.3 样例](../../../docs/api/examples/v0.3/)。
- 不包含：生产身份系统替换、VLM 看图、Rekognition 替换算法、跨镜／包裹业务、自动多实例扩容、静默模型回退、为既有 EC2／Aurora 重建资源。当前本地代码／文档与远端部署可能不同，不能据设计宣布业务已上线。
