# 核心项目地图

项目根目录 `/srv/partrace-vision/vision`（Linux 服务器，非 Git 仓库；2026-10-16 从原 Windows "code - anker" 项目复制精简，原项目保留完整历史）。统一解释器 `.venv-track/bin/python`（Python 3.12.3；torch 2.6.0+cu126；NVIDIA L4 24GB，驱动 580.178.04 / CUDA 13.0；依赖见 `requirements-cuda126.txt`）。

## 入口命令

原 PowerShell 入口（`run_AB_station.ps1` 等）未随副本迁移，直接用 Python 模块。在项目根目录执行，`-X utf8` 必带；运行名仅限字母数字与连字符／下划线。

```bash
# A 自动建档（读 A 视频，追加匿名档案）
./.venv-track/bin/python -X utf8 -m station_vision.reid.enroll_video \
  --video test_video_new/site_20261016/A/<视频文件> --run-name <RunName>_A --device cuda

# B 货架交互（默认选最新 configs/interaction/shelf_B_*.json 与 B 目录唯一视频）
# AB 连跑时 B 读取该次 A 的档案快照；--review 才启用 Qwen 五帧复核
./.venv-track/bin/python -X utf8 -m station_vision.shelf_session.pipeline \
  --run-name <RunName>_B --device cuda \
  --gallery outputs/<RunName>_A/gallery_snapshot.json

# 独立 B（读主档案）
#   --gallery data/person_enrollment/archive.json

# 仅刷新已保存 B 运行的报告/视频（不调用任何模型）
./.venv-track/bin/python -X utf8 -m tools.reexport_shelf_session <B运行名> [--report-only]

# 单元测试（72 个用例，合成数据，不需要视频）
./.venv-track/bin/python -X utf8 -m unittest discover -s tests -q

# 凭证可用性检查（不发请求；Linux 读 STATION_VLM_API_KEY）
./.venv-track/bin/python -X utf8 -m tools.qwen_credential_check
```

B 其他参数：`--video`（图片标定 JSON 必须显式给视频；B 目录多段视频时也要指定）、`--config`（指定标定 JSON）、`--observations-run`（哈希一致时复用已保存观测）、`--reference`（对照评估 JSON）、`--model` / `--base-url`（VLM 覆盖）。

## 主流程与数据

当前只有 A 自动建档 → B 货架交互及匿名外观关联这一主流程。重要参数统一 `configs/vision_runtime.json`（`_help` 逐项说明范围与影响），货架多边形由 `shelf_calibration.html` 导出到 `configs/interaction/shelf_B_*.json`，运行时默认选最新保存的 B 标定并打印路径。

人物档案根目录 `data/person_enrollment`：G 编号独立目录、`archive.json`、`profile_registry.json`（编号保留表，不要手工清空）与 `index.html` 浏览页。每条合格轨迹精选 3～5 张有效代表图，被替换旧图进 `history/`；样本不足时拒绝新建档案。缺乏身份支持时 `person_id=null`；本地 P 轨迹用于交互和模型人物校验。

输出在 `outputs/<RunName>_A/B`：`comparison.html`、导出视频、`events.json`、`keyframes/` 五帧及短片等；六字段契约不变，B 结果含 `person_archive_snapshot.json`。已有运行 `anker_core_validation_20261016_01_A/B` 为原 Windows 机器产出（summary 的 `source_path` 指向 `C:\...`，源哈希校验仍有效，重导出可用）。每次用新运行名，不覆盖旧结果。

## 本副本与原项目的差异（注意）

- **无 A/B 视频**：`test_video_new/site_20261016/` 目前只有《新场景接入说明.md》。跑完整流程前需先放入 A（建档）与 B（货架）各一段视频。
- 原所有 `.ps1` 入口（run_AB_station、setup_qwen_key 等）未迁移；Linux 凭证只用 `STATION_VLM_API_KEY` 环境变量，密钥不落盘。
- `contracts/interaction_event.schema.json` 与 `README_hua.md` 提到的 `docs/*.md`（统一参数、标定说明、迁移记录）不在本副本；契约以代码为准，参数说明看 `vision_runtime.json` 的 `_help`。
- Linux 中文渲染使用 `assets/fonts/NotoSansSC-Regular.otf`（`FONT` 自动解析，勿再硬编码 Windows 字体路径）。
- 旧 Florence/KLT/RetailAction/YOLOE 及其权重、历史输出/旧视频均已移出本项目，不使用旧文档命令。Python 源码只由当前副本加载；使用唯一环境执行测试与主流程。
