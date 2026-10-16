# Tasks

本表仅跟踪一期实施与验收，当前全部未实施。范围见 [proposal.md](proposal.md)，顺序与回滚见 [design.md](design.md)。二期 SQS／AgentCore 不属于这些任务的完成标准。

## 1. 配置、依赖和部署基线（G0，D05／D07）

- [ ] 1.1 固定本地基线与拟部署 revision／image／schema，建立脱敏部署清单，区分已有 Aurora／HTTPS 历史记录和本次实查；验收：清单列出实际 API／Agent 状态、已知差异和未核验项，不初始化或替换既有数据库。
- [ ] 1.2 在 Settings 增加独立 Chat／Embedding provider、Bedrock target／region、媒体后端及监控参数，旧配置默认不变；验收：配置测试覆盖无 API key 的 Bedrock、缺字段、长 ARN、非法组合与禁止客户端覆盖。
- [ ] 1.3 固定兼容 boto3／botocore／langchain-aws，更新 Backend 锁文件、导出 requirements 与镜像清单；验收：从 `backend/` 执行 `uv sync --locked` 成功，Agent 包无新增 AWS 强依赖，镜像导入检查通过。
- [ ] 1.4 更新 Backend 环境模板和 README 的 provider／身份／region 说明，仅用占位配置；验收：按文档构造旧 provider 与 Bedrock 配置均能完成本地校验，不触发模型或资源创建。

## 2. Bedrock 对话与既有 Agent（G1，D07）

- [ ] 2.1 拆分 provider 工厂，注入 ChatBedrockConverse 并保持原引擎与 ToolStrategy；验收：SDK／模型替身覆盖 DraftPlan、load_skill、五个只读工具和 CandidateAnswer，旧 OpenAI 回归通过。
- [ ] 2.2 加入多轮／并行 tool_use 与 tool_result ID 验证及安全错误分类；验收：未知工具、畸形返回、固定范围外参数、未读引用均明确失败，不发布伪答复。
- [ ] 2.3 实现 SDK 有界超时／线程池、无自动重试及停止后的迟到结果隔离；验收：停止、总超时、模型／工具／图步数预算测试通过，迟到返回不开新调用、不覆盖终态。
- [ ] 2.4 为 run 固定安全模型配置身份，完善 provider 中立的缺配置错误；验收：model_key 能区分 provider／region／修订，旧历史可回读，运行中改变配置只影响新 run。
- [ ] 2.5 更新 Agent README、M06 与 Backend／Agent 联动说明；验收：文档区分模型适配成功、真实工具链成功和业务验收，执行 `uv run pytest` 中 Agent／provider 相关测试通过。

## 3. Bedrock 双端编码与事件索引（G1，D04／D05／D07）

- [ ] 3.1 拆分查询／文档编码意图，为旧 provider 保持兼容，实现 Cohere Embed v4 文本检索协议；验收：SDK 替身核对两种 input type、维数、响应数量、finite float32 和非零校验，未知 family 明确拒绝。
- [ ] 3.2 版本化来源描述模板及模型空间，保留 candidate／来源／未知信息；验收：模型／recipe／维数变化产生隔离空间，事件与帧 ID 不变，描述中没有生成的新动作或身份断言。
- [ ] 3.3 增加受权 `event_indexing` 服务与显式 `index-event-descriptions` 管理命令，从受保护文件读取业务 Bearer；验收：仅 IAM／DB 身份、缺凭据或失效 token 均不能索引，凭据不入 argv／日志，dry-run 不调模型不写库，范围／batch／limit 有界。
- [ ] 3.4 复用向量写入服务，完成编码前去重、编码后重新验权／来源校验、并发冲突回读和逐项恢复；验收：重跑跳过已写来源，部分失败有真实统计，mock 不能索引真实录像，撤权后不提交。
- [ ] 3.5 保持覆盖／错误语义及旁路索引切换；验收：无索引不调查询模型、范围外／无匹配／部分覆盖／模型不可用／存储错误分别测试，新旧空间从不混算，旧配置可恢复旧空间。
- [ ] 3.6 更新 M05、编码／重建命令说明和 Backend README；验收：本地测试可重现“描述→文档向量→同空间查询”，独立标明真实 Bedrock 尚未验证，`uv run pytest` 相关测试通过。

