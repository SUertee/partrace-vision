# 驿站追迹：人物建档与货架交互

2026-10-17 12:59两路最新结果已全部入库：后端修正A旧空结果后，A包免租约重传HTTP201成功，G0008/enter_zone/533～5133ms、1事件5图；B原成功结果保持G0008/shelf_interaction/第2层/1717～14178ms、1事件5图。合计2events/10evidence，逐条事件字段与证据关联、10张图片SHA回读全部一致。本次不重跑算法/Qwen，无新增模型调用。服务仍1.5.0 direct、8010免认证；此段覆盖下方A冲突状态。[最终核验记录](outputs/AB_new_scene_20261017_121839/publication_verified_20261017_125906/report.json)。

2026-10-17 12:49后端免租约接口已生效，视觉服务已更新并重启为1.5.0（10.0.5.155:8010），publication_mode=direct：直接complete发送events/evidence及图片，不调用claim/heartbeat、不发X-Lease-Token；210项测试通过。B本轮1事件/5图片已HTTP201入库，5图回读SHA一致；同包重传HTTP200/replayed=true、计数不变。A最新false方向r3包实际重传仍HTTP409/idempotency_conflict“任务已发布不同结果”，该录像原空结果仍阻止补写；用户已允许，需要后端实际允许更新这条空分析结果。此次算法/Qwen均0。此段覆盖下方双路lease_conflict与旧发布适配状态；[当前完整报告](outputs/AB_new_scene_20261017_121839/report.md)。

2026-10-17 12:33按用户最新要求：A改为布尔false，缓存轨迹更新为G0008 enter_zone（右到左，0.533～5.133秒），已重导A r3视频，0次新推理/模型复核。两路已跳过claim/租约实际POST现有complete，各1事件/5图，无Bearer或X-Lease-Token；后端均409 lease_conflict“请提供当前任务租约”，不是视觉继续做前置检查。本机两任务旧claim自动重试已停止，分析completed/回写failed，已有包和ID保留；后续需要后端接受免租约结果写入或给出新路由。当前交接见[免租约回写与实际响应](docs/视觉回写阻塞_后端修复交接_20261017.md)，覆盖下方true方向与等待精确claim方案。原服务尚保留旧发布适配，本轮免租约实传是独立脚本；不宣称全服务发布已切换或已入库。

2026-10-17 12时新场景A/B已按用户给定recording_id使用服务器原片顺序解析，最新标定为`shelf_B_2026-10-17-12-11-15.json`：B285/285帧配准有效，G0008连续交互1.717～14.178秒，首次第2层取件，Qwen一次17.401秒、自评分0.95（非准确率），保留五张原图/展示图和视频；该五帧未覆盖后续上层操作。A调高选帧质量0.40→0.65、方向持续200→800ms并重测，2条方向候选减为1条，G0008按true规则为leave_zone，配5图；G0007静坐只建档。首轮误建人体碎片G0009已从有效库隔离并保留图片/元数据及编号，有效累计8人。待回写共2events/10evidence，A后端已completed/0条需重排，B等待精确claim；线上仍仅worker_id，station_sdk_v2两路zones为空，E3接收也需后端确认。本地结果不等于已入库；详见[新场景结果与缺项](outputs/AB_new_scene_20261017_121839/report.md)，[可视化汇总](outputs/AB_new_scene_20261017_121839/comparison.html)。

2026-10-17 11:58修正货架标定显示：桌面feb1289视频确实加载了`shelf_B_2026-10-17-08-26-23.json`，原运行快照中的三个区域和参考图与该JSON逐项一致。此前可视化只画配准通过的区域，自动配准失败便隐藏全部人工框；现在保留原始人工坐标，以虚线标注“配准未通过”，不把它们当作算法已验证区域。整段配准失败时自动导出`calibration_debug.mp4`。206项测试通过；本例从缓存观测重新导出12.811秒，305帧完整解码，未重跑姿态或调用Qwen。见[修正后的可视化](outputs/B_manual_regions_visible_20261017_115802/comparison.html)。自动配准仍0/305，交互尚未判断；此前建议重新标定缺少充分依据，不能仅凭ORB失败认定人工标定错误。

