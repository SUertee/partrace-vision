# 当前项目地图与执行方法

默认根目录：`C:/Users/12908/Desktop/黑客松/安克_26.10.17/code`。
本说明在 2026-10-16 对照 `dev_2cam` 更新；后续功能状态以项目 README 和代码为准。

## 当前状态

同日API学习：已读取SUertee/partrace-vision的dev提交eb5de7266c247964a50a00e58523f9c34581014a中的OpenAPI/API_DESIGN/视觉交接/SQL与样例，笔记为docs/GitHub_dev_API学习与视觉接入要点_20261016.md。契约27操作、11表77字段；旧78字段仅conversations去掉recording_id，六张视觉相关表不变。视觉发布完整multipart包，EventInput仅9字段；track_key限同录像，G档案/跨镜及shelf_level/取件分数没有独立接口字段。ROI为归一化多边形，bbox为归一化xywh；未知UTC为null。默认14个数据操作、显式Agent后22个为文档所述本地实现，P1包裹5个待实现；未执行业务HTTP联调。将来开发适配层须重查接口版本，不能把本次学习视为接入已完成。

同日最新：重要参数统一到 `configs/vision_runtime.json`，覆盖A选帧/OSNet、B匹配、手架交互起止、手区、首次伸收、层号投票、五帧上下文、配准、VLM模型/超时/token。各入口支持RuntimeConfig，保存实际参数快照；改参数不复用不一致观测。人物档案根目录直接为data/person_enrollment，G编号独立目录，最多5张有效代表图，多余旧图进本人history；根index.html用于查看。旧手工不足3张保留并注明。135项检查通过；最终实测AB_configured_20261016_02_A/B：A三人物5/5/4张，11.029秒；B两候选，一关联一未知，88.354秒，0API。见docs/统一参数与人物档案_20261016.md。下方AB_auto_profiles为历史实测。

2026-10-16新增 `run_AB_station.ps1 -Device cuda`：先完整运行A人物自动建档，再将A档案快照传给B货架交互流程。默认A/B为 `test_video_new/site_20261016/A/` 与 `B/` 各唯一视频。默认按最后修改时间选择最新 `configs/interaction/shelf_B_*.json`，打印实际路径；只有B拍货架，A/B时间各自保留，不推断录制间隔。无ReviewWithQwen不调用API。

A采用姿态/BoT-SORT 15fps、OSNet最多5fps、960输入、质量/遮挡/外观一致性筛选3～5帧，间隔至少200ms。档案库 `data/person_enrollment/` 保留旧编号，向最大编号后续接；证据充分复用，否则新建并记录可能重复。同视频同参数同轨迹重复运行不追加。旧手工建档不要与自动库同时写。B保留局部P轨迹，OSNet多帧一对一外观匹配；事件采用G/null，模型提示仍采用局部P校验后再映射。六字段不变。

完整实测 `outputs/AB_auto_profiles_20261016_02_A/` 与 `_B/`：A三档案19张原始关键帧，重复执行档案不变，10.521秒；B两段候选，其中一段G0004，一段因G0005支持覆盖45.5%不足而null，132.335秒，0API。层号仍待复核，无独立身份准确率。122项自动检查通过。见 `docs/A自动建档_B人物归属_实施与测试_20261016.md`。本次仍为本地离线A/B流程，实时流和后台复核队列未实现。

2026-10-15新增简化入口 `run_shelf_session_test.ps1`。人物/手腕＋人工区域生成整架连续候选会话，腕点不直接定层；首次伸手的肘腕估计手区提供来源层候选，模型独立复核。每会话前300ms、首次伸手起点/峰值/收手、后300ms五帧，只做一次Qwen请求；无可靠伸收转折才明确回退25%/50%/75%。新公开动作shelf_interaction，仍保持六字段，自评分/取件判断/冲突放详情。默认不运行包裹模型；ParcelAblation在完全相同会话和五帧上添加Florence/KLT作对照。旧run_interaction_video.ps1仍可运行，下面旧路线描述仅适用于该入口。

模块为 `station_vision/shelf_session/`，最新输出 `outputs/shelf_session_adaptive_20261015_1240/`。本例会话2.033～14.033秒，五帧1.733/2.033/2.667/3.400/14.333秒；算法、模型及最终层均3，取件true。单次API22.170秒，主流程133.992秒；同五帧包裹增强结果未变但增加46.766秒视觉处理。一个正例和两个同片构造负例3/3与助手参考一致，不是通用准确率；参考3层在本轮之前由首版复核修正，未经团队独立标注。见 `docs/自适应五帧与操作来源层_修正实测_20261015.md`。旧首版输出和文档保留为历史对照。

`reach.py`负责估计手区、首次伸手转折和来源层投票；`detector.py`负责整架候选/五帧；`review.py`验证模型人物/层/证据；`visualization.py`导出腕点与估计手区核查图。估计手区不是物理接触或包裹检测。算法仅定位首次操作来源；长会话后续跨层的自动分段尚未验证，明显多层应保留未知。switch_ms只是旧配置兼容，不再用于腕点跨层切分。

新入口示例：`run_shelf_session_test.ps1 -Device cuda -ReviewWithQwen -RunName <新名字>`。加ParcelAblation会另发对照请求；Reference可增加该视频的两个构造负例。只重新导出已有结果用 `.venv-track/Scripts/python.exe -m tools.reexport_shelf_session <运行名>`，加report-only仅刷新报告；不调用模型。新入口ObservationsRun校验相同配置/源哈希，启用复核仍会发送新请求。

当前为单个本地视频的离线实验：

人物检测／姿态／ByteTrack → 手与包裹／货架区域的时序关系 → 人物和目标隔离的交互候选 → 原始证据加密抽帧 → 可选 Qwen 多帧复核 → 事件聚合与联合结果视频。

人物使用 `models/yolo26n-pose.pt`。默认包裹由 `models/florence2/base-ft/` 的 Florence 自动定位，KLT 做短时跟踪，周期定位做校正。定位跳变不进入运动证据；失效、到期和匹配歧义保留无效状态。货架区域由人工配置并通过 ORB 配准。新增可见外观变化，只是候选的辅助信息，不是货物移除证明。默认未标定具体层号，层号由多帧复核结合可见支撑关系判断。

旧Florence分支从 `72d0913` 建立，原 `dev_parcel_pick_s` 的实验保存原处，未合入 YOLOE 比较入口。RetailAction 已停用。large-ft、CPU 分频优化、实时流／后台复核队列仍未验证；A/B外观关联已按本页新入口实现。`72d0913` 的人工种子路线可通过旧配置显式运行。

## 修改定位