## 4. 媒体内部接口与本地兼容（G2，D05／D06）

- [ ] 4.1 抽象存储协议与受限 read／materialize／prepare／commit／rollback，保留本地实现；验收：现有本地上传、解码、路径、幂等及事务测试保持通过。
- [ ] 4.2 将 facts、任务原片下载及完成复用从直接 Path 访问迁到协议；验收：SDK 原片复用仍验证 video_sha256，临时文件退出清理，稳定 key／DTO／媒体响应保持兼容。
- [ ] 4.3 区分确证缺失／损坏与存储依赖异常，修正吞 ApiError 的存在性判断；验收：missing／media_missing 与 503 分别覆盖元数据、读取和 Agent 引用，依赖故障不被降级为缺图。
- [ ] 4.4 保留鉴权代理、当前用户／录像／租约校验及现有读取响应行为；验收：Vision 不跟随重定向仍可下载，撤权／旧租约拒绝，当前 200 完整字节响应、内容类型和 no-store 规则回归通过，一期不新增 Range／206 合同。
- [ ] 4.5 更新 Backend／Vision 联动、M03／M04 和存储说明；验收：从 `backend/` 执行 `uv run pytest`，从 `vision/` 执行 `.venv-track/bin/python -m unittest discover -s tests -q`，只将协议替身归入离线结果。

## 5. S3 对象适配与发布协议（G2，D05／D06）

- [ ] 5.1 实现显式 S3 后端、受控 prefix 和不可变条件写入，沿用本地 staging 解码及 SHA256；验收：SDK 替身测试超限、同键异内容、部分上传、校验失败及禁止静默 local 回退。
- [ ] 5.2 在长 SQL 事务外完成上传并将 commit 定义为对象齐全屏障；验收：任何未上传／未验证对象不能获得 DB 引用或 completed，原事实和任务提交原子性不变。
- [ ] 5.3 定义确定回滚、COMMIT 不明与共享原片的清理集合；验收：提交失联保留对象，重放按既有摘要核对，旧租约和失败请求不能删除共享／已引用媒体。
- [ ] 5.4 实现受控读取／完整性缓存，按对象版本／校验与大小失效；验收：不把 ETag 当 SHA256，访问拒绝／超时／5xx 为依赖错误，未知对象不能成为有效引用。
- [ ] 5.5 增加迁移 manifest 与维护窗口 GC 的 dry-run 管理入口；验收：execute 必须核对 API／worker 全部停写退出、无未决发布事务／上传及 COMMIT 不明，缺维护证明或 DB 不可读时拒绝删除；三表全部引用含 pending／failed 均保留，不按年龄／tag 删最终对象。
- [ ] 5.6 更新存储配置、迁移回滚及故障排障文档；验收：明确 S3 新写后的回滚条件和无跨系统事务保证，`uv run pytest` 的本地／SDK 替身媒体测试通过。

## 6. 诊断事件和 CloudWatch 配置（G0／G3，D05／D07／D06）

- [ ] 6.1 在 HTTP 请求边界生成安全单行 JSON，捕获服务端 request ID 和路由模板；验收：诱饵凭据、用户问题、签名 URL、异常原文和私有路径均不出现在访问／错误日志。
- [ ] 6.2 让 Agent observer、异步运行失败和 Vision attempt 阶段具有可关联诊断；验收：origin request ID、run／job／attempt 与终态 error.request_id 可查，固定 tool／skill 名有耗时，后台完成与图库推广分别可见。
- [ ] 6.3 实现低基数指标、真实 usage／unknown 和提交后终态计数；验收：大量随机 run／job ID 不增加维度，COMMIT 不明不算成功，幂等重放不重复算业务完成。
- [ ] 6.4 增加有界轮转／缓冲、采集缺口和恢复摘要；验收：CloudWatch 失联与缓冲满不改变业务／终态、不无限占用资源，恢复不补造丢失的调用轨迹。
- [ ] 6.5 编写 CloudWatch 日志组／采集配置、metric filters、dashboard 和告警；验收：模板离线校验通过，心跳缺数与低流量错误率缺数行为有明确配置，日志保留、维度数量和通知目标可评审。
- [ ] 6.6 更新运维字段契约、排障查询和保留／费用说明；验收：用显式 fixture 演示 HTTP→run→tool 和 job→attempt→gallery pending 关联，Backend／Vision 相关离线测试通过，不上传原文诊断。

