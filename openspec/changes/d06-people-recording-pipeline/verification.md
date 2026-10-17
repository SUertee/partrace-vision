# 本地验证 · 2026-10-17

## 先入库后解析 · 2026-10-17 最新验证

按用户最新 docs 和顺序要求，当前 HEAD 已包含 dev@97067ce 的 Agent 卡片/SSE。公开原片入口已改为 `POST /api/v1/recordings` multipart `mode=source + manifest + files/source.mp4`；旧完整结果模式默认 result，201/200 兼容。接收时同事务创建 pending recordings 和 queued recording_jobs；Vision complete 在同事务发布事件/证据、published 和 completed，App 回读持久终态后回调。Agent run SSE 不用于 Vision 完成。

- Backend + Agent 本轮：**338 passed、无 skip**，真实隔离 PostgreSQL schema 与实际 ffprobe/ffmpeg。覆盖 source 202/200、跨兼容入口重试、pending 优先于区域/UTC/模型拒查询、原子完成与失败、迁移保留/回滚。
- Vision 本轮：**101 passed**，worker 协议保持兼容。App completed 保证 PG 事实已提交；A 图库 CURRENT 在其后推广，不能承诺此时 B 已读取新档案。
- 已在本机实际 development 库执行 `migrate-source-recordings`：迁移前2条已发布录像（1条真实 SDK A、1条已有 mock）与11任务；迁移后12录像=2 published+10 pending。所有旧录像列值、11任务全部选择列（含来源事件、状态、attempts、原片键、请求/结果摘要）逐值不变，10条 B 原片无需重传。
- 新公开入口对同一真实 A/B 原片各重放一次：均200、同 ID/attempts；A仍published/completed，B仍pending/queued。真实 B 的 events 与 events/search 均在区域/编码器校验前返回409 recording_not_ready；默认列表仅published。本轮未伪造新摄像头事件。
- 结构从独立临时 PostgreSQL pg_catalog 重新导出：**12表/104列**；OpenAPI **35操作、91组件schema、54JSON fixture**，schema元结构与fixture精确校验通过。临时结构库已清理。

- Android 最终恢复修复：**15 项相关单测、assembleDebug、lintDebug 全部通过**。安装后原10条 accepted、本地误标 failed 的任务自动恢复回读，真正终态不复活；上传/下载仍保留5次上限，已受理任务仅状态读取持续退避、最长120秒。保留 SDK 登录、私有配置、稳定ID、完成回调标记，未手工修改私有任务快照。
- 最终APK SHA256：`efdef983228aed493760c24af57e5c5a2a72c5d18ab37e996adcf5544f705e7b`。已增量安装并使用真实已登录模拟器验证。本轮自动发现的一条新B People任务从App公开原片入口成功入库，处于pending/queued；没有手动虚构SDK事件。
- 五项设计同步检查、91组件schema/54fixture、10份改动文档的本地链接、OpenSpec严格检查及已跟踪diff检查通过。六份文档的合并冲突已保留双方有效改动并解决。

- 通过任务页原有“重试”恢复停机期间尚未上传的一条真实录像，未修改私有快照。最终13条App任务全部已受理：1 completed、12 queued、0 failed；逐条与PostgreSQL任务和published/pending阶段一致，原A完成回调标记保留。B仍等待现场标定/区域映射。最后屏幕同时被另一项Agent页面验收使用，本轮不以其截图作为People首页凭据，不打断该运行。

以下为原片任务初版的历史验证，时间点早于本次 source-first 修正；其中旧接口、计数和APK哈希仅作为历史凭据。

## 初版范围与真实依赖

用户确认 `recoding` 指现有 `recordings` 表；门铃为 A（人物建档），球形为 B（货架交互）。按用户要求已安全快进合入最新 `origin/dev` 的 `eb5de72`，包含真实 Vision 算法与 Aurora IAM 数据库连接；本地界面、设备接入及其他既有未提交修改保留。没有提交、推送或部署远程服务。

`POST /api/v1/recordings` 继续接收已完成分析的完整结果包，不因单独收到视频就自动推理。新增七个 `recording-jobs` API 接收原片、调度独立 Vision worker，并复用相同发布逻辑在一个事务内写入 `recordings/events/evidence` 和任务完成状态。原生 People 只作为任务来源，不升级成区域动作事实。