| 需求 | 主要文件 |
|---|---|
| Florence 本地模型及矩形框 | `station_vision/florence/probe.py` |
| 定位调度、裁剪映射、手腕与货架状态 | `station_vision/florence/observer.py`、`shelf.py` |
| KLT 轨迹、校正及失效规则 | `station_vision/florence/tracking.py` |
| 源图/标注对照与轨迹图 | `station_vision/florence/visualization.py` |
| 输入、全帧观测、证据、复核调度、聚合、视频 | `station_vision/interaction/pipeline.py` |
| 候选触发与人物／目标隔离 | `station_vision/interaction/candidate.py` |
| 提示词、人物／时间／层号校验、六字段事件 | `station_vision/interaction/review.py` |
| 包裹短时跟踪及背景位移 | `station_vision/hand_object/tracking.py` |
| 手物运动初判 | `station_vision/hand_object/motion.py` |
| 模型客户端及响应解析 | `station_vision/multiframe_review/client.py` |
| 人物姿态和 ByteTrack | `station_vision/models.py` |
| 货架区域配准／媒体时间 | `station_vision/registration.py`、`video.py` |
| 默认自动定位配置 | `configs/interaction/florence_klt_0fc72122.json` |
| 人工初始框基线 | `configs/interaction/new_0fc72122.json` |
| 公开接口 | `contracts/interaction_event.schema.json` |

## 运行

在项目根目录执行，使用唯一环境 `.venv-track`：

```powershell
.\run_interaction_video.ps1 -Device cpu -RunName interaction_local
# GPU 测试时将 Device 改为 cuda。
.\run_interaction_video.ps1 -Device cuda -MaxSeconds 10 -RunName florence_short
```

需要真实模型复核时加 `-ReviewWithQwen`。`-MaxRequests` 或 `-ReviewCandidates` 可限制复核范围，限制后的结果不是完整复核。不要重用已有运行名。

本机 base-ft 已准备。新环境在 `.venv-track` 安装 `requirements-florence2.txt`，执行 `-m tools.prepare_florence2 --variant base-ft`。large-ft 需先准备权重，再加 `-FlorenceVariant large-ft`；不能声称已测试。Florence 只做定位，提示词中的 held 不等于真实持有。

默认 `qwen3.8-max` 和兼容地址来自当前 CLI 配置，后续以 `--help` 与代码为准。不要复制密钥到命令、技能或报告。

`-ObservationsRun <已有运行名>` 复用观测，不复用模型响应；配置和视频哈希需匹配。只需重新解析保存回答时使用下面的离线重导出工具。

复核凭证检查入口为 `setup_qwen_key.ps1 -CheckOnly`，缺失时以 `setup_qwen_key.ps1` 隐藏输入并保存当前Windows用户DPAPI密文。此检查不调用API，不要将密钥写进命令或项目文件。启用复核时在视觉计算前加载凭证。观测完成后立即保存 `observation_checkpoint.json`，后续失败仍可复用。旧版没有检查点的人工标定失败运行，可用 `tools.recover_interaction_observations <旧运行名> <新缓存名>` 在源哈希和逐帧时间校验通过后恢复；不修改原失败目录。说明见 `docs/Qwen凭证与失败运行恢复_20261015.md`。

## 验证命令

```powershell
.\.venv-track\Scripts\python.exe -X utf8 -c "import sys; print(sys.executable); print(sys.prefix)"
.\.venv-track\Scripts\python.exe -m pip check
.\.venv-track\Scripts\python.exe -X utf8 -m unittest discover -s tests -v
.\.venv-track\Scripts\python.exe -X utf8 -m tools.validate_interaction_run interaction_20261012_final_v2
.\.venv-track\Scripts\python.exe -X utf8 -m tools.validate_florence_klt_run florence_klt_20261014_review
.\.venv-track\Scripts\python.exe -X utf8 -m tools.reprocess_interaction_reviews interaction_20261012_final_v2 interaction_reexport
```

按任务选择运行，最后一个命令会生成新输出目录。现有测试覆盖候选持续时间、多人隔离、跟踪失效、共同运动、证据尾部覆盖、响应截断、人物和层号冲突等行为。校验工具检查源哈希、原始 JPEG、时间戳、响应结构、公开字段和导出帧数，不评估语义识别准确率。

## 结果位置

`outputs/<RunName>/` 中主要查看 `comparison.html`、`joint_review.mp4`、`report.md`、`events.json`。Florence 路线另有 `localization/`、`florence_klt_metrics.json`、`parcel_tracking_log.json` 和源图/标注图库。复核细节位于 `event_details.json`、`reviews.json`、`evidence/` 和 `calls/`。

人工种子基线样例为 `outputs/interaction_20261012_final_v2/`。Florence 全视频观测样例为 `outputs/florence_klt_20261014_final/`，两个候选的 Qwen 试运行在 `outputs/florence_klt_20261014_review/`，后者仅部分复核。实施与实测限制见 `docs/Florence定位_KLT跟踪_实施与验证_20261014.md`。原始视频在 `test_video/` 和 `test_video_new/`，不是可随意删除的处理输出。

## 依赖与恢复

公共依赖见 `requirements.txt`，重建方案为 `requirements-cpu.txt` 或 `requirements-cuda118.txt`；`requirements-lock.txt` 是已验证 CUDA 环境的依赖快照。

`.venv-track` 是从原环境迁移并修复启动脚本后的唯一环境。旧环境名在历史审计和停用文档中只表示过去状态。Windows 环境再迁移时，要重新生成激活与命令入口，不能仅替换文件夹名。

项目技能的版本控制源位于 `skills/station-vision-implementation/`，本机发现副本位于 `C:/Users/12908/.codex/skills/station-vision-implementation/`。维护技能时同步这两个副本；项目根 `AGENTS.md` 提供环境和技能入口。

## 人工货架标定

2026-10-16新增项目根目录`shelf_calibration.html`，独立浏览器页面选择图片或视频当前帧，四角框选、下载便携JSON、重新导入编辑。新JSON的shelf_calibration.source为manual_image，内嵌PNG；通过`run_shelf_session_test.ps1 -Config <图片JSON> -Video <实际视频路径>`接入，`image_reference.py`校验PNG/尺寸/哈希，等宽高比尺寸映射后以图片为ORB参考，逐帧核对区域。无有效配准不能冒充没有交互；旧首帧模式兼容。旧取放和旧预览入口不支持新图片格式，默认拒绝。见`docs/B机位_图片与视频选帧标定_20261016.md`。已通过107项Python测试、JS语法及模拟DOM的JSON流测试；内置浏览器禁止file自动化，未实测浏览器文件对话框/视频解码，新B场景还没有实际输入。

货架区域和层号当前采用人工框选。入口为 `run_manual_shelf_calibration.ps1`，实现位于 `station_vision/shelf_calibration/manual.py` 和 `manual.html`。页面支持拖框、四角调整、未知层号、图片导出和保存新配置；不调用模型 API。

保存配置包含 `shelf_zones` 与人工标定来源、首帧坐标和视频哈希。通过 `run_interaction_video.ps1 -Config <保存配置>` 接入。换视频或尺寸不匹配时应重新标定。标定本身不代表已实现拿取起点和来源层关联。使用步骤及边界见 `docs/人工框选货架层_使用说明_20261014.md`。

## 视觉结果入库方案（2026-10-16）

方案见 `docs/当前视觉流程入库方案_20261016.md`，依据后端 dev 的固定提交 eb5de726。独立结果导出适配层和正式上传器尚未实现；不将本地六字段原样上传。现有 API 可承载 B 的录像、交互和原始证据，track_key 仅局部连续轨迹，G 档案与跨视频关联、模型取件评分需扩展契约。正式入库先核对发布配置和分层 zone 映射，再冻结 ID/清单/媒体并按同包幂等重试。