2026-10-17 视觉服务1.4.0将本地解析和后端回写分离：按recording_id取片后先运行算法并保存结果，精确领取只用于后端发布；未就绪则waiting_writeback，可读取本地结果，后续只重试回写。A本地建档完成即可解除B依赖。204项测试通过，真实B本地解析49.412秒完成，但171帧仅27帧配准有效，2.5～6.8秒操作期间63帧均失效，漏掉可见交互，候选0/Qwen0；空结果已阻止发布，writeback_status=blocked_quality，需修正匹配当前视频的标定/配准后重新分析。当前已无算法前置claim等待。见[实施](docs/视觉服务_本地解析与后端回写分离_20261017.md)及[实测报告](outputs/vision_local_first_20261017_111904/report.md)。此段覆盖下方旧状态。

2026-10-17 10:25，视觉服务更新到1.3.0并已重启：持续免认证受理A/B通知、持久入队并立即202；后台按recording_id查询与直接下载指定原片，精确领取该录像后才启动算法、续租和回写。后端尚未声明精确领取时显示waiting_backend并定期重查，不再领取其他录像。200项测试通过，真实B完整下载成功；20次通知8路并发全部202，P95约164ms，本次算法/Qwen为0，回写仍待后端契约确认。当前说明见[按录像ID取片与持续受理](docs/视觉服务_按录像ID取片与持续受理_20261017.md)，覆盖下方旧领取方式及失败状态说明。

2026-10-17按用户要求取消8010所有服务认证，A/B通知、列表、状态、结果、重试均无需Bearer，启动与测试也无需通知令牌；后端8080继续免Bearer。服务已重启，用户提供的cam_sphere_b请求免令牌实测202，190项测试通过。实际B后续遇到后端claim返回其他录像，未进入算法，已请求按recording_id精确领取的接口。当前调用示例及完整边界见[免认证调用](docs/视觉服务_免认证调用_20261017.md)，覆盖下方旧Bearer说明。

2026-10-17 07:07后端修复后A/B真实结果重传成功，complete均201：A3事件/14人物图，B2事件/10交互图，合计5events/24evidence，24张图全部回读SHA-256一致，任务completed、录像published，列表与事件查询已回读。使用HTTP8080免Bearer，默认E3；本次算法/Qwen调用0，沿用原ID。成功报告outputs/lan_publication_success_20261017_070817/report.json；此段覆盖下面历史失败状态。

2026-10-17 06:53已切换免Bearer的HTTP8080并重试。A/B原结果未重算，两路complete仍422，现已明确message：“分析事件引用了此配置不存在的区域。”涉及提交zone_id=E3，而lan_video_20261017_v2两镜头zones为空。完整响应、request_id和manifest已留存于outputs/lan_writeback_retry_20261017_*/，尚未成功入库。默认配置为configs/vision_service.lan.json；此段覆盖下方HTTPS及原因未定位说明。

2026-10-17 接入10.0.4.72局域网后端：本机服务现默认用`configs/vision_service.lan.json`，通过任务media下载原片并持续续租，A后B执行，默认区域E3，最终manifest上传图片字节。真实A/B取片和算法已跑通（A3事件/14图，B2事件/10图及2次Qwen）；本次服务器complete均422，仍未成功入库。8080健康已200，业务实测使用交接包可验证的HTTPS8443，HTTP专用令牌待提供。字段、耗时、可视化、失败状态和不重跑模型的重传命令见[局域网取片与E3回写](docs/局域网后端取片_A_B处理与E3回写_20261017.md)。本段覆盖下方旧地址、旧队列与待取片状态。

2026-10-17 本机局域网通知服务已通过 `start_vision_service_lan.ps1` 后台启动，地址 `http://10.0.5.155:8010`，保留 Bearer 鉴权。调用示例、私有令牌读取、启停及当前取片/回写限制见 [本机局域网调用](docs/视觉服务_本机局域网调用_20261017.md)。此地址是私网地址，尚未从外部设备实测，不表示生产后端闭环已完成。