## 已执行

- Backend + Agent：真实独立 PostgreSQL `*_tests` 库、随机测试 schema，实际 ffprobe 8.0 ARM64 与 ffmpeg；全量 **330 passed，0 skipped**。覆盖真实 MP4/PNG 校验、重放与同原生事件冲突、重启、并发 claim、续租/过期重领、旧租约、撤权、媒体期间到期、事实冲突整体回滚、迁移保留原事实，以及失败任务显式重排。
- Android：JDK 17、Gradle 8.13、SDK 36，`assembleDebug`、`lintDebug`、PeoplePipelineLogicTest（8 项）和 SdkDeviceOrderTest（3 项）通过；APK 已安装到 API 37 的 `emulator-5554`，保留已有 SDK 登录和两台真实设备。
- PostgreSQL 结构：新建独立目录校验库加载完整 DDL；从真实 `pg_catalog` 导出 **12 表 / 102 列**，生成关系图、API 页面及离线工作台。接口契约共 34 操作，Backend 默认 21，启用 Agent 后 29；P1 包裹 5 操作仍待实现。
- 本地开发库：只执行新增任务表的显式迁移，迁移前后 `recordings/events/evidence` 条数保持一致；新增最小范围的测试用户、镜头配置与私有令牌，不修改既有认证配置。新测试 API 绑定 `127.0.0.1:8001`，保留原 8000 服务。
- Vision：在 Git 忽略的 `vision/.venv-track` 准备 macOS CPU 环境，沿用原算法 pins 与现有权重；安装 CUDA 不在本次范围。算法与 worker 的全套 **101 项 unittest 通过**；另用明确标注的合成蓝色视频实际加载现有 YOLO/OSNet 权重执行 A（CPU 3.903 秒），验证 0 人空图库可生成/读取。合成烟雾未调用业务 API 或数据库，不当作真实摄像头链路凭据。A 图库改为每任务 staging，只在 Backend 确认相同 attempt 已 completed 后原子切换本机 CURRENT；B 只读已提交版本。失租、撤权、失败和完成状态未知不污染共享图库；本地推广中断有受权 reconcile 入口，未声称文件系统与 PG 跨系统原子。

私有令牌、设备映射、下载视频、真实截图、算法产物和测试日志均留在忽略目录、App 私有不备份目录或临时目录，不作为源码交付。

## 真实链路与限制

- 真实联调修复了三项问题：SDK 播放器创建前静音触发空指针；新进程下载前缺原生媒体初始化，采用官方回放就绪后静音/暂停再下载；SDK 原生事件 ID 是含分隔符的 opaque 字符串，不能按 URL 资源 ID 限制，现原样保留并拒控制字符/全空白。
- 首页显示两台真实设备在线；新增配置页和历史手动验证入口已在运行 App 核对，A/B 与后端 camera_id 映射只写私有配置，不硬编码设备 SN。
- A 通过真实 SDK 查询近 24 小时 HUMAN 历史录像，明确标记“历史手动”。取得真实 30.466 秒 MP4（1,741,415 字节），从 App 上传到本地 API 后，真实 CPU A 算法执行 44.902 秒，生成 5 条匿名局部轨迹与 10 张内部原始选帧，未调用外部模型、未验证自然人身份。Backend 任务同一次尝试完成，最终 `recordings` 为 `source_mode=sdk / duration_ms=30466 / started_at=null / media_status=available`；A 不生成区域动作事实，因此业务 event/evidence 计数均为 0，不能解释为无人。App 已回读 completed、完成提示已投递，私有下载副本按规则清理；已提交图库 CURRENT 指针存在。
- B 的真实新 HUMAN 事件已由前台自动发现，5 条完整录像已全部通过 App 上传并在 PostgreSQL 持久排队，时长分别为 10.048 / 6.016 / 5.454 / 5.926 / 18.185 秒，均 `queued`、无错误。它们原先因本次开发期的 SDK/事件 ID 问题失败，修复后对同一稳定任务重试，没有新建重复来源。B 仍缺与当前球机画面匹配的现场货架标定和 zone_id 映射，不能套用其他场景参考图；当前只授权 A worker 领取 A，B 不宣称解析完成。完全无有效配准帧会返回 `calibration_invalid`，不得发布无检测成功。当前 v1 的 B 区域为空；未来现场配置须发布新版本，新事件使用新版本。已保存这批 v1 原片不会自动跟随新版本解析，须另行明确配置迁移，不能覆盖旧版本或悄悄改任务元数据。
- 自动发现与上传在 App 前台且 SDK 已登录时运行。初次启用按时间基线处理新事件；下载/上传任务可在进程重启后恢复。未实现 Android 系统后台常驻、远程系统推送或生产 API 认证。
- SDK 时间只记录来源 `sdk_timestamp_ms`，没有独立校准；最终录像 `started_at=null`。A 建档不伪造区域动作，B 仍发布算法候选，不认定身份、包裹归属或责任。

