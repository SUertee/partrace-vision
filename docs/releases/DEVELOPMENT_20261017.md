# 2026-10-17 开发整合版

本次从 `dev@8fc6771` 建立独立发布工作区，固定原开发目录的代码快照后整合。原开发目录的文件、Git 暂存区和运行服务保留。本次交付代码和文档，不执行 EC2 重部署，不关闭 D02–D10 的业务验收 Issue。

## 本版包含

- Android：用户六张设计稿、三入口、消息空态/通知提示、站点与静音弹层、审核只读/搜索/详情、SDK 设备首页及新详情、原事件/回放/设置导航。
- People：真实 HUMAN 事件发现、完整录像等待/下载、严格媒体校验、稳定任务 ID、断网重试和持久状态回读；原片先通过 POST recordings source 模式入库，解析后回调。
- Backend：recording_jobs、pending/published、独占租约、续租、原子完成、失败及受控重排；完整包的 201/200 语义保留，source 新建为 202、重放为 200。
- Vision：独立 HTTP worker、真实 A/B 算法适配、CPU 环境说明、A 图库 staging/CURRENT 与受权恢复；B 无真实标定明确报错，不发布伪成功。
- Agent：Android 提问/计划确认/运行/停止/历史/引用，Backend cards 兼容迁移，内置只读 Skill 与可选语义不可用后的受限普通查询。
- 交接：001–004 显式增量迁移、People/Aurora/IP HTTPS 运维脚本、SQL/OpenAPI/样例/结构图、原始设计参考和不含真实摄像头画面的 UI 预览。

保留最新 dev 的 shelf_level、可空且默认 E3 的 zone_id、可空 G 编号 person_id、cards/SSE 与非空 UTC 的声明式输入规则。修复发布快照中 AudioRecord.start 后误输入的单个 @ 字符；保留现有 Linux 字体路径兼容与 A worker 图库隔离。

## 本次重新执行的检查

| 检查 | 本次结果 | 范围 |
| --- | --- | --- |
| Backend/Agent | 401 passed，0 skipped | Python 3.12、独立 `_tests` 库与随机 schema、真实 ffprobe/ffmpeg；使用本次发布目录的源码 |
| Vision | 103 tests，OK | 既有离线算法和 worker/图库协议测试，不调用外部模型 |
| Android | 34 项目标测试，0 失败/跳过 | TraceAgent、People、SDK 排序和缓存 |
| Android 构建/lint | assembleDebug、lintDebug 通过 | JDK 17.0.2 / Gradle 8.13，lint 70 Warning、0 Error/Fatal；未覆盖安装到现有设备 |
| SQL | 12 表、106 列 | 独立空 PostgreSQL/pgvector 真正加载并读取系统目录，15 外键、34 索引、62 CHECK、25 触发器 |

Android 仅在发布副本使用官方 AAR及空凭据模板构建，未复制用户 SDK 登录、Backend Bearer 或模型 Key。Debug APK SHA-256：`b42e014456fc6d30c3c2de36708b0752035a684f905b3ad736ac8b2930457864`；APK 不提交 Git。工程按 Android README 准备私有配置后运行。

实际命令：

```sh
# 仓库根目录；测试库通过环境变量设置，名称必须以 _tests 结尾。
python -m pytest backend/tests agent/tests -o addopts='' -q --tb=short -rs
cd vision
python -m unittest discover -s tests -q
cd ../android/ESIotKit-DemoApp-Android
./gradlew :app:assembleDebug :app:lintDebug :sample-core:testDebugUnitTest \
  --tests '*TraceAgent*' --tests '*People*' --tests '*SdkDeviceOrder*' --tests '*Cache*' \
  --console=plain --offline
```

Backend 与 Vision 复用开发目录已有 venv，测试导入路径明确指向本次发布副本。SQL/API 五项同步、OpenSpec 1.14.1 全部规格、链接/JSON/差异及敏感文件检查另记录于[本次发布验证](../../openspec/changes/d10-local-development-release/verification.md)。

## 源码与已部署服务

源码整合后包含 35 个设计操作，默认挂载 21 个 Backend；启用 Agent 后挂载 30 个，P1 包裹 5 个仍未实现。新空库为 12 表/106 列。

AWS 仍运行 `person-id-20261017` 独立镜像：21 个操作、Agent 关闭，Aurora 为 12 表/105 列且未加 cards_json。此前只读快照为 A completed 15、B queued 2；旧 events 3 条，G 编号均为空。这是已记录的运行状态，不把发布源码当作重新验收或部署。

已有真实 A SDK→API→Aurora→CPU 建档→App 回调凭据见 [AWS 记录](../deployment/AWS_PEOPLE_20261017.md)。本轮不重新触发硬件事件、不调用外部模型、不改业务数据。

## 仍待完成

1. 当前球机 B 的现场标定/区域映射、新配置版本与旧 pending 任务迁移策略，第一条真实货架事件、关键帧和 G 关联。
2. worker 服务器常驻、Android 真机/后台和生产身份；当前 A worker 在 Mac 上运行，自动发现要求 App 前台及 SDK 登录。
3. 真实 Agent 完整成功答复、真实 Embedding 空间和有效事件/证据引用。已有计划成功、执行失败/超时记录；Skill 的离线成功不替代外部模型验收。
4. Android 人工保存及重启回读，同一真实事件的 D10 演示脚本与录屏。审核只读界面不表示保存已完成。
5. Bedrock/S3/监控的 `d05-d07-aws-integration` 当前仅为待评审设计；本版不声明已实现或上线。P1 包裹业务仍待开发。

私有配置、密钥、SDK AAR、签名、缓存、真实录像及人物帧不随源码提交。参考图和 UI 截图仅为用户设计与已明确标注的模拟/黑底预览。