2026-10-17 更新 A/B 服务字段：A 必传 recording_id、camera_id、left2right_flag；B 必传 recording_id、camera_id。A 仅按人物横向位移推定进出，不需要分界线；B 默认开启主办方 Qwen3.8-Max 五帧复核。新模型地址、方向参数、展示图、详情 JSON、双 ID 取片适配和实测见 [字段确认与模型接入](docs/视觉API字段确认与A方向_Qwen官方接入_20261017.md)。生产后端仍需合入只读取片补丁并承接已有录像的结果回写；未部署。本段覆盖下方首版的默认不开启复核说明。

2026-10-17 新增本地视觉 HTTP 服务：A/B 通知立即入持久队列，A 完成建档后 B 从 `data/person_enrollment/archive.json` 加载最新累计库并冻结本次快照；重复通知去重，A 失败阻塞相关 B，支持状态查询与显式重试。入口为 `run_vision_service.ps1`，Ubuntu 使用 `.venv-track/bin/python -m station_vision.service`；服务参数在 `configs/vision_service.json`。已用现有 A/B 视频完成隔离算法测试，正式 Backend 取片及向已有录像追加结果仍待接口对齐，未部署。详见 [首版实施与实测](docs/视觉API首版_A先建档_B读取累计库_20261017.md) 和 [下一轮字段确认清单](docs/视觉API_后端对接待确认_20261017.md)。

当前主流程的重要参数统一在 [configs/vision_runtime.json](configs/vision_runtime.json)：A人物检测/选帧、OSNet与编号复用、B人物匹配、手与货架交互起止、估计手区、首次伸收转折、层号投票、五帧前后时间、货架配准和模型复核。JSON上半部分编辑数值，`_help`逐项说明范围、含义和增减影响；完整表见[统一参数与档案目录说明](docs/统一参数与人物档案_20261016.md)。修改后重新运行生效，每次保存实际参数快照。货架多边形仍使用独立标定JSON。

2026-10-16新增 **A 自动人物建档 → B 货架交互及档案归属**。一条命令顺序运行：

```powershell
.\run_AB_station.ps1 -Device cuda
# 需要五帧多模态复核时：
.\run_AB_station.ps1 -Device cuda -ReviewWithQwen
```

默认读取 `test_video_new/site_20261016/A/` 与 `B/` 中各唯一一段视频；B 标定自动选 `configs/interaction/shelf_B_*.json` 中最后保存的文件（按最后修改时间），并打印实际路径。A 使用姿态/BoT-SORT 与 OSNet，优先质量、兼顾时间和角度差异选 3～5 张原始人物裁剪；所有档案直接保存在 `data/person_enrollment/G编号/`，总索引为同目录 `archive.json`，不再使用auto_archive中间目录。每人有效代表图最多5张，多余旧图保存在本人history；旧手工档案不足3张时标记样本不足。旧编号保留，证据充分复用编号，否则新建递增 G 编号并记录可能重复。联合入口在 A 完成后把累计库传给 B；新视觉服务在 B 开始时读取累计库并冻结任务快照，事件 `person_id` 使用 G 编号，无法确认填 null；本地 P 轨迹仍用于交互与多模态人物校验。两段视频各保留自己的时间，不虚构连续时间线。详见 [A 自动建档与 B 归属实施说明](docs/A自动建档_B人物归属_实施与测试_20261016.md)，2026-10-17 的加载策略以上方首版服务说明为准。

2026-10-16新增独立的[货架图片标定工具](shelf_calibration.html)：本机浏览器选择图片或从视频截取一帧，四角框选后下载含参考PNG的JSON，可重新导入编辑。B检测入口支持`run_shelf_session_test.ps1 -Config <图片JSON> -Video <视频路径>`，逐帧配准校验后使用区域；无需用视频首帧完成标定。使用及验证边界见[B机位图片标定说明](docs/B机位_图片与视频选帧标定_20261016.md)。旧首帧配置继续支持，新图片格式仅接入简化入口。

