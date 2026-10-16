# 驿站追迹：Anker 核心版本

本目录是2026-10-16从原code目录复制并精简的独立项目。原项目保留。当前流程：A自动人物建档 → B人物跟踪与货架交互 → 五张原始关键帧 → 可选Qwen复核 → 六字段事件和可视化。

## 运行

在当前目录打开PowerShell：

```powershell
.un_AB_station.ps1 -Device cuda
# 每段交互五张图，一次多模态请求：
.un_AB_station.ps1 -Device cuda -ReviewWithQwen
# 单独运行A：
.un_A_enrollment.ps1 -Device cuda
# 单独运行B并读取现有档案：
.un_shelf_session_test.ps1 -Device cuda -Gallery 'data/person_enrollment/archive.json'
```

环境固定为本目录 `.venv-track/Scripts/python.exe`；激活不是必要步骤。CPU运行把Device改为cpu，当前仍是离线处理，不保证实时。本机CUDA/PyTorch已随项目环境复制，其他电脑需要按requirements-cpu.txt或requirements-cuda118.txt重新建环境；本地环境仍依赖本机已有基础Python。

## 视频、标定、参数、档案

- A视频：`test_video_new/site_20261016/A/`；B视频：同目录B/。每路默认只放一段视频，多段时用VideoA/VideoB明确指定。
- 只有B拍货架。打开`shelf_calibration.html`，加载B原图或选取视频一帧，人工框出各层，从下往上编号，导出到`configs/interaction/shelf_B_*.json`。运行时默认选最后保存的标定文件，并打印路径。
- 重要参数：`configs/vision_runtime.json`。修改前面的数值；_help逐项说明范围、含义和增加/减少影响。使用RuntimeConfig可指定另一份参数。每次结果保存实际参数快照。
- 人物档案：`data/person_enrollment/G编号/`；根目录archive.json与profile_registry.json保持编号。每条合格轨迹精选3～5张，当前有效代表图最多5张，旧图片和必要原始证据保留在本人history/evidence中。根目录index.html可浏览档案。
- A先处理完成，B只读取该次A的档案快照。G编号是匿名外观关联；无法确认时公开person_id为null。本地P轨迹用于交互和模型人物校验。
- A/B先后录制，各自保留视频源时间，不推断未知录制间隔。

## 输出

`outputs/<RunName>_A/`：comparison.html、A_profiles_review.mp4、profiles_result.json、gallery_snapshot.json及原始截图。

`outputs/<RunName>_B/`：comparison.html、interaction_review.mp4、events.json、interaction_summary.json、keyframes/五帧及短片。

公开事件严格保持`id, person_id, start_ms, end_ms, action, shelf_level`。不加ReviewWithQwen时是算法候选，取件结论和层号仍待复核。每次用新运行名，不覆盖旧结果。

检查现有凭证：`.\setup_qwen_key.ps1 -CheckOnly`，不发送请求。缺失时运行不带参数的setup_qwen_key.ps1，用隐藏输入保存当前Windows用户DPAPI凭证。凭证不在项目内，不复制、不写入JSON或Git。

已完成B观测可使用ObservationsRun复用，要求源视频、配置和档案哈希一致。仅刷新已保存报告/视频可运行：

```powershell
.\.venv-track\Scripts\python.exe -X utf8 -m tools.reexport_shelf_session <B运行名>
```

## 核心结构

```text
station_vision/reid/               A选帧/建档、OSNet、B人物关联
station_vision/shelf_session/      交互起止、伸收、层号、五帧、模型复核、导出
station_vision/shelf_calibration/  标定数据与图像配准验证
station_vision/multiframe_review/  兼容API和私密凭证读取
station_vision/models.py           人物姿态/ByteTrack/BoT-SORT
station_vision/runtime_parameters.py 参数校验
models/yolo26n-pose.pt             人物模型
models/reid/                      OSNet模型、来源及许可证
tests/                            当前主流程测试
```

已移除旧RetailAction、YOLOE、Florence/KLT实验、独立手工截图建档入口、旧视频、历史输出和无关文档。主流程不依赖这些模块。保留了已有Git历史和唯一运行环境；环境内依赖不逐包卸载，以免破坏PyTorch/Ultralytics运行。

当前参数解释见docs/统一参数与人物档案_20261016.md，图片标定说明见docs/B机位_图片与视频选帧标定_20261016.md。迁移及验证记录见docs/核心副本_整理记录_20261016.md。

## 检查

```powershell
.\.venv-track\Scripts\python.exe -X utf8 -m unittest discover -s tests -q
```
