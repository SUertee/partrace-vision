# 算法开发入口

主责：徐泽华。对应 D03、D04。

业务功能与表／API 交接见 [M03 视觉产物与视频入库](../docs/modules/README.md#m03)、[M05 描述向量与检索](../docs/modules/README.md#m05)。视觉输出经后端导入，不直接访问业务数据库。

具体职责、数据库表、API 调用、完整包／媒体格式与联调验收见 [Backend 与 Vision 联动设计](../docs/modules/BACKEND_VISION.md)。

当前已有真实 A 自动人物建档与 B 人物跟踪／货架交互算法、YOLO 姿态和 OSNet 权重，原离线运行、标定和六字段输出见 [README_hua.md](README_hua.md)。新增的 API worker 在独立进程中调用这些算法，接收已下载完整的 SDK People 录像，向 Backend 回传原有完整结果包；Vision 不连接业务数据库。描述不能补出未观测动作，未知区间保留未知。Embedding 的真实编码空间及效果仍按 M05 单独验收，worker 本次不自动生成或补造向量。

交付与验收见 [开发计划](../docs/DEVELOPMENT_PLAN.md)。

共享接口与数据格式见 [docs/api](../docs/api/README.md)。

向量存储采用 PostgreSQL + pgvector 的原生 `vector`，由后端校验与入库；交接仍用 JSON 数值数组，统一模型版本、维度和 float32 编码。

## People 录像 API worker

从仓库根目录执行 `python -m vision.worker`，默认调用内置 [backend_adapter.py](backend_adapter.py)，不是空的命令占位。对应 [d06-people-recording-pipeline](../openspec/changes/d06-people-recording-pipeline/proposal.md)。任务元数据和状态由 Backend 存入 PostgreSQL；原片和关键帧由 Backend 的受限媒体目录保存，Vision 仅使用授权 API。

先把 [worker.example.json](worker.example.json) 复制到私有配置目录，按现场实际填写 `camera_id`、`config_version`、相机角色和标定，不直接运行占位模板。配置内所有相对路径均以 `vision/` 为根：

用户已确认门铃为 A、球形相机为 B，示例使用 `cam_doorbell_a / cam_sphere_b` 和 `station_sdk_v1`。真实球机画面尚无已验证的对应标定，因此示例明确保留 `calibration: null`，不绑定仓库原 B 视频的参考图。这样允许先启动并处理 A；领取 B 任务时会返回 `calibration_invalid`。管理员补充真实 B 标定、整架 zone 和逐区域映射后才能处理 B。完全没有有效配准帧时拒绝发布，不把场景未匹配记作已解析零事件。

- `enrollment` 是 A 人物建档相机；显式指定独立 `enrollment_root`，该根保存发布锁、版本元数据和 `CURRENT.json` 指针。每个任务先复制已提交图库到保留 artifact 下的独立 `enrollment-staging/`，档案与编号注册表只写入 staging；只有 Backend 确认同一 job／attempt 已 completed 后，才原子切换 CURRENT。不修改已有 `data/person_enrollment`，也不自动导入没有 CURRENT 的旧可变图库。A 没有输出区域动作的依据，因此其最终业务 manifest 的事件／证据数组为空；这表示建档阶段完成，不能解读为没有 People 或现场无人。
- `shelf` 是 B 货架相机；必须指定自己的 `calibration`、文件中对应的 `calibration_camera_id`，并把每个 `manual_XX` 区域显式映射到 Backend 当前镜头的 `zone_id`。`session_zone_id` 是整架活动对应的 Backend 区域；层号无法确定时仍按该显式区域保存候选，不猜层号。自动到来的不同录像通常需使用同机位图片标定，逐帧配准；绑定视频哈希的旧手工标定不能用于其他片段。不会自动套用“最新标定”或把两台相机都当作 B。
- B 的 `gallery: null`、`gallery_root: null` 显式使用本段匿名 P 轨迹；如要关联 A 档案，将 `gallery_root` 设为 A 的 `enrollment_root`，保持 `gallery` 为 null。活链路拒绝直接指定可变 archive 文件，B 只从 CURRENT 读取同一个已完成的不可变版本，再制作只读快照。允许 gallery_root 在启动时尚未发布，以便先运行 A；B 实际开始前缺少已提交版本时报 `gallery_missing`，不读取未确认 staging 或回退旧图库。G 编号仍是未经身份验证的外观关联，Backend 的 `track_key` 始终使用本录像的局部 P 轨迹。
- `runtime_config` 指定现有可校验的参数文件；`device` 使用 `cpu` 或经实测可用的 `cuda`。`artifacts_root` 保存每次作业独立的真实算法产物，默认新建 `outputs/worker_jobs/`，不覆盖既有运行。下载用的临时文件在任务结束后清理，A 档案依赖的真实证据保留在作业产物目录。

A 使用同一文件系统上的独占发布锁，贯穿“复制已提交版本 → 推理 → Backend 确认 → 切换 CURRENT”，多个本机 worker 不会从旧基线各自覆盖更新。等锁期间心跳继续，失租立即退出；算法失败、失租、撤权导致 complete 被拒绝或完成状态无法确认时，staging 不向 B 发布。完成后心跳因终态收到409，不会撤销已经确认且 attempt 匹配的有效完成结果。旧任务重复推广是幂等操作，不会把 CURRENT 回滚到后续版本之前。

PostgreSQL 的完成事务与本地图库指针不是跨系统原子事务。进程可能在 Backend 已 completed、CURRENT 尚未切换时崩溃；所有 staging 和发布标记保留在 artifact 中。遇到本地发布失败时 worker 停止，避免继续推进图库；用相同私有 API 配置执行显式恢复：

```sh
vision/.venv-track/bin/python -m vision.worker \
  --reconcile-gallery '/absolute/private/artifacts/job-directory'
```

恢复会重新通过受权 GET 确认 job、camera、config 和 attempt 均匹配已 completed 的任务，取得同一本机发布锁后幂等推广。若此后已经发布了其他任务且当前基线发生变化，返回 `gallery_base_changed`，保留现场，由管理员核对后重新合并；不会用旧 staging 覆盖新更新。恢复前不要重新推进该共享图库；不要删除任何 CURRENT／历史版本所引用的已发布 artifact。文件锁依赖本机或提供等价 advisory-lock／atomic-replace 语义的共享文件系统；不同机器各有一份本地目录时不能协调发布，需要独立的服务端图库协调设计。本 worker 不直接连接业务数据库。

本机为 macOS，原 Windows `.venv-track/Scripts/python.exe` 不存在，已按相同项目环境位置使用 `.venv-track/bin/python`。macOS 的 PyTorch CPU wheel 不带 `+cpu` 后缀，使用专门依赖文件，不安装 CUDA：

```sh
uv venv --python 3.12 vision/.venv-track
uv pip install --python vision/.venv-track/bin/python -r vision/requirements-macos.txt
```

Windows/Linux 沿用 `requirements-cpu.txt` 或已有经验证的 CUDA 环境，再安装 `httpx==0.28.1`；原 PowerShell 离线入口继续保留。跨平台报告字体按系统已安装字体选取，也可用 `STATION_VISION_FONT` 指定本地字体文件，不自动下载字体或模型。

私有 token 文件只保存 Bearer 原文，设置权限 `0600`；worker 需要 `active admin`、`vision:work`、`data:read`、`data:ingest` 及对应镜头授权。配置通过环境变量或参数传入，不把令牌放在命令行、JSON 配置或日志中：

```sh
export PARTRACE_API_BASE='http://127.0.0.1:8000'
export PARTRACE_VISION_TOKEN_FILE='/absolute/private/vision-token'
export PARTRACE_VISION_WORKER_CONFIG='/absolute/private/vision-worker.json'
vision/.venv-track/bin/python -m vision.worker
# 单次领取与处理（队列为空时正常退出）：
vision/.venv-track/bin/python -m vision.worker --once
```

worker 通过 `/api/v1/recording-jobs/claim` 领取独占租约，以 `X-Lease-Token` 下载 `/media`、每10秒 `/heartbeat`，调用真实算法子进程；失败用 `/fail` 报受控错误码，成功用 `/complete` 提交 manifest 和真实关键帧。manifest 的 `recording_id/camera_id/config_version/duration_ms` 必须与任务相同，`source_mode=sdk`、`started_at=null`、`file_name=source.mp4`；原片由 Backend 复用，不再上传。SDK 时间只保留原生元数据，不冒充校准后的录像 UTC 起点。

B 结果仅发布 `candidate shelf_interaction`，summary 保留六字段候选详情、未请求模型复核和身份未验证标记；不将伸手、层号估计或 G 关联升级为确认拿取／自然人身份。本轮 worker 不发起 Qwen 请求。成功仅在 Backend 确认结果已发布并返回 `completed` 后记录。失租停止子进程且不能覆盖其他 worker 的结果；超时终止整个 POSIX 子进程组。默认算法超时1800秒，可用 `--algorithm-timeout` 调整。

可选 `PARTRACE_VISION_COMMAND_JSON`／`--command-json` 接受 JSON argv 数组，在独立子进程执行自定义算法，以 `{input}`、`{config}`、`{job}`、`{output}` 替换路径，不经过 shell。算法读取原片、Backend 配置和不含租约的任务 JSON，在 output 下生成 `manifest.json` 与声明的 PNG/JPEG；最终文件必须在 output 内，大小和任务身份通过校验。替换 A 算法时仍需传入 `--worker-config`，只向受控 `{gallery_stage}` 写档案，推广由 worker 管理，不能在算法内直接改共享图库。该入口用于显式替换真实算法或协议测试，不提供 mock 回退。

检查从 `vision/` 运行，使用项目解释器：

```sh
.venv-track/bin/python -m unittest discover -s tests -q
```

`tests/test_worker.py` 使用 fake HTTP transport 和明确标注的协议算法 fixture，验证心跳、失租、超时、失败、幂等身份与文件边界；这些测试不计入真实视觉效果或摄像头链路验收。实际模型执行、标定和业务效果须分别记录。

2026-10-17 本机 macOS 的 `.venv-track/bin/python`（Python 3.12.13、CPU PyTorch 2.6.0）执行上述 unittest：101项全部通过，包含29项 worker／图库发布／适配边界测试；根目录 `python -m vision.worker --help` 和 `git diff --check -- vision` 通过。覆盖 staging 失败／失租／撤权不污染、不同 attempt 不推广、终态心跳409、等锁时续租、重复推广不回滚和受权 reconcile。Windows 临时目录与 macOS `/var`／`/private/var` 别名统一按规范路径比较。这些凭据不替代新门铃录像的真实 A 运行或球机现场 B 标定验收。

同日另执行真实权重 CPU A 烟雾：在 `/private/tmp/partrace-SYNTHETIC-cpu-smoke-_jhyfkzi/` 生成标注 SYNTHETIC 的1秒蓝色视频（320×240、12fps、12帧），直接调用现有 `enroll_video.run`，实际加载仓库 YOLO26n-pose 与 OSNet 权重。PyTorch 2.6.0／Ultralytics 8.4.174 下 A 执行3.903秒，输出0条人物轨迹、0个档案，空 `archive.json` 正常生成；仅使用临时目录中明确标注的本地 receipt fixture 验证空库推广成功、重复推广不改变 CURRENT，B 的已提交 archive 路径可读。详细结果保存在该临时目录的 `SMOKE_RESULT.json`，算法原始 summary 在 `artifacts/algorithm/summary.json`。这次真实执行验证了模型加载、A 入口与空库文件边界，没有调用业务 API／数据库，没有上传合成录像；本地 receipt fixture 不是 Backend 完成回执，不计作真实 SDK 摄像头链路或人物识别效果验收。

## 视觉 HTTP 服务（station_vision.service）

A/B 队列服务在独立进程中运行，先安装 `requirements-service.txt`，从 `vision/` 目录执行 `.venv-track/bin/python -m station_vision.service`（Windows 为 `Scripts\python.exe -m station_vision.service`）。服务参数、角色映射和取片回写边界见 [configs/vision_service.json](configs/vision_service.json) 内 `_help`；端口与 Backend 的 8000 区分。开发日志与实测记录见 [README_hua.md](README_hua.md)。

## Aurora API worker 验证 · 2026-10-17

本机CPU A worker已通过受信任公网HTTPS连接35.84.190.14，实际处理App上传的22.266秒SDK历史原片和30.066秒新自动原片，Aurora均published/completed，App完成回调已回读。worker仅通过API领取、下载、续租、提交结果，不直写PG事实；AWS使用单独A凭据及图库目录。当前worker仍在本机运行，Mac/进程停止后新原片只能入库排队；B因缺现场标定及区域映射不在本次worker授权中。原片解码失败保持错误，不放宽校验冒充完成。详细边界见[D06云端验证](../openspec/changes/d06-people-recording-pipeline/verification.md#aurora-公网与真实自动事件2026-10-17)及[运维说明](../infra/people-aurora/README.md)。

### B 事件 person_id

worker交接新增独立person_id：只写已成功匹配的A图库G编号，未知/冲突为null；track_key继续保存同视频局部P轨迹。identity_human_verified仍为false，A仍只建档而不捏造区域动作事件。该映射不替代B现场标定验收。
