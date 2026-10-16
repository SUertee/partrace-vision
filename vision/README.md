# 算法开发入口

主责：徐泽华。对应 D03、D04。

业务功能与表／API 交接见 [M03 视觉产物与视频入库](../docs/modules/README.md#m03)、[M05 描述向量与检索](../docs/modules/README.md#m05)。视觉输出经后端导入，不直接访问业务数据库。

具体职责、数据库表、API 调用、完整包／媒体格式与联调验收见 [Backend 与 Vision 联动设计](../docs/modules/BACKEND_VISION.md)。

当前 `dev` 已包含 A 自动人物建档、B 匿名跟踪与货架交互的真实算法源码、运行参数和已有模型，离线入口与产物说明见 [README_hua.md](README_hua.md)。最新基线已合入 Linux 字体兼容及 CUDA 12.6 依赖适配，本轮没有在 Linux 环境重新实测。视觉分析使用独立 Python 进程，完成分析后按共享协议交付完整结果包，不在 API 请求进程中执行耗时推理。

固定素材、ROI 与人工时间基准仍需按场景核对，未知区间保留未知。原算法公开事件保持 `id / person_id / start_ms / end_ms / action / shelf_level`；未经模型复核的输出是候选，匿名 G 外观关联不能证明自然人身份。Backend 的 `shelf_level` 表示自下而上的货架层级，未知为 `null`，不与货架编号 `zone_id` 混淆。描述不能补出未观测动作，Embedding 须使用共同确认的模型、编码版本及维度，模拟向量不计入真实效果。

交付与验收见 [开发计划](../docs/DEVELOPMENT_PLAN.md)。

共享接口与数据格式见 [docs/api](../docs/api/README.md)。

向量存储采用 PostgreSQL + pgvector 的原生 `vector`，由后端校验与入库；交接仍用 JSON 数值数组，统一模型版本、维度和 float32 编码。

## AWS 真实事件验证

2026-10-17独立A worker在Mac CPU运行，经 `https://35.84.190.14` 的受限API领取、下载、续租和发布。Aurora快照A completed 5段（4新自动、1历史），App回调已确认；B自动原片2段pending/queued，缺现场标定和区域映射。Mac或worker停止后原片可继续入库排队，AWS API不会自行推理。公网接通不代表B算法、生产运行或D10验收完成，worker增量源码仍待合入dev。详见[AWS部署与验收](../docs/deployment/AWS_PEOPLE_20261017.md)。

## 此前本地验证，待合入 source-first

以下是 2026-10-17 本地开发工作区的验证记录，**相关 API worker、原片队列、Android 协调器及其 SQL／API 增量尚未随本次文档发布合入 `dev`**。当前 `dev` 的 Backend 仍使用既有离线完整结果包契约；本段不提供克隆后即可运行的 worker 命令。代码交付范围与最新状态统一见 [项目状态](../docs/PROJECT_STATUS.md)。

本地 source-first 实现由 App 取得真实 SDK People 完整录像，经 API 先保存待解析录像与持久任务，独立 worker 通过受限 API 领取、下载、续租及提交结果；Vision 不直连业务数据库。原生 People 只表示任务来源，不能直接生成区域动作或拿取事实。

- **真实 A：** 门铃的一条历史 HUMAN 录像明确标为手动历史验证，时长 30.466 秒；真实 CPU 算法运行 44.902 秒，得到 5 条匿名局部轨迹和 10 张内部选帧。App 下载、API 上传、Backend 最终发布与 App 完成提示已闭环。A 建档不伪造区域动作，因此业务事件／证据计数为 0／0；内部选帧不冒充已发布业务证据，零计数不能解释为现场无人，人物身份未验证，录像 UTC 起点仍为 `null`。
- **图库与回调：** 本地实现按任务隔离 A staging 图库，仅在 Backend 确认相同任务／attempt 已完成后推广已提交图库。App 的 completed 回调只保证 PostgreSQL 事实已提交，图库 CURRENT 随后推广，不能承诺 B 此时已读取新档案；数据库与文件系统之间不是同一原子事务，中断后需要受权核对与恢复。
- **真实 B：** 本地最终核对时有 12 条 B 原片处于 queued，是该时间点的持久任务快照，不代表解析完成。当前球机画面缺匹配的现场货架标定及区域映射，不能套用仓库其他场景的标定。既有 v1 配置保持不可变；新标定须发布新配置版本，旧 v1 原片不会自动改用新版本，需要另行确定迁移策略。

本地链路仅在 App 前台且 SDK 已登录时运行；Android 系统后台常驻、完整 B 真实算法链、生产部署以及 D10 的同一事件检索／引用／人工核查闭环均未验收。本轮 A 未调用外部模型；完整远程 LLM／Embedding 业务效果另行验收。