已生成离线示例 `outputs/backend_ingest_plan_20261016_01/`：2 事件、10 原始帧、1 源 MP4，约 13.577 MiB。camera/config/zone 尚为 PENDING，upload_ready=false；未调用 Backend API。示例不是正式上传命令，不能直接上传。

## recordings 实际上传测试（2026-10-16 22:20）

35.84.190.14 的 Backend 与 PostgreSQL/pgvector就绪，API仅绑定服务器127.0.0.1:8000，公网直连未通。通过现有SSH配置anker_AWS和内存中的既有演示凭据，上传5秒明确标记的空场景MP4；首次201、同包重放200、详情与列表回读200，media_status=available。源视频93,821字节，首次1.555秒，总计10.478秒，0事件0证据；不能作为真实B入库或大视频测速。测试录像已留服务器。真实B尚缺正式镜头/层级zone配置及对应权限，当前服务器仅demo_v1/cam_demo_01/E3。未修改服务器配置或鉴权。报告见docs/recordings上传实测_20261016.md与outputs/backend_upload_smoke_20261016_222024/report.json。一次性.runtime探测脚本再次运行会新增演示录像，不是正式上传器。

## A 录像上传及时间校验（2026-10-16 22:32）

用户明确选择真实A视频2_input.mp4、cam_demo_01/demo_v1测试配置和prerecorded，随后指定started_at使用电脑当前时间。实测14912ms、837387字节，按2026-10-16T14:32:53.433Z提交实际MP4，POST返回422 clock_unverified，GET回查404；本次未发布录像。服务器现有时间规则不接受无独立校准的客户端时间。用户时间选择待解决，不悄悄改null或写伪校准记录。实际包与响应位于outputs/A_recording_upload_20261016_223246，说明见docs/A录像上传_电脑时间被拒绝_20261016.md。

## 用户更新后重试（2026-10-16 23:29）

用户自行调整时间规则后，线上OpenAPI已注明started_at按导入方声明保存。此次未部署本地.runtime内的未完成时间补丁。真实A视频2_input.mp4、cam_demo_01/demo_v1、prerecorded和电脑当前时间重新POST，返回500；回读也500。日志定位UndefinedTable users，API现连Docker postgres:5432/partrace、public，未发现users/recordings/events/evidence表（IAM关闭），不再是之前的clock_unverified错误。仅诊断，未擅自建库/建表/改权限/切换数据源；时间处理因鉴权阶段失败尚未实际验收。结果outputs/A_recording_retry_20261016_232938/report.json与diagnostics.json。需确定并恢复正确业务库或初始化明确的新库后再重传。

## A 录像正式重试成功（2026-10-16 23:35）

用户自行修复数据库连接后，health/user/config/recordings均200，旧演示录像可回读。按已确认cam_demo_01/demo_v1测试配置、prerecorded和电脑当前时间实际上传A/2_input.mp4成功：recording_id=20261016T233503_ca4797bb66d5，started_at=2026-10-16T15:35:03.233Z（北京时间23:35:03.233），14912ms；首次POST201/2.245秒，GET回读200，media_status=available。输出outputs/A_recording_upload_20261016_233455/report.json和result.md。0事件0证据仅录像导入，不作无事件结论。未部署本地.runtime内的未完成时间补丁，也未修改服务器配置。此前clock_unverified和缺users表的上传阻碍已由用户修复，当前线上规则允许导入方声明started_at。

## B 视频上传前置检查（2026-10-16 23:50）

用户要求上传B/1_package.mp4，指定cam_B/demo_v1、mock和当前电脑时间，及手填部分服务端字段。源视频4674199字节、SHA d0f9d2c3f1dc0da32850332d0f120148726c58822bf6550e0cab12eb063d282f；ffprobe容器时长25.633333秒，ceil导出25634ms。服务器健康正常，但实际demo_v1只有cam_demo_01，当前凭据仅授权此镜头；cam_B不存在。当前模型仍拒绝mock携带实际MP4和时间；manifest_sha256/media_status/created_at由服务端生成。已询问补齐B配置授权或临时演示镜头，以及是否沿用A成功的prerecorded方式，选择待回复。0条recordings POST，未修改服务器。前置结果outputs/B_recording_preflight_20261016_235056/preflight.json。

## B 录像上传成功（2026-10-17 00:22）

用户自行新增了demo_v2，包含cam_demo_01/cam_A/cam_B，当前导入与读取凭据已授权B；demo_v1仍只有原演示镜头。实际B/1_package.mp4已使用cam_B/demo_v2、prerecorded、电脑当前时间上传：recording_id=20261017T002245_8e82b0253893，started_at=2026-10-16T16:22:45.738Z（北京时间10月17日00:22:45.738），duration_ms=25634。首次POST201/4.805秒，GET回读200，media_status=available，已核对镜头、版本、时间和时长。输出outputs/B_recording_upload_20261017_002239/report.json与result.md。0事件0证据，仅原始录像测试导入。没有修改服务器，没有使用mock伪称已存媒体；摘要、存储键、媒体状态和创建时间由后端生成。

## 视觉API对齐与只读核查（2026-10-17）

用户要求先对齐、不实施；已明确视觉通过业务Backend API读录像元数据并提交结果，通知包含recording_id/camera_id，B希望使用累计人物库。实际A/B MP4均存在服务器/srv/partrace-vision/.local-state/media/objects，容器映射/state/media，源哈希与Windows原始视频一致。容器UID10001实测可读，ubuntu UID1000实测PermissionError；未来视觉进程需媒体挂载和读权限，不能仅凭路径存在声称能访问。当前累计库在本地data/person_enrollment/archive.json；服务器/srv/partrace-vision/vision/data/person_enrollment/archive.json亦有一份拷贝，两者SHA不同且当前A/B代码不跨机器同步。服务器18条模板图片引用仍指旧Windows code - anker路径，直接引用无效，但Linux档案目录中18份对应图片实际都存在，迁移时需统一路径。当前A是整段扫描、选图、原证据校验后才逐人物register并原子更新archive.json，之后导出A gallery_snapshot；当前run_AB_station传A快照给B，BIdentity启动一次加载、无热更新。B输出person_archive_snapshot仅留痕；单独B无Gallery/config路径时不会自动读取累计库。后续API按需求改为每个B任务默认加载最新完整累计库，不要求通知绑定某次A。此前“实时创建/持续更新/直接扫目录”的表述不能作为已实现功能。审计结果outputs/vision_service_alignment_audit_20261017_013816/audit.json。

## 视觉通知服务首版（2026-10-17，用户已授权实施）

新增station_vision/service与configs/vision_service.json：两条POST /vision/v1/camera-a/jobs、camera-b/jobs接收recording_id/camera_id，Bearer鉴权、持久SQLite去重、单worker进程锁、状态/结果/显式retry。A成功完成后才允许依赖它的B开始；失败/中断阻塞B，重试A成功解锁。B每个任务开始从data/person_enrollment/archive.json读最新库并冻结输入快照，运行中不热加载。run_AB_station也改为A完成后把累计库传B。新增Ubuntu字体选择与哈希校验的旧Windows档案照片定位；Ubuntu必须用Linux .venv-track/bin/python，不能复制Windows环境。服务尚未部署，不是持续视频流。