此记录不关闭 D04/D05/D06/D10，不把本地构建与生成媒体测试表述为真实视觉效果验收。完整设备链路及现场标定未验收前保留提案，暂不归档。


## 最终本地交付状态

最终 APK 已增量安装并停留首页，状态卡实测显示“待处理 5 · 已完成 1 · 失败 0”和“最近一段录像已解析完成”；已去掉首页技术 camera_id 展示。首页验收图仅保存在被忽略的 `android/ESIotKit-DemoApp-Android/app/build/outputs/ui-checks/people-completed-home.png`。最终 `assembleDebug / lintDebug` 通过；相关逻辑回归 11 项、Backend/Agent 330 项、Vision 101 项均通过。五项设计同步检查、86 个组件 schema / 53 个 JSON fixture、链接、OpenSpec 严格检查及 diff 检查通过。

APK SHA-256：`ad1820f282b3efc14a6f2a364e223cf60d54c7a530e41c1edfbc91526377ecc4`。

本次开发使用的独立结构校验库已清理，实际开发库与私有原片保留。A API worker 与联调 API 只在本机运行，未部署远程，也未推送代码。B 现场标定/区域映射问题已向用户提出，等待真实输入后继续其配置版本与算法验收；当前不归档提案。

## Aurora 公网与真实自动事件（2026-10-17）

本轮按用户要求将实际链路接到35.84.190.14的Aurora `partrace/public`。新API基于云端dev_hua@39aa4ab兼容本地任务实现，保留shelf_level/可空zone_id及云端UTC规则，发布包与源码冻结留在受限私有目录。没有覆盖服务器Git工作树，没有宣称已合并dev。

