# 最小后端验证记录

工程布局更新：2026-10-16 按用户最新要求，源码直接平铺为 `backend/*.py` 和 `agent/*.py`，导入名为 `backend`／`agent`，不再有 `src/` 或重复内层包目录。下方旧命令是首次验证时的历史凭据，保留原样；当前命令见 Backend README，后续检查见 D07 验证记录末节。

2026-10-16（Asia/Shanghai）。本次为 development 最小数据实现，基于 dev@97d38ba，在 feat/d05-backend-mvp 工作分支开发；不代表 D05 全部（含向量）、D09 Android 联调或 D10 已完成。不关闭 Issue #5／#9，不归档完整业务工作包。

## 实现范围

- 复用已有 SQL／OpenAPI，11表／77字段及27个业务契约操作未改变。实现其中12个非 Agent、非向量、非包裹的数据操作；`/docs`／`/openapi.json` 只列本次12个操作。另有两项健康检查。
- PostgreSQL 连接池、受限静态摘要凭据、当前 active 用户及管理员／scope／资源授权、配置固定签名、完整导入、查询、实际关键帧读取、单事件人工核查保存与回读。
- 显式 development 空库初始化／用户创建，已有库／用户拒绝覆盖；API 启动不自动建表。生产静态认证与管理引导 fail closed。
- 先媒体暂存、校验与摘要，再DB事务；正常失败补偿清理，同ID同内容重放，事实与人工决定分离。提交结果不明保留可能已关联媒体，按同ID重试；进程崩溃后的孤立暂存需管理员核对清理。

## 实际环境与测试

- macOS arm64，Python3.12.13，锁定依赖见 backend/uv.lock 与 requirements.lock。
- 本项目独立 `partrace-mvp-dev` PostgreSQL17 + pgvector0.8.7 容器，数据卷持久化，主机仅127.0.0.1:54391；未操作其他项目数据库。
- 本地development库实际执行 init-db，创建11表；seed-user创建 local_admin／local_viewer。随机凭据仅在忽略的私有本地文件，不写入此文档。
- 独立 `partrace_mvp_tests` 库预装vector。每个集成测试使用自己的随机schema并清理，测试拒绝在不以 `_tests` 结尾的库中执行。
- 完整运行 `PYTHONPATH=src .venv/bin/python -m pytest -q --tb=short`，由子进程环境提供私有测试DSN及真实probe路径：**161 passed，0 skipped**。分项：runtime16、validation66、management19、media51、真实PG API9。
- 唯一已观察提示为当前 Starlette TestClient/httpx 弃用提示；未影响此批测试。CI工作流已添加，尚未上传触发GitHub Actions，不声称线上CI已通过。

## 覆盖凭据

1. 真实PG测试按当前OpenAPI JSON Schema验证返回；模拟整包首次201、同内容200、异内容409；导入碰撞时父录制也回滚。
2. 真实PG并发同内容导入与核查各三请求，精确验证 `[200,200,201]`、只保存一次；使用事务内advisory锁和READ COMMITTED锁后新快照。
3. 用户禁用／未映射、viewer写入、只读scope、镜头／录制受限、令牌撤销、其他人重放核查均拒绝；未知和重复查询、混合时间、越界、时钟未知分别拒绝。
4. 配置pins为空／非对象／重复键均503 fail closed；配置／ROI／ID／引用、数值和大小由严格模型及部署限额校验，结果不静默截断。
5. 使用真实FFmpeg生成1秒64×64蓝色视频，Pillow生成PNG，通过multipart与真实ffprobe／FFmpeg上传校验、读取原图字节；关键帧位于事件前后上下文仍允许。物理文件删除后元数据返回missing、字节端点404，核查仍保留原ID。
6. 真实媒体导入先验证未经校准UTC被拒绝，再通过测试用可信映射（实际视频SHA256等）验证UTC转偏移；此映射仅为测试机制，未声称现实拍摄时间已校准。
7. 媒体测试中的probe替身仅用于边界单元测试；API真实上传项使用本机既有 `/Applications/screenpipe.app/Contents/MacOS/ffprobe` 7.1 与本机FFmpeg9.0.2。未新安装或验证此本机helper的发行完整性。Docker交付路径由系统包提供FFmpeg工具，镜像构建未在本轮执行。
8. 实际Uvicorn HTTP进程中执行 readiness→模拟包导入→事件查询→人工保存→回读；终止该进程并启动新进程后，原核查记录再次200回读，独立于TestClient的新实例测试。
9. OpenSpec1.14.1三个活跃change strict校验通过；五项既有文档同步 `--check` 通过，SQL／OpenAPI源未修改。文档离线快照检查不是本次真实PG测试的替代。

## 仍待后续验收

当前本地可调用演示数据明确为mock、媒体missing；测试生成的真实编码文件不等于驿站真实监控／真实自动检测。组员真实事件包、真实bbox/动作关联、Android调用、SDK、向量检索和Agent尚未验收。非development正式身份、TLS、网络授权部署、持续运行监控、迁移升级与崩溃孤立媒体清理需后续完成；当前不宣称生产部署。新时间线／连续轨迹／3D／跨镜能力仍需独立契约与实现。