读取最新GitHub dev 116cef71d6a09f526a9ad3785ef96b6b81e17510及线上OpenAPI：GET recording明确隐藏storage_key，源录像下载接口缺失；events/evidence写入只在完整POST recordings中，已有recording不可追加结果。dev有shelf_level独立字段，当前线上旧OpenAPI没有；source-first队列/worker仅文档记录本地验证、未合入，不能假设已可调用。服务遇到缺视频键明确recording_source_not_exposed；不直连业务SQL或重新上传替代recording。输出本地result_package，writeback_status=pending_backend_contract，completed只表示本地完成。后端字段/取片/回写待用户下一轮对齐，见docs/视觉API_后端对接待确认_20261017.md。

全量158项Python检查通过，新增服务23项。真实A/B算法通过新通知路由隔离验证：outputs/vision_service_smoke_20261017_020332_662548/report.json，A任务24.937s、B87.470s、总115.328s；A后B顺序与重复通知去重正确，B6人库、2交互/10原始帧/1G0004匹配1未知。0模型API、未测语义准确率、原累计库SHA不变。Backend元数据为带storage_key的测试替身，不能称生产端到端。命令 .venv-track/Scripts/python.exe -m tools.smoke_vision_service --device cuda。实施、启动、配置与输出详见docs/视觉API首版_A先建档_B读取累计库_20261017.md。


## A横向进出与主办方Qwen（2026-10-17，覆盖首版默认值）

用户明确不需要门口分界线。A通知必填recording_id/camera_id/left2right_flag，B必填recording_id/camera_id。true左到右enter_zone、右到左leave_zone；false相反。reid/direction.py仅按同轨迹横向移动分段，平滑/最小位移/持续时间/缺口重置均在vision_runtime.json的a_direction；纯上下和抖动不猜方向。A输出direction_events/diagnostics，预览视频显示横向箭头。A仍管理累计G库，不新增业务档案字段。

service JSON的review_with_qwen现默认true，仅B调用。主配置vlm改为https://ai-router-cn-pub.anker-in.com、hackathon/v_model/qwen-max，实际回包qwen3.8-max。图片base64内联；B人物/货架/五帧算法候选作为复核输入。网关仅enable_thinking=false仍有截断，增reasoning_effort=none后两段成功11.009s/13.427s。官方凭据在当前用户LOCALAPPDATA/StationVision/anker-key.dpapi，旧qwen-key.dpapi保留；Ubuntu用STATION_VLM_API_KEY。绝不复制明文密钥到项目或日志。

新增service/artifacts.py：本地event_records/evidence_records，日期随机ID保存在job/id_map.json，recording_id自动关联；A选清晰裁剪图，B选复核后display_image_path，bbox_json=null，原图留存。storage_key在本地包是视觉文件路径引用，media_storage_scope明确vision_local，writeback_status仍pending_backend_contract，不能称后端已发布。B显示图在复核后生成，不回送给模型，避免答案泄漏。Qwen状态/取件判断/评分仍在event_details.json及calls，未加数据库列。

Backend普通GET recording仍隐藏storage_key。视觉已适配按ID请求GET /api/v1/vision/recordings/{recording_id}/source?camera_id=...，最小只读Backend补丁docs/backend_read_recording_source.patch已通过apply --check、未部署，需admin+data:read及相机授权。不让通知方额外提供video_path，不直连业务SQL。既有录像追加分析结果的写入接口亦未部署，不能用重复POST原片替代。

178项全量测试通过。实际隔离顺序测试outputs/vision_service_smoke_20261017_035802_698227：A18.933s/B100.389s，A true得3条enter候选/14张图，B无API测试2交互；正式库SHA不变。真实复核成功目录outputs/official_qwen_B_20261017_035947_424217：2段交互均模型疑似取件/第3层/评分0.9和0.85，1人未知1G0004，未做人工准确率；event_details.json为用户要的JSON，comparison.html/interaction_review.mp4和*_display.jpg可视化。service_export/attempt_1/result_package.json含2事件/10展示图，离线整理不重发API。复核总49.932s为复用观察加API/导出，不是全流程。初期截断响应保留partial，不冒充成功。

最新字段、原图/标注图区别、模型调用与边界见docs/视觉API字段确认与A方向_Qwen官方接入_20261017.md；OpenAPI已更新A必填布尔字段。代码未部署，未自动提交Git。

## 本机局域网通知服务（2026-10-17）

用户授权用本机IP开放服务。新增start_vision_service_lan.ps1，用项目.venv-track入口隐藏后台启动、监听0.0.0.0:8010，当前有线IP10.0.5.155。原服务JSON不变，生成.runtime/vision_service_lan/config.json及process.json/日志/validation.json；保留同一持久队列和累计库。首次生成的随机通知令牌使用当前用户DPAPI保存在LOCALAPPDATA/StationVision/vision-service-token.dpapi，不打印令牌、不进入项目；若启动环境已提供服务令牌则优先使用。不开机自启，不重复启动占用端口的服务。

本机通过10.0.5.155验证健康200、鉴权查询200、无令牌401、A缺flag422、B错误镜头422，真实任务0、Qwen调用0、无业务回写。当前Windows防火墙Public已有匹配实际Anaconda基础进程镜像的Python TCP允许规则，执行入口和sys.prefix仍为.venv-track，无环境切换。没有另一台设备的连通验证，没有公网映射。运行配置Backend仍127.0.0.1:8000且无PARTRACE_API_TOKEN/Ubuntu媒体挂载，真实取片与回写未接通。调用、令牌读取与启停见docs/视觉服务_本机局域网调用_20261017.md。

## 指定服务器录像的真实通知检查（2026-10-17 05:36）

按用户给定recording_id=20261017T052824_camA_site20261016/camA与camB两条记录（实际camera_id为cam_A/cam_B），真实POST本机10.0.5.155:8010。A带left2right_flag=true，两条均202；A job vj_b29a3102a8c243c7a3c80cb14aac2a84立即failed/backend_credentials_missing，B job vj_5e2de24e515a47abb8d6386e76e24839依赖A，blocked/a_dependency_failed、attempt=0。算法未启动，Qwen0，回写尝试0，不能把202当作处理完成。队列留存，接通后显式retry A可解锁B。

服务器Backend容器只读诊断确认这两条recordings/demo_v2和源MP4均存在，A14912ms/837387字节、B25633ms/4674199字节，哈希与既有本地原片一致；调用后events/evidence各0。初次直接数据库连接因未用现有IAM连接工厂而OperationalError，保留失败前置证据；改用Backend原有connection_class(Settings())成功只读核对，非视觉直接SQL接入。线上OpenAPI仍无取原片source或既有录像追加结果路由。本地服务无Backend token、仍指Windows本机8000（无监听），未擅自改生产或增加鉴权凭据。报告outputs/vision_server_recording_test_20261017_053640/report.md、report.json、service_calls.json、database_after.json；无有效前置计数，不伪称前后增量核对。

## 切换10.0.4.72后端、远程取片与E3（2026-10-17 06:14实测）