2026-10-15新增简化交互测试入口 `run_shelf_session_test.ps1`：人物/手腕＋人工区域→连续会话→首次伸手转折与估计手区→自适应五帧→一次Qwen复核→JSON、五张原始PNG与视频。腕点只触发候选，不直接定层；中间三帧优先选首次伸手起点、峰值、收手，找不到可靠轨迹才明确标为均匀抽帧回退。Florence＋KLT可选作受控对照，不是会话触发前提。新事件为 `shelf_interaction`，公开仍为六字段，自评分及依据放详情。最新修改与实测见[自适应五帧与层号修正](docs/自适应五帧与操作来源层_修正实测_20261015.md)，[首版记录](docs/五帧货架交互_实现与测试_20261015.md)保留作历史对照。

```powershell
.\run_shelf_session_test.ps1 -Device cuda -ReviewWithQwen -RunName shelf_session_demo
# 历史视频的包裹对照需明确指定对应配置，不能套用新 B 标定。
.\run_shelf_session_test.ps1 -Config configs/interaction/manual_shelf_20261014_173804_01.json -Device cuda -ReviewWithQwen -ParcelAblation -Reference configs/interaction/reference_0fc72122_sessions_20261015.json -RunName shelf_session_compare
```

当前是单视频探索验证，未建立跨场景准确率或实时性能。连续会话可包含多个操作；算法层候选仅来自首次伸手，后续明显跨层须由复核保留未知，目前未验证自动跨层分段。下面介绍保留的旧取放复核路线，仍可运行。

当前入口是 `run_interaction_video.ps1`。`dev_florence_klt` 从 `72d0913` 建立。包裹流程为：**Florence-2-base-ft 定位 → KLT 短时跟踪 → 周期重新定位校正 → 手腕与货架状态候选 → 动作前、中、后加密抽帧 → 可选 Qwen 多帧复核 → 视频和六字段事件**。人物继续使用姿态模型和 ByteTrack。

旧 `run_interaction_video.ps1` 路线处理单个本地视频。默认包裹框由 Florence 自动生成，不需要人工包裹种子框。货架区域仍需场景配置和画面配准。Florence 只做定位，不判断拿取。KLT 校正跳变不作为运动证据；货架外观变化不作为取走证明。实时流和 CPU 分频优化尚未接入；A/B 外观关联已通过上方新入口接入。旧人工种子路线保留为显式配置，旧 YOLOE 实验代码不接入本分支。

## 运行

在本目录执行：

```powershell
.\run_interaction_video.ps1 -Device cuda -RunName interaction_demo
# 短片测试，不调用模型 API。
.\run_interaction_video.ps1 -Device cuda -MaxSeconds 10 -RunName florence_short
```

纯 CPU 使用 `-Device cpu`。此选项表示可以在 CPU 上执行当前离线程序，不表示已经达到实时。

启用 Qwen 复核：

```powershell
.\run_interaction_video.ps1 -Device cuda -RunName interaction_qwen -ReviewWithQwen
```

不加 `-ReviewWithQwen` 时，只生成观测、候选和证据，不会调用模型 API，也不会把候选直接当作 take / put / touch 结果。每次使用新的 `RunName`，程序不会覆盖已有结果。

默认配置为 [Florence/KLT 场景](configs/interaction/florence_klt_0fc72122.json)，默认视频位于 `test_video_new/`。换视频时须重新设置视频路径和货架区域，不能直接套用旧货架坐标。`shelf_zones` 可提供从下往上的分层多边形；默认只有整个货架区域，未标定层号。复核仅在可见支撑关系足够明确时给出层号。

显式运行 `72d0913` 的人工包裹框路线：

```powershell
.\run_interaction_video.ps1 -Config configs/interaction/new_0fc72122.json -Device cuda -RunName manual_baseline
```

本机 base-ft 权重已准备。新环境还需在 `.venv-track` 安装 `requirements-florence2.txt`，再执行 `-m tools.prepare_florence2 --variant base-ft`。未来测试 large-ft 时先准备该变体，再加 `-FlorenceVariant large-ft`。本轮未验证 large-ft 效果。

模型客户端优先读取环境变量 `STATION_VLM_API_KEY`，其次使用已存在的 `%LOCALAPPDATA%/StationVision/qwen-key.dpapi`。凭证不放入代码、配置或 Git。默认模型和地址沿用现有配置，可通过 Python CLI 的 `--model`、`--base-url` 指定。复核当前为离线顺序执行，尚未改成实时后台队列。

