# events.person_id 验证 · 2026-10-17

以下先记录字段开发工作区和已运行 AWS 补丁的实测；GitHub 提交在最新 dev 基线单独整合，结果见文末。12 表/105 列属于 People 开发/部署基线，本次 dev 新空库为 11 表/80 列。

本次新增可空的 A 图库 G 编号。`track_key` 保留录像内局部 P 轨迹；B 仅在同一局部人物成功匹配有效的 `identity.profile_id`、且复核未指向其他局部人物时写 G，未知和冲突为 `null`。A 继续只建档，不凭空产生区域事件。G 表示匿名外观关联，不表示自然人身份。

## 代码与隔离测试

- PostgreSQL 集成：`test_event_person_id.py`、`test_recording_jobs_postgres.py`、`test_api_postgres.py` 共 35 项通过，使用独立 `_tests` 库中的随机 schema；真实 ffprobe 可用，没有跳过媒体检查。
- 验证覆盖已匹配 G 的导入及回读、保留 P 轨迹、同内容 200 重放、换 G 返回 409、事实更新被触发器拒绝、非法编号拒绝、增量迁移与旧行不变。新旧未知清单（省略或显式 null）的摘要一致，因此旧导入仍可重试。
- `backend/tests/test_validation.py` 的 82 项验证通过；`vision.tests.test_worker` 的 20 项通过，包含已匹配、冲突、未匹配、P 冒充 G 和复核人物不一致的映射检查。
- 新 SQL 在隔离空 PostgreSQL 库加载并重新导出系统目录；该本地设计基线为 12 表、105 列。五项设计同步检查、样例校验和本 change 的 OpenSpec strict 校验通过。

## 已有运行库与云端 API

- 本地 development 库使用显式 `migrate-event-person-id` 管理命令执行 004 增量迁移，1 条旧事件全部旧字段逐值不变，新列为 null。
- Aurora `partrace/public` 执行相同增量迁移。迁移前完整 events 快照保存在 EC2 独立发布目录的 root 私有备份中；迁移后 3 条旧事件全部旧字段逐值不变，新列均为 null。没有回填推测编号，也没有为演示写入合成业务事件。
- 云端基于已部署的 `partrace-vision-api:people-20261017` 制作窄补丁镜像 `partrace-vision-api:person-id-20261017`，保留其 `shelf_level` 字段及原有 People 流程。独立目录 `/opt/partrace-person-id-20261017`；原 People 容器保留为 `partrace-people-api-20261017-before-person-id`，原 8000 API 保留。
- 云端模型隔离验证：接受 G、拒绝局部 P、未知摘要兼容、保留 shelf_level。新容器仍在 loopback 8001，`/health/ready` 返回 200；契约保持 21 个数据/任务操作，Event 响应声明可空 person_id。
- 公网 `https://35.84.190.14/health/live` 返回 200；未认证的 recordings 返回 401，现有 App 凭据读取 recordings 返回 200。TLS 使用正常证书验证，没有忽略证书错误。
- 若需回退服务，将新容器停止并移走，恢复保留的旧 People 容器名称后启动；新增可空列可以保留，不能删除历史记录或整库初始化。字段已存在时管理命令拒绝重复执行。

## 仍未验收的边界

B 机位缺少现场标定及区域映射，现有真实 B 原片仍排队。本次验证了字段契约、持久化与映射逻辑，没有证明真实 B 的 G 匹配效果，也没有将 A 建档完成解释为业务区域事件完成。当前常驻 A worker 不处理 B；启用 B 时须使用包含本次映射的 worker 代码、不可变的新标定版本及已提交图库快照。本次提交仅合入 person_id、迁移、现有 Vision 出口和对应契约，不合入其他并行的 People/Android/Agent 实现。

Aurora 可只读检查：

```sql
SELECT event_id, recording_id, track_key, person_id
FROM public.events
ORDER BY recording_id, start_offset_ms, event_id;
```

## 本次合入 dev 的独立回归

基于 `origin/dev` 的 `df2de62` 创建独立工作区，仅整合 person_id、004 迁移、既有 Vision 六字段事件出口、契约和文档。保留 dev 的 shelf_level、可空 zone_id 与 Agent cards/SSE；不复制主开发工作区其他未提交实现。

- Backend/Agent：在 Python 3.12 的既有 venv 中将本次工作区放到导入路径首位，运行 `pytest.main(['backend/tests', 'agent/tests', '-o', 'addopts=', '-q', '--tb=short', '-rs'])`，**320 passed，0 skipped**。环境显式使用 `partrace_mvp_tests` 隔离库与真实 ffprobe/ffmpeg；每项测试创建并清理自身随机 schema。
- 首轮全量回归发现旧测试仍 patch `psycopg.connect`，而已合入的 IAM 管理命令通过 `connection_class` 连接；测试已改为在实际工厂边界注入，并补充新迁移的非 development 拒绝检查。Agent 已有 SSE 第 9 个操作，旧 8/22 断言同步为 9/23。没有改动这些既有运行功能。
- Vision：从本次 `vision/` 运行 `python -m unittest discover -s tests -q`，**73 tests，OK**。覆盖 G 匹配、冲突、未知、局部人物不一致与非法编号；原六字段结构和局部 session 人物保持。另修正一处 macOS `/var` 与 `/private/var` 的测试路径等价比较，不改变图库运行逻辑。测试素材及编号均为 fixture，不代表真实摄像头识别效果。
- 用本次 SQL 在专用空 PostgreSQL 库实际建表并导出、再次 live check：**11 表、80 列、13 外键、30 索引、55 CHECK、25 触发器**。这份 dev 契约不混入独立 People 部署的任务表；临时库验证后清理。
- 五项设计同步检查通过；使用仓库固定 OpenSpec **1.14.1** 校验全部 active/archived 规格；相对链接、JSON 和 diff 空白检查通过。
- 在 Aurora 对交付的 `aurora-people-status.sql` 实际执行 `BEGIN READ ONLY`：三组查询成功，结果分别为 2 个状态汇总、17 个 People 任务和 3 条旧事件。仅只读检查，旧事件 person_id 仍为 null；没有上传合成事件。

本次提交已提供 dev 现有完整包入口可用的 person_id 契约。源码合入不等同于将完整 People 部署从 dev 重建，也不代表 B 标定或真实匹配已验收。