用户提供ALGORITHM_LAN_API_HANDOFF(1).md，要求从新局域网服务器取视频处理，随后确认A/B zone默认E3。现有wechat目录teammate-access(3).zip有HTTPSCA和A专用/双路token；仅指定凭据提取到LOCALAPPDATA/StationVision/lan-backend-20261017，未打印密钥，未执行外部worker/应用补丁。8080初10061，用户启用后health/ready200；HTTPS8443验证CA/user/config/job均200。HTTP与HTTPS令牌分离，HTTP专用token尚缺，业务实测继续HTTPS。在线EventInput定义默认E3，但配置zones仍空，不断言此次422一定是区域问题。

新增service/lan_jobs.py：读取job、核对双ID、claim、media流式下载、SHA/长度/上限/半截清理、10秒后台heartbeat、失租只中断当前任务、构建稳定manifest、上传真实图片字节而非本机路径、不重传MP4、complete后回读job及每张evidence摘要。runner接入，artifacts默认E3并传G person_id，六字段B契约保持。configs/vision_service.lan.json独立队列.runtime/vision_service_lan_backend，共享原累计人物库；start_vision_service_lan.ps1现默认此配置，当前端口8010仍后台运行。remote source不打开storage_key，按jobID下载，API并不暴露存储键。

真实通知A=lan_981094dbf2cd791399f92f075835898194ff2602fd072f7a/lan_video_1，B=lan_04e5f9a36180fdd4aa4ca851e37c362ce845728c29e18d78/lan_video_2，共同lan_video_20261017_v2、prerecorded，started_at保持null。A下载837378字节/.163s、B4686750字节/1.744s，均匹配交接包SHA。A本地任务vj_54a0e26e8b2e41948deadb4f6ac46f04：21.031s，3enter候选14图，3旧G复用，累计库仍6人。B任务vj_8669d48535fa43da9c383a3c54d581c2：111.24s，2交互10图、Qwen8.979/9.168s，两段模型第3层/疑似取件、自评分.9/.85（未校准/未做人验准确率），1未知1G0004。源768帧配准有效，最新B标定，Florence/KLT关闭。总约135.3s。

两路complete都422validation_error，未发布；已按真实失败发送vision_result_not_published，后端两job failed，recording仍pending，计数null，不伪称直接SQL证实0。初次客户端只存code没保留服务端详细message；现补message/request_id留痕，后端拒绝具体原因待日志或重排队复测。已询问HTTP专用token和后端拒绝日志/重排队。请求回复不是再次授权请求。保持实际计算结果，后续用tools/republish_lan_result从现有result_package重传，验证原片SHA，沿用原事件/证据ID，不重跑算法/Qwen；失败任务须先由后端重排队，不能凭空再claim已failed任务或重传原片造新ID。

188项全量通过，新增10LAN相关测试；错误留存补充后10项再次通过。真实发布成功未验收，模拟成功及图片回读已覆盖。测试报告outputs/lan_api_test_20261017_061421_880427及backend_readback.json；A/B各vision_*输出包含视频HTML、原图及展示图；详情见docs/局域网后端取片_A_B处理与E3回写_20261017.md。当前代码变更未提交。新工具run_lan_recording_test.ps1真实通知并去重回读；勿反复重算付费。

用户后来说明8080不用令牌并要求再试。实际无Authorization的config及A/B job GET仍401 unauthorized（请提供有效Bearer凭据），只有health/ready200；证据.runtime/lan_backend_20261017/http_no_token_recheck.json。HTTPS旧授权仍200，但两任务仍failed/vision_result_not_published、未重排队。实际运行两次tools.republish_lan_result均backend_job_not_queued，在claim前停止；算法0/Qwen0/新ID0。HTTP无令牌业务目前仍不可用，未误用HTTPS令牌到HTTP；不再重复索要用户说无需提供的HTTP token，需后端实际免鉴权行为同步与failed任务重排队。保留HTTPS配置与本地结果。

## HTTP免Bearer和明确E3校验失败（2026-10-17 06:53，覆盖上段）

用户实际关闭Bearer后HTTP业务GET200，配置已改base_url=http://10.0.4.72:8080、auth_mode=none、token_files为空、allow_lan_http=true；本机服务已重启加载。LanJobSource显式none不读取任何已有令牌，lease仍保留；当前在线claim支持camera_id筛选，配置claim_camera_filter=true，避免A误领B。同镜头仍核对recording ID。相关38项通过，新增无Bearer/令牌忽略/租约保留检查，LAN共11项。

用户后端重排队后，实际复用A/B保存结果上传，算法0/Qwen0、manifest完全一致、原IDs不变。两路complete仍422；已新增完整脱敏响应保存，这次根因明确：message“分析事件引用了此配置不存在的区域。”提交events[*].zone_id=E3，而lan_video_20261017_v2的lan_video_1/2 zones仍空。A request_id bf2c5a9ad5f8452f9f701ad4142c8c11；B 92bbfa9bcb944029990cbd647a8c4e10。A/B后端任务再次failed，未成功发布，需后端让实际生效配置包含E3再重排队。不把已知E3改null绕过用户约定。完整响应、稳定manifest及状态汇总在outputs/lan_writeback_retry_20261017_*，每个attempt_1/publication_retry_20261017_0653*/writeback.json亦有完整complete_response（http_status+body）和request_id。11LAN测试在响应留存补充后通过。此前第一次完整422响应确实未保存，后续409的request_id不可当第一次422使用。

## A/B真实发布成功（2026-10-17 07:07，覆盖上述失败结论）

用户修复后端区域校验并重排队后，再次使用HTTP8080免Bearer复用真实结果，A/B complete均201/replayed=false，任务attempts3/completed，recording publication_status=published。A3enter_zone候选+14人物PNG，B2shelf_interaction候选+10复核后标注JPEG，总5events/24evidence；24张图全部通过evidence/media回读SHA一致。GETrecordings列表和带recording_id/camera_id/zone_id=E3的GETevents均200且数量对应，G和层号字段已回读确认。source_modeprerecorded、started_atnull保持，原视频复用。API无Bearer、lease正常；算法0/Qwen0、ID/manifest未改。

A成功目录attempt_1/publication_retry_20261017_070703_177805（上传/回读3.691s），B publication_retry_20261017_070708_111370（3.198s）；根路径同前vj_54a...和vj_866...，原result_package已更新published。新增JobStore.sync_published_result用于同步本地已completed任务的旧writebackfailed摘要，仅匹配原录像/镜头/结果文件时更新；两条SQLite已同步，新增对应验证，39项LAN+服务测试通过。服务主流程无需再运行算法。media_storage_scope现local_paths_with_verified_backend_copy，localstorage_key仍是本地引用，服务器图片已验证发布。后端zones查询仍空，本轮成功代表后端已调整接受E3，不假称本机改变服务器config。

成功报告outputs/lan_publication_success_20261017_070817/report.json和report.md，含两次完整201回包、recordings/event回读与图片校验列表。A完成北京时间07:07:05.752，B07:07:12.270。当前本机服务8010仍运行，后台路由已切HTTP8080；此后文档历史失败不能当成现状，代码未提交Git。

## 8010全部免认证（2026-10-17 09:53，覆盖所有旧服务Bearer说明）

