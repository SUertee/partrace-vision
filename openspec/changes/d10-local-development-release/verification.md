# 本次源码整合验证

日期：2026-10-17（Asia/Shanghai）。本次交付开发源码版本，关联 D02/D04/D05/D06/D07/D08/D09/D10，不关闭各工作包。发布范围及剩余依赖见[版本说明](../../../docs/releases/DEVELOPMENT_20261017.md)。

## 固定范围和整合

以 `dev@8fc6771` 建立独立临时发布目录。使用临时 Git index 固定原开发目录的 222 个变更文件，原 index SHA-256 前后相同，没有 stash、reset、切换原分支或覆盖原工作文件。合并后保留最新 dev 的 shelf_level、可空 zone_id、person_id、cards/SSE 和 UTC 声明规则；source 模式的 SDK 起点仍为 null。

保留公开代码与模拟/黑底 UI 预览，不收录 `.local-state`、SDK AAR、生成的三份登录/模型配置、Backend Bearer 资产、签名、真实录像或人物帧。发布副本修正 AudioRecord.start 后多余 @，并将 worker 所需 httpx==0.28.1 放入各平台共享 requirements。A 档案报告保留 Linux 兼容的历史图片路径解析及新增的字体 helper / profile_root 隔离。

## 本次实际执行

- Python 3.12 复用已有 venv，导入路径断言指向本次发布目录；隔离 `partrace_mvp_tests` 库中随机 schema、真实 ffprobe/ffmpeg：Backend/Agent 全量 **401 passed，0 skipped**。覆盖 source/pending/lease/原子完成/迁移/媒体与现有 Agent、Skill、语义边界。
- Vision `.venv-track/bin/python -m unittest discover -s tests -q`：**103 tests，OK**。包含现有算法及 HTTP worker/图库发布/身份映射协议；负例日志中的失败码来自明确 fixture，不是本轮真实摄像头任务失败。
- Android JDK 17.0.2、Gradle 8.13、AGP 8.11.1、compileSdk 36，缓存依赖 `--offline`；`assembleDebug`、`lintDebug` 和 TraceAgent/People/SdkDeviceOrder/Cache **34 项测试通过、0 失败/跳过**。lint XML **70 Warning、0 Error/Fatal**，构建 32 秒。仅在发布副本准备官方 SDK 与空凭据模板，未复制登录或模型凭据，未覆盖安装设备。
- Debug APK SHA-256：`b42e014456fc6d30c3c2de36708b0752035a684f905b3ad736ac8b2930457864`；APK 属于忽略的构建产物，不上传 Git。
- SQL 在专用空库 `partrace_local_publish_*` 实际加载并两次读取 pg_catalog / live check：**12 表、106 列、15 外键、34 索引、62 CHECK、25 触发器**。仅处理隔离结构库，不迁移已有业务数据库。
- 重新导出 schema catalog、生成数据库/API HTML并同步工作台，35 个 API 操作（5 个 P1 包裹操作未实现）；固定 OpenSpec **1.14.1**：active/spec **15 项通过**，archived **4 项通过**。不使用全局旧 CLI 1.5.0 的结果代替固定版本。
- `python -m vision.worker --help` 通过。新依赖使用当前测试环境已有的固定 httpx 版本，不升级模型或权重。

五项设计同步、完整 Markdown 相对链接/JSON/diff 和提交凭据检查在最终提交前核对；GitHub CI 以本次 PR 的实际检查结果为准，不预先宣称远端通过。

## 验证边界

本轮不调用外部模型、不生成业务向量、不触发摄像头新事件、不安装覆盖运行中的 App、不部署云端或修改 Aurora。已有 A 真实链路、UI 模拟器与外部模型失败记录保留为各 change 的历史凭据，不冒充本轮重新测试。

B 标定、真实 B 事件/帧/G 关联、真实 Agent 最终答复、App 人工保存与重启回读、Android 真机/后台及 D10 同事件演示仍待完成。Bedrock/S3/监控仅有待评审方案；生产鉴权未交付。代码整合成功不代表这些业务验收完成。