在同一个终端运行 `.\setup_qwen_key.ps1 -CheckOnly` 可检查凭证是否可加载，不发送API请求。缺失时运行 `.\setup_qwen_key.ps1`，在隐藏输入提示处粘贴密钥，按当前Windows用户加密保存。启用复核时，主流程会在视觉计算前检查凭证。已有运行名会明确报错，不覆盖结果。

视觉观测完成后立即保存 `observation_checkpoint.json`。后续证据、API或导出失败，仍可用新的运行名加 `-ObservationsRun <原运行名>` 复用已完成观测；校验配置、源视频哈希和缓存文件完整性。旧版本失败目录的恢复步骤见[凭证与失败运行恢复](docs/Qwen凭证与失败运行恢复_20261015.md)。

## 保留的结构

```text
run_interaction_video.ps1       当前启动入口
station_vision/
  florence/probe.py            已校验的本地 Florence 模型、任务与矩形框解析
  florence/observer.py         定位调度、坐标映射、手腕/包裹/货架候选
  florence/tracking.py         KLT 轨迹、周期校正、失效/歧义/到期处理
  florence/shelf.py            可见货架外观变化，遮挡/配准失败保留未知
  florence/visualization.py    源画面与标注图对照、轨迹时间图及 HTML
  interaction/pipeline.py      观测、候选、证据、复核、聚合与视频导出
  interaction/candidate.py     按人物和目标隔离的候选状态机
  interaction/review.py        提示词、人物/时间/层号校验、六字段结果
  hand_object/tracking.py      包裹短时跟踪及背景位移
  hand_object/motion.py        手与包裹运动关系的算法初判
  multiframe_review/client.py  模型 API 客户端及响应解析
  models.py                   人物姿态和 ByteTrack 适配
  contracts.py                内部人物观测结构
  registration.py             货架区域画面配准
  video.py                    视频解码和媒体时间戳
configs/interaction/           当前场景配置
contracts/interaction_event.schema.json  六字段事件契约
models/yolo26n-pose.pt         人物模型
models/florence2/base-ft/      已固定版本的 Florence 定位模型
tools/                        当前结果校验和离线重新导出
tests/                        当前候选、运动和复核逻辑测试
outputs/                      当前保留结果及后续新运行
.venv-track/                 当前唯一 Python 环境
test_video/、test_video_new/   原始测试输入，未参与清理
```

`.venv-track` 是本项目唯一运行环境。原环境已经迁移，并修复激活脚本和命令入口；后续运行、测试、校验和安装依赖均使用它。

## 输出和字段

每次运行写入 `outputs/<RunName>/`：

| 文件 | 用途 |
|---|---|
| `joint_review.mp4` | 人物、姿态、包裹、候选和动作／层号可视化 |
| `comparison.html`、`gallery/` | Florence 路线的源画面/标注图对照、速度和轨迹可用时间 |
| `localization/` | Florence 的原始输入裁剪、原始回答及源坐标框 |
| `parcel_tracking_log.json`、`florence_klt_metrics.json` | 校正/失效/到期日志与阶段耗时 |
| `report.md` | 事件与证据查看入口 |
| `events.json` | 六字段公开事件 |
| `event_details.json`、`reviews.json` | 算法初判、模型意见及冲突提示 |
| `observations.jsonl`、`candidates.json` | 原始观测和候选，可供后续复用 |
| `evidence/`、`calls/` | 原始／标注证据和模型请求／响应 |

公开事件只包含 `id`、`person_id`、`start_ms`、`end_ms`、`action`、`shelf_level`。货架从下往上编号，地面不算第 1 层；人物或层号无法确定时为 `null`。识别结果仍需复核，二维接近或共同位移不能单独证明拿取。

当前保留的完整结果是 [interaction_20261012_final_v2](outputs/interaction_20261012_final_v2/report.md)。已保存的模型响应可以离线复核，不必再次调用 API。

## 环境与验证

当前保留环境为 Python 3.11.5、PyTorch 2.6.0+cu118、Ultralytics 8.4.174，可执行 CPU 和 CUDA 推理。