用户明确要求取消一切服务认证。app.py移除全部HTTPBearer/Depends校验与令牌启动要求，version1.2.0；通知/列表/状态/结果/retry均公开，health.authentication=none，OpenAPI无securitySchemes和操作security。configs两个service JSON移除service_token_env，启动脚本不生成/读取DPAPI通知密钥，测试/隔离smoke也无通知Header。后端8080当前auth_mode=none，旧共享BackendClient也尊重none；模型Key私密读取不影响通知API。190全量测试通过。

后端station_sdk_v1明确镜头cam_doorbell_a“门铃A人物建档”、cam_sphere_b“球形B待货架标定”，已追加角色映射；B仍用实际现场JSON，未伪造标定。服务已重启8010，public GETjobs200、A重复通知202、新B实测无Authorization202，用户给定recording_id=5d2272313ca55d05af17e9fc1577da53d09fda94643b55b7f06ee7182ee5953f/cam_sphere_b，job_id=vj_1e9d8cec0bed47d28aa34d76fe3b9f08。

新B随后failed/backend_claim_mismatch，后端claim返回其他录像，本机拒绝错片，算法/Qwen0。目标远端仍queued/attempts0。当前在线claim仅worker_id，camera筛选未保证精确recording，取消Bearer后失去此前scope约束；已请求后端按recording_id及camera_id精确领取，未循环claim去消费别人的队列。一次错领任务的租约未写日志、未分析或发布，该租约按后端超时机制处理；不要假称目标已被后端拒绝解码。后续接通精确领取后显式retry本地失败B。新错误message已改为提示精确录像筛选，不再建议恢复令牌。

最新后端A cf791be8...及f7fa492a...任务completed但event/evidence均0，本机取消认证前无新A通知，仅旧LAN2任务；不能说新人物事件由本机创建。用户提及早期两段解码错误未给具体IDs，不乱归因。证据outputs/public_service_validation_20261017_095342/validation.json；完整调用文档docs/视觉服务_免认证调用_20261017.md。代码未提交Git。


## 按录像ID取片与持续受理（2026-10-17 10:25，覆盖旧全局领取）

视觉服务1.3.0已重启运行于0.0.0.0:8010，仍免认证；A/B通知实时持久受理并202，后台单线程维持累计库顺序。LanJobSource先GET recordings/{id}核对双ID，再GET recordings/{id}/media直接下载，无任务租约；保存source_receipt并校验摘要复用缓存。读取在线OpenAPI验证claim具备recording_id+camera_id，再仅领取该录像并核对返回三ID；不支持精确领取时绝不调用全局claim。后端条件未就绪以BackendPending进入waiting_backend，next_retry_at持久化，wait_retry_s默认30/范围5..300，只有算法开始前自动重查；不自动重跑已有Qwen或不确定提交。有效租约后算法/heartbeat/complete/图片回读沿用。QueueWorker收到通知唤醒，HTTP不等GPU/Qwen；health增加queue计数。

200项全量测试通过，含算法阻塞时20个不同通知并发受理、等待恢复、持久去重、A/B顺序和精确领取/缓存/错误录像保护。真实B 5d2272313ca55d05af17e9fc1577da53d09fda94643b55b7f06ee7182ee5953f 当前后端camera_id已改cam_B/config=station_sdk_v2；完整下载1583046字节，SHA308a94b979366afd2702168d10fe08e94f855e699bf79417a0611e59db82f100。本机任务vj_60eb1cdf7b2542428d1830754ccc8ef9由failed显式重试后waiting_backend，后端目标仍queued/attempts0。20次同B重复通知8路并发全202同jobID，平均57.948ms/P95 163.945ms/max221.938ms；为本机经局域网地址测试，不是外部实测或算法实时率。代码运行用.venv-track，启动PID39976。原旧cam_sphere_b任务保留失败历史，不冒充可用当前camera_id。

用户说后端接口将确认；截至实测claim schema仍仅worker_id，未合并精确领取。本次算法0/Qwen0、无新events/evidence；不能声称端到端回写或新场景识别已完成。后台每30s重查，接口在同路径完成实现与OpenAPI后可继续；不同路由要按实际契约再适配。新球形B货架标定仍需匹配现场。说明docs/视觉服务_按录像ID取片与持续受理_20261017.md，实测outputs/vision_service_realign_20261017_102024/live_validation.json，日志.runtime/vision_service_realign_tests_20261017.log；代码未提交Git。


## 先本地解析、后领取发布（2026-10-17 11:23，覆盖1.3的算法等待）

用户追问待处理任务为何不处理，已解除算法前置claim：服务1.4.0使用LanJobSource.prepare只按ID取片，先VisionRunner.analyse完成本地算法、保存；acquire仅用于发布租约。BackendPending转WritebackPending携带结果，队列waiting_writeback自动重试只复用保存结果，source SHA/config/双ID校验后发布，原事件证据ID不变，不重复Qwen；A本地建档完成即可解除B依赖。retry、异常、重启保留已完成结果，GET结果在waiting_writeback也200。204全量测试通过，服务仍8010免认证，最新启动PID36720。

真实目标B cam_B/5d2272313ca55d05af17e9fc1577da53d09fda94643b55b7f06ee7182ee5953f 已本地完成，源171帧/11.52秒，analyse 49.412秒（主流程43.522s），输出outputs/vision_B_vj_60eb1cdf7b2542428d1830754ccc8ef9_r109；包outputs/vision_service/vj_60eb1cdf7b2542428d1830754ccc8ef9/attempt_109/result_package.json。使用最新configs/interaction/shelf_B_2026-10-17-08-26-23.json（三层）。结果候选0/Qwen0，并非无交互：参考图08:06和源09:26画面位置偏移，配准仅27/171有效，2.5..6.8s操作期间63帧全部失败，人工抽查3s伸手、6s持有、9s回架有可见交互。因此增加空B发布质量检查，b_empty_result_min_registration_ratio默认.8，当前.158禁止发布，package quality_check.publishable=false。本机status completed/analysis completed，writeback blocked_quality，不再等待算法；后端目标仍queued0，不冒充已入events/evidence。后端接口精确claim仍待对齐，但现在主要检测质量问题是标定/配准，需明确重新分析，不能靠发布重试改检测。

报告outputs/vision_local_first_20261017_111904/report.md、report.json及source_3s/6s/9s.jpg；比较HTML与标注MP4在r109。没有候选因此没有自动五帧证据，抽查图仅用于调试，不能叫复核关键帧。未自动替换人工标定或关闭配准。当前用户仅问待处理原因，这轮完成服务修正及真实本地测试，不宣称已修复漏检或回写成功。


## B 本地新视频独立测试（2026-10-17 11:42）

用户要求单独跑桌面feb1289ed1c072473f021397a272a017_raw.mp4，已用.venv-track/cuda、当前latest shelf_B_2026-10-17-08-26-23.json、累计gallery和ReviewWithQwen运行。源20.543秒/305帧/2560x1440，51.585秒观测阶段后因整段0帧货架配准有效而明确failed，未到会话/Qwen（0次）。1轨迹P001/293帧含人，无G匹配；交互/层号/取件未知，不能叫无交互。失败原因185 warp_validation_failed、120 insufficient_static_matches。没有改标定/服务/算法，也没有写入后端。