- 公网IP受信任HTTPS已签发；Nginx只代理API/health/live到新People容器127.0.0.1:8001，旧API8000保留。Agent关闭，实际21操作；Aurora IAM=true，development鉴权，不代表正式production认证完成。
- 原库已有5条recordings、3条events、1条evidence、2用户。迁移前保存全表快照，执行001/002；迁移后原11表全部旧行逐值一致。实际物理结构12表/104列，包含shelf_level、尚无cards_json，与本机同字段数量不能当作相同结构。新App/worker身份使用独立令牌摘要和精确镜头授权，不复用本机demo凭据。
- 云端兼容代码对真实隔离PostgreSQL及实际ffprobe/ffmpeg的People任务集成检查15项通过，无skip。初次临时目录缺fixture的失败准备已纠正，精确清理该次15个空随机测试schema；后续正式测试全部通过，不触碰业务数据。
- App保留原14个本机任务与SDK登录。通过UI切换公网地址，模拟器键盘未完整保存凭据时，仅修正私有settings.bearerToken并校验所有任务/seen不变；未改业务状态、来源、完成回调或伪造事件。旧目标任务保持原targetKey，配置变更后未被重定向。
- 用户选择已有真实历史录像验收。App从SDK历史查到未导入的A HUMAN原片，真实下载22.266秒，经公网POST /api/v1/recordings source入口受理；直接查询Aurora先为pending/queued。任务ID：`20ac5b8bf48bee8817fb68195b823f81a3700314530bbe69b184067348749844`。
- 独立本机CPU A worker通过公网领取、下载、推理、发布，实际算法36.741秒，4条匿名局部轨迹，1个达到质量门槛的新档案/5张内部原始选帧；identity_human_verified=false，未调用外部模型。最终Aurora published/completed、attempts=1，finished_at=2026-10-16T18:53:29.095882Z；A建档业务event/evidence=0/0，不代表无人。图库CURRENT已在后端完成确认后推广。App同ID status=completed、remoteAccepted=true、completionDelivered=true、origin=manual_history。
- 随后持续worker实际处理了一条**新自动A事件**：`e815c058d25d9118ddfd7e1c3643b64ec090ad099157ba4dc721ea41f8b9a05a`，30.066秒，Aurora published/completed，accepted_at=2026-10-16T18:56:45.611Z、finished_at=2026-10-16T18:57:40.998799Z。App origin=automatic、completed、completionDelivered=true，证明新硬件事件触发至回调全链路，不仅是历史手动。以上时间为API受理/完成时间，录像started_at仍null，不能冒充SDK UTC已校准。
- 球机B自动新People录像已由App入库：`76a98318bb7f8bd9da325abd78f18e338818659356cf83bfbc71cf6fc61dedd4`（10.507秒）、`3393f0f1cf3e57b4d4b6954f1bac8993ceead8572cebfb41aec02ca556cf3d2b`（12.055秒），Aurora pending/queued、原片available，App origin=automatic/remoteAccepted=true。B尚缺现场标定与zone映射，A worker的授权和配置均排除B，不伪造B completed。后续自动事件数量可继续增长，以上是实测时间点。
- 另一个自动A来源`9841a9d3a765…`的29.199秒SDK原片实际有H.264 SPS/帧错误，后端严格解码返回422 Media could not be decoded，首次提交未创建Aurora录像/任务。保留原片与错误诊断。新增用户显式“重新下载原片”恢复：仅未受理的422源可丢弃本地坏副本重新下载；稳定ID、SDK来源、目标和完成标记保持；已受理/完成、409冲突不替换原片。
- Android最终JDK17 assembleDebug/lintDebug与13项People目标测试通过，增量安装保留数据。运行环境为已登录模拟器连接真实摄像头，非Android真机验收。

Aurora查询SQL与部署边界见[运维说明](../../../infra/people-aurora/README.md)和[查询SQL](../../../infra/people-aurora/verify-recordings.sql)。原片字节在EC2受限媒体目录，PG保存元数据/状态/存储键。当前A worker持续运行在本机，Mac/worker停止后只保留入库排队能力；B现场算法解析和完整D10仍未验收，不归档本提案。

### 本轮最终恢复与数据快照

通过App新入口对`9841a9d3a765d58846a449566da6d10c7373c719ca0bf8fe83343c38e93de33e`显式重下一次，原SDK事件及任务ID不变；重新取得30.599秒完整可解码原片，API202后真实A worker解析，Aurora published/completed，finished_at=2026-10-16T19:06:58.851417Z。App远程受理、completed与completionDelivered均为true，error=null；未手工修改任务、回调字段或后端结果。首次29.199秒异常副本与请求诊断保留，未放宽严格解码。

最后一次直接Aurora查询：A completed 5段（1历史手动+4新自动），B queued 2段（自动），原5条非People录像仍在；新自动数据可继续增长。App任务页实测展示新自动A的“解析完成·事实已发布”和B的“原片已入库·待解析”，截图位于忽略的app/build/outputs/ui-checks/aurora-people-completed.png。新自动A包括e815c058d25d、c8a6d13cd0fc、78cb969a0647及恢复后的9841a9d3a765。

期间少量状态读取/上传出现暂时503；复测公网user/任务均200，容器restart=0/OOM=false，既有持续退避回读和上传重试恢复，未将状态读取失败误标成Vision失败。最终APK摘要保存在私有aurora-pipeline/apk.sha256，构建/lint、13项People测试、15项真实PG任务测试、OpenSpec严格验证和相对链接/diff检查通过。A持续worker已验证仍运行；本轮不添加Mac开机常驻或AWS算法运行服务。