`requirements.txt` 为公共直接依赖；`requirements-cpu.txt` 和 `requirements-cuda118.txt` 为两种重建方案。`requirements-lock.txt` 记录本次保留 CUDA 环境的全部已安装包版本，不包含本机 wheel 路径。

现有环境无需重装，直接验证：

```powershell
.\.venv-track\Scripts\python.exe -X utf8 -m unittest discover -s tests -v
.\.venv-track\Scripts\python.exe -X utf8 -m tools.validate_interaction_run interaction_20261012_final_v2
```

仅在新目录重建环境时，先用 Python 3.11 创建 `.venv-track`，再安装 `requirements-cpu.txt`；需要 CUDA 11.8 时改用 `requirements-cuda118.txt`。

编辑器默认解释器已在 `.vscode/settings.json` 指向 `.venv-track`。若 Trae 仍缓存旧解释器，重新选择 `.venv-track/Scripts/python.exe`。

要复用新生成的观测，可使用入口的 `-ObservationsRun <已有运行名>`；配置和视频哈希必须一致。它仍会重新进行启用的模型复核。

`-ReviewCandidates c0007,c0009` 可只复核指定候选；`-MaxRequests 2` 限制请求数。两者都会留下未复核片段，不能作为完整视频的动作结论。具体候选编号先查看该运行的 `candidates.json`。

只重新生成 Florence 图文报告，不执行推理或 API：

```powershell
.\.venv-track\Scripts\python.exe -X utf8 -m tools.florence_report <运行名>
```

要只复用已保存模型回答并重新导出：

```powershell
.\.venv-track\Scripts\python.exe -X utf8 -m tools.reprocess_interaction_reviews interaction_20261012_final_v2 interaction_reexport
```

## 文档与历史

本分支说明见 [Florence 定位与 KLT 跟踪实施](docs/Florence定位_KLT跟踪_实施与验证_20261014.md)。原有事件定义见 [人物包裹交互 v0.2](docs/人物包裹交互_最小字段与单视频实施_v0.2.md)。调研文档不代表已接入当前代码。

历史源码已保存到本地 Git 标签 `pre-mainflow-cleanup-20261013-112224`（提交 `7912131`）。该快照保存源码、配置和文档，不保存环境、权重或输出。清理清单见 [cleanup_20261013.json](docs/cleanup_20261013.json)。历史实验文档保留用于追溯，并标记为已停用。

项目实施规则见 [AGENTS.md](AGENTS.md) 和 [实施技能](skills/station-vision-implementation/SKILL.md)。可显式使用 $station-vision-implementation 调用本机已安装技能。

## 独立人物档案关联实验（2026-10-14）

新增 `run_person_reid.ps1`：用户每人几张截图→OSNet建档→B视频YOLO姿态/BoT-SORT→未匹配U编号→持续匹配后G档案编号→视频和人物JSON。对外只有matched/unmatched两种状态。原交互入口默认仍使用ByteTrack，不改变Florence、手物候选、Qwen或六字段事件。

历史手工截图入口使用 [data/person_enrollment](data/person_enrollment/README.md) 的person_001等原始输入目录；自动生成的G编号目录不会被该入口重新编号。历史用户3个人物、4张图保留为G0001—G0003少样本档案。当前A自动建档与B交互归属已由上方新入口接入，实时视频流仍未实现；历史实验不构成跨镜准确率评测。

```powershell
.\run_person_reid.ps1 -Device cuda
```

使用唯一 `.venv-track`。默认B测试视频与原新视频相同；`-Video`可覆盖，`-MaxSeconds`做短片测试，`-Realtime`只做视频原速模拟。详细模块、参数、输出与验证见[实施说明](docs/人物截图建档与视频持续关联_实施说明_20261014.md)。

当前4张截图分别为2、1、1张，使用专用少样本配置复跑（默认配置仍要求每人至少3张）：

```powershell
.\run_person_reid.ps1 -Config configs/reid/user_screenshots_sparse_20261014.json -Device cuda
```

本次实际图库结果见[关联视频](outputs/person_reid_user_gallery_20261014/person_reid_review.mp4)和[报告](outputs/person_reid_user_gallery_20261014/report.md)。