输出outputs/B_standalone_feb1289_20261017_114231_run，保存原始summary失败摘要、observer_metrics和observations；另从已有观测导出B_debug_registration_failed.mp4（红条失效提示、蓝姿态/橙估计手区），14.692秒，305帧已验证，非take/put预测。result.json明确status failed、交互未知null。report.md/comparison.html可看；原片抽查图在outputs/B_standalone_feb1289_20261017_114231。此前建议重新标定，用户随后确认此JSON正是为本视频标定，以下修正覆盖该建议；未擅自变换旧区域或降低验证阈值。

## 新JSON加载核对与人工框显示修正（2026-10-17 11:58）

用户指出新标定未在视频显示。已核对shelf_B_2026-10-17-08-26-23.json与原运行config_snapshot.json：shelf_zones和shelf_calibration均逐项相等（3层及内嵌参考图），原文件SHA256=9c07f7b0c4c2f7d4268b24d1eb6fb8986bb7be2bce3e732f0d60db553bb2b014，确实加载了新JSON。根因是观察器仅保存配准通过的shelf_polygons，显示器也只画这些区域；0/305通过导致人工框全部隐藏。不能据此认定人工标定错误。

观察器新增reference_shelf_polygons保留原人工区域；仅展示导出显式show_reference_regions=True时以虚线画原坐标并标注未通过配准。算法仍只用有效shelf_polygons，模型输入默认不加入未验证区域，未降低阈值或改变手工JSON。整段配准失败时pipeline自动导出calibration_debug.mp4。206项测试通过，含失败区域保留、显示与候选隔离。当前视频使用相同源SHA和配置的缓存姿态重导出12.811秒，305帧完整解码，模型/API均0；outputs/B_manual_regions_visible_20261017_115802下保留视频、预览图、result.json配置核对和HTML。自动配准仍0/305，交互未判断，显示修复不等于交互算法已修复。没有提交Git或改后端。

## 新场景A/B处理与回写等待（2026-10-17 12:30）

用户更新site_20261016的A/B和shelf_B_2026-10-17-12-11-15.json，给定cam_A/62f7263cb20caa377d22d8c63a65eb1e3dfd2f0fddd7c9248a740f02a72a2904、cam_B/de6d6d8f6530179610f0257e301cf626aec9a21ce31b960ab626bf01c68f3d0c。已通过8010真实通知顺序处理。服务端源A1600x2200/136帧，SHA c0d0a5bb085329e40e9a5381bb1b9964d4c866e3a14b201e1ef365bb12a98b44，本地A928x1280/133帧为不同编码版本，因此使用服务器原片保留精确offset。B285帧/19.431秒，SHA 2e91f66237aa1b40089ece13de33ee0bb69f981b9a0473571f7eef9155aad378，和本地字节不同但7帧像素/PTS相同；使用服务器源。

A job vj_e8abf93a329c4657b68e5100a412b8fe首轮产生2方向候选、3新档案，抽查发现A-t10是拼接下半幅人体碎片误建G0009，A-t36是600ms重复短移动。用户已允许调参，minimum_selection_quality .40->.65，a_direction.stable_ms 200->800，参数前后快照已保存；配准/OSNet/B参数不变。G0009仅本轮误建，已在archive锁内移到quarantined_profiles（含原profile/occurrences），图片和registry保留，next_id>=10不复用ID，旧G1..8不删除。A显式r2通过同一VisionRunner重测，复用G0007坐着/G0008白衬衫，每人5图；方向剩1条G0008 leave_zone .533..5.133s（true规则），5证据。原r1保留，当前本地任务指向r2，15.07秒/主流程10.344秒。参数更严格可能漏小/遮挡人和<800ms快走，只验证本片。

B job vj_eceed59672164261b481885485547345/r1完整成功，285/285配准；新JSON SHA ba9f09a5788fc27f54a3b239321c1708e1df1f68856f885941c3286b3a8aedd9，冻结已核对。G0008匹配46次/覆盖1.0，1会话1.717..14.178s，首次峰值1.986/收手2.390，模型第2层取件/自评分.95，Qwen3.8-Max仅1次17.401秒；主流程82.37、服务87.389秒。原片后续还有上层操作，五帧只覆盖首次，不能说整个会话只操作2层或全部动作均识别。原PNG/显示JPG/视频均保留，Florence/KLT关闭。B使用r1时9人库快照，后续A参数修正仍沿用G7/G8，B不重跑模型。有效累计8人，G9隔离。

待提交共2events/10evidence，已生成manifest校验图片摘要/时间范围。12:30后端A仍completed/published 0/0，Bqueued0；claim OpenAPI仍仅worker_id、additionalProperties=false，complete必须X-Lease-Token。A writeback failed/backend_job_not_queued，B waiting_writeback/backend_exact_claim_unavailable；没有POST complete，不冒充本轮字段校验失败或入库成功。后端station_sdk_v2两路zones为空，E3须确认允许。GET events需完整offset区间（且整数）：A200/no_detections，B409/recording_not_ready；初次无offset和float9399.0读请求422已修正，不能混为上传422。

用户询问为何影响回写、需要什么与如何解决；已说明取片/分析不受影响，租约只是当前后端提交前置。推荐后端精确claim加recording_id/camera_id（不能换片）、让A可重新提交空结果、确认E3；或提供直接指定录像写结果接口。不能仅在视觉删claim绕过当前complete要求。用户尚未提供部署完成确认/新接口，不执行依赖变更后的发布；后续只重传已有包，勿重复GPU/Qwen。

报告与可视化outputs/AB_new_scene_20261017_121839/，完整交接docs/视觉回写阻塞_后端修复交接_20261017.md。参数变更已同步README和参数说明。未提交Git。A包outputs/vision_service/vj_e8abf93a329c4657b68e5100a412b8fe/attempt_2/result_package.json，B包outputs/vision_service/vj_eceed59672164261b481885485547345/attempt_1/result_package.json；上传后使用现有republish工具同步任务，沿用ID。

## 用户最终方向与免租约直接提交（2026-10-17 12:33，覆盖上段回写方案）

用户指定A left2right_flag=false，JSON须布尔值不加引号。已用原A2缓存轨迹/同源SHA重新计算映射、复制保留原输出、重导A r3视频与结果包；G0008 0.533..5.133s从leave_zone改enter_zone，人物/事件/证据ID不变，0次新人物推理/Qwen/档案变更。当前A任务options=false，指向attempt_3/result_package.json，10方向测试通过；同A布尔false通知真实202/created=false，未重复计算。当前A包为outputs/vision_service/vj_e8abf93a329c4657b68e5100a412b8fe/attempt_3/result_package.json。汇总HTML/报告已更新false进门及r3链接。

用户明确要求不看租约、直接传events/evidence。已按要求实际POST /api/v1/recording-jobs/{指定recording_id}/complete，multipart只events/evidence+5张图，A/B各1事件5图，无Authorization、无X-Lease-Token、未调用claim/heartbeat。两路均409 lease_conflict，“请提供当前任务租约。”A request_id 1fad9f66312342e3bdee1190d0f2129e，B 38524d4cad714a908f28676c00e3e600；真实响应outputs/AB_new_scene_20261017_121839/direct_submit_20261017_123314/report.json以及latest_direct_submission.json。不能再说本次尚未POST complete，也不能说结果已入库。