## 7. AWS 测试环境与分阶段切换（G0–G3，D05）

- [ ] 7.1 完成脱敏部署参数清单：账户／各服务区域、Cohere target／维数／recipe、Chat target／alias、bucket／prefix、日志保留、预算／阈值和恢复窗口；验收：一期明确为受控 development，非 development 仍拒绝旧凭据，真实调用使用明确目标并记录数据处理区域，无待决定的架构项。
- [ ] 7.2 编写仅覆盖 S3／监控／角色策略的 CloudFormation 模板与 Compose AWS 配置；验收：模板与 change set 不创建／删除／替换既有 EC2／Aurora；对象动作限定 prefix，ListBucket 按 bucket ARN 与 prefix 授权；确证缺对象和实际 AccessDenied 分别测试，不授予模型业务写权限。
- [ ] 7.3 在实际容器中验证 IMDSv2／临时凭据刷新、应用 DB 用户、Aurora verify-full／CA 和 pgvector；验收：错误主机名／CA／IAM 拒绝，正常新连接可刷新，DB 备份及 cursor／配置／图库恢复演练有记录。
- [ ] 7.4 对明确的 AWS 测试资源执行 Bedrock 计划／skill 工具链与文档／查询编码 smoke；验收：真实有效引用和同空间检索成功，记录安全 request ID／版本／usage，IAM 拒绝、超时和限流失败可定位。
- [ ] 7.5 对测试 bucket 验证私有访问、条件写、原片／关键帧回读与校验，再以短维护窗口迁移全部当前引用；验收：任一缺失／哈希冲突阻止切换，恢复前 local→S3 和有新写后的回滚条件分别演练。
- [ ] 7.6 启用真实日志采集、指标与选定告警测试目标，按顺序交接单执行器；验收：日志无敏感原文，采集缺口／告警恢复可见，第二执行器被拒绝，旧 running 重启失败且无自动模型续跑。

## 8. 同事件集成、交接与范围确认（G4，D10）

- [ ] 8.1 重跑 Backend／Agent 全套测试及隔离 PostgreSQL／pgvector 集成；验收：在 `backend/` 使用 README 的 `uv run pytest`，真实集成只使用专门测试库，记录通过／失败／跳过及环境，不沿用旧 280／338 项数字。
- [ ] 8.2 用同一真实已发布区域事件完成 S3 回读→真实描述编码／检索→Agent 引用→Android 开帧→人工保存→重启回读；验收：Event／Evidence／run／review ID 一致，候选和覆盖限制保留，失败／缺图／取消／撤权各有实际结果。
- [ ] 8.3 演练停止新分析、排空／停止旧 run、释放执行锁、版本／配置回退和媒体／空间恢复；验收：无重复模型执行、无丢失证据、无覆盖人工记录，CloudWatch 能解释每次交接结果。
- [ ] 8.4 更新模块 README、开发计划和交接记录，分别汇总离线、真实 AWS、真实视频、Android 与未验证项；验收：OpenSpec 全量／归档校验、相对链接及 `git diff --check` 通过。若实际扩大 SQL／API 范围，先完成契约／迁移／样例与五项设计同步检查再验收。

## Workflow follow-up

- 仅在上述实际交付与所需真实验证完成后归档；设计文件齐全不代表实施完成，不自动关闭 D05／D07／D10 Issue。
- 二期另建 SQS change，采用唤醒＋保留 DB 轮询；取消轮询前先设计同事务 outbox。另建 AgentCore／图库分布式 change，先解决 run owner／fencing／取消、身份链和图库版本，再评估托管扩容。