本机两任务已保存实际直接提交失败响应，status completed仅代表分析，writeback failed，submission_mode=direct_complete_no_claim_no_lease；旧waiting_writeback/claim自动重试已停止，防止违背用户新方案。未改变已运行服务的通用发布适配（目前仍旧1.4），本轮直接实传是.runtime/direct_submit_AB_20261017.py；如后续改变整个服务，须按明确免租约后端契约实现并测试，不冒充全服务已删除租约。用户不再选择精确claim方案，下一步应请后端移除该写入接口的租约校验/接受免租约events/evidence或提供新的直接回写URL，并允许A已有空结果补写，确认E3。视觉已实际绕过前置检查，无法单方面取消服务器409校验，不能伪造令牌或改为业务SQL。已有图片与ID保留，后端调整后只重传不重跑模型。

最新交接docs/视觉回写阻塞_后端修复交接_20261017.md已标明旧精确claim建议作废、当前免租约设计和完整请求。README及本轮report/HTML已同步。未提交Git。

## 免租约正式接通与逐条回读（2026-10-17 12:51，覆盖1.4和本轮双路未入库状态）

用户通知后端已改免租约complete，已实际重传：B HTTP201/replayed=false，1事件/5证据成功；A HTTP409/idempotency_conflict“任务已发布不同结果”，原空结果已发布阻止补写。B5图片SHA回读均一致。用户随后明确允许A补写并要求全部重传保证最新，已单独A重传（request_id 54d2e853b2d4492abb12cf11f7e249aa）及再全部重传，A仍409（最新2daf3a4a35a347bb883704aa9531020e），B200/replayed=true无重复。不要再询问用户是否允许更新A，其授权已明确；需要后端实际解除该条旧空结果锁定或给出重发方法。禁止造新recording_id或说A已入库。

服务已实现1.5.0正式免租约发布：configs/vision_service.lan.json backend.publication_mode=direct；LanJobSource.prepare_publication只核对源/任务双ID和元数据，不按status阻止发送，不调用claim/OpenAPI门槛/heartbeat，不发X-Lease-Token，publish仅events/evidence+真实图字节。VisionRunner仍先本地分析和保存，失败保留响应，saved结果不重跑模型。旧leased只显式兼容，测试夹具固定旧mode；新增4direct相关检查，210全量通过。tools.republish_lan_result遵循direct且已实测B同包HTTP200/replayed=true/5图SHA一致。确认无running后重启后台服务，当前启动PID21136，仍10.0.5.155:8010免认证；health analysis_policy=local_analysis_then_direct_publication，OpenAPI version1.5.0。此段覆盖旧“仅一次脚本、通用服务未切换”说明。

用户问events/evidence是否真正入表，已通过后端API逐条回读：A按recording/camera/E3全时段查询200/no_detections，目标事件404、5证据404，仍0/0。B查询200/matched1，目标事件200、5证据200；事件字段与最新本地包逐项一致，人物G0008、shelf_interaction、第2层、1717..14178ms，5证据event_id/offset关系一致，图片SHA均已复核。不是直接SQL检查，不冒称A/B全成功。完整回读outputs/AB_new_scene_20261017_121839/table_readback_20261017_125119/report.json和latest_table_readback.json；新全量直接提交direct_submit_20261017_125049/report.json。所有重传算法0/Qwen0。

README、当前汇总report/HTML和后端交接已同步B成功/A冲突；本轮latest JSON仍shelf_B_2026-10-17-12-11-15.json且与B冻结SHA一致。A当前false/r3/attempt3包，B原r1/attempt1包；B已入库，后续只处理A阻塞，不无故重复Qwen。尚未Git提交。

## complete幂等业务规则交接（2026-10-17，未改后端）

用户问是哪条表/API规则，要求详细说明供后端修改。已重新GET线上OpenAPI，complete的description明确写“同内容完成重放200，已完成后异内容或 failed 任务返回409；失败不发布半包”。因此是complete的已发布结果一致性/终态业务规则，不是events/evidence字段422错误。A空数组原结果也已completed/published，旧manifest_sha256 c62745addff720b8593e08e9b8c8541df7c5f6a00613deae79b3842eff902c15；新1事件5图与其不同触发409。未取得后端源码，不虚构具体文件/行号或直接数据库约束；建议按idempotency_conflict/中文message/complete_recording_job搜索代码。

已生成docs/后端complete_结果覆盖与幂等规则_修改建议_20261017.md：建议新增manifest顶层JSON布尔replace_existing（这是方案，尚未实现），默认保持同内容200重放/异内容409；显式覆盖时事务锁定该recording，验证全包、替换当前录像完整分析快照、同步摘要/发布状态/计数，保持原片与源时间，回滚不损坏旧有效结果；同包再传仍replayed=true。A空结果可一次性修复，但不能仅改job.status造成发布元数据不一致。B已成功，无须清空。需要后端告知覆盖参数具体位置并同步OpenAPI后再按新契约重传，不能现在随意加一个被忽略的字段宣称可用。

接口声明快照outputs/AB_new_scene_20261017_121839/complete_contract_current.json，A最新409 request_id仍2daf3a4a35a347bb883704aa9531020e，未作新的发布变更。当前服务1.5 direct、B1/5成功、A0/0不变，用户已授权更新A不重复问许可。

## A/B本轮回写最终完成（2026-10-17 12:59，覆盖A冲突未入库说明）

用户通知后端已修改、现在可以推，已通过更新后的tools.republish_lan_result/direct免租约单独重传A最新attempt3包，实际HTTP201/replayed=false，event_count1/evidence_count5，A旧空结果已解除。A manifest_sha256=433b9303735b1cf08772d154557abc6df77d30b65acbd1b97db1b3d2ef9c9d39，源recording ID和原片未变，G0008/enter_zone/533..5133ms、false方向。A成功响应outputs/vision_service/vj_e8abf93a329c4657b68e5100a412b8fe/attempt_3/publication_retry_20261017_125841_057651/writeback.json；本机任务已同步published。

B原成功结果未再重传，仅回读：G0008/shelf_interaction/shelf_level2/1717..14178ms，1事件5图。两路合计2events/10evidence，最终通过业务API查询全时段、逐条证据元数据、下载10图片比较SHA，所有事件字段、证据event_id/offset关系及图片摘要与最新本地包完全一致。报告outputs/AB_new_scene_20261017_121839/publication_verified_20261017_125906/report.json、latest_publication_verification.json，all_verified=true；当前report.json writeback_complete=true，HTML/README/两交接文档已同步全部成功。此次算法0/Qwen0，无重复事件、无改人物库；服务仍1.5.0/direct/10.0.5.155:8010免认证。

此前建议的replace_existing参数没有在请求中使用；仅证实这条A现已可成功补写，不宣称后端实现了通用显式覆盖参数或允许所有已发布结果无条件覆盖。历史409及旧readback仅保留排错，不能作为当前状态。未提交Git。本轮原始需求（两路最新结果入events/evidence且验证）完成。
