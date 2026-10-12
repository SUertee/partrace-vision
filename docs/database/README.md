# ParTrace Vision 数据库关系草案

**版本：0.2-draft；依据：技术方案 r51、PRD r230；日期：2026-10-12。** SQLite 仅用于本地 DDL 与约束验证，生产引擎待官方资源和实际部署确认。仓库没有现有数据库迁移或 ORM；本设计不代表服务已实现。

- [schema.sql](schema.sql)：13 张拟表、索引、CHECK、复合 FK 与本地验证用触发器。
- [schema-catalog.json](schema-catalog.json)：执行上述 DDL 后从 SQLite PRAGMA 导出的真实结构，不含数据记录，`row_count` 为 `null`。
- [schema-canvas.html](schema-canvas.html)：离线关系图，下载后双击即可查看；搜索表或字段，点表查看完整结构与关系。
- [接口约定](../api/README.md)：四模块交接字段与来源口径。

## 表与模块边界

| 领域 | 拟表 | 保存内容 |
| --- | --- | --- |
| 设备与采集 | cameras、zone_configs、recordings、coverage_intervals | 设备/区域版本、输入时间基准、实际采集覆盖 |
| 视觉索引 | index_imports、track_segments、events | 索引批次、有限单镜匿名片段、观测/候选事件 |
| 证据与保留 | evidence、event_evidence、retention_cleanup_logs | 文件引用、可用/到期状态、实际清理结果 |
| 核查与复核 | journeys、journey_items、review_actions | 服务端复现快照、当前真实 FK 选择、不可变复核历史 |

算法提供输入与索引包，后端校验并持久化，Android 与 Agent 读取同一事件/证据。SDK 在 Android 工程验证，后端接收标准数据或文件；SDK 原生设备事件使用 `event_type=device_detection`、`metadata.raw_sdk_type`，可以没有区域、轨迹或证据，不自动生成货架行为和人物轨迹。

## 时间、区域与版本

API 使用 ISO 8601 UTC；SQL 的 `*_epoch_ms` 为整数 epoch 毫秒，视频偏移 `*_offset_ms` 为毫秒。录制保留 `raw_start_epoch_ms`、`raw_end_epoch_ms`、`time_base_num/den`、原始 PTS、`clock_offset_ms` 与 `time_mapping_version`。

字段映射：API `started_at/ended_at` 对应 SQL `start_epoch_ms/end_epoch_ms`，`captured_at` 对应 `captured_epoch_ms`；API `start_offset_ms/end_offset_ms/offset_ms` 原样使用毫秒。未知时原始 epoch 列仍可保存源值，但 API 的对齐 UTC 保持 null。

对齐约定为 **`start_epoch_ms = raw_start_epoch_ms + clock_offset_ms`**，终点同理。未验证时 `alignment_status=unknown`，对齐 UTC 和偏差允许 `NULL`，保留原因，不用 0 冒充已校准；有实际对齐凭据后才标 `aligned`。PTS 转媒体毫秒须结合原始时间基准，不能直接将 tick 当毫秒。子对象与录制的映射一致性仍需导入器校验。

事件和匿名片段允许起止相等表示瞬时点。查询窗口为 `[qstart,qend)`：点事件使用 `t >= qstart AND t < qend`；跨度相交使用 `start < qend AND end > qstart`。未知 UTC 不参加确定的时间命中，作为无法对齐的缺口返回。

`zone_id` 是 E3 等业务区域；`zone_config_id` 标识摄像头内一份不可覆盖的 ROI/标定配置。不同摄像头和版本可有同一个业务区域，事件用复合 FK 固定实际配置。画面归一化坐标与区域示意位置分开，不将未标定点当作地面位置。

`recording_id` 在重复索引时保持稳定。每次重算新增 `import_id` 与 `index_version`，旧片段/事件 ID 和完整批次不变。Recording 保存输入原始时间和首次映射；`index_imports.time_mapping_snapshot_json` 保存该版实际使用的原始 epoch/time_base、偏差、对齐结果、状态和验证凭据。时钟调整必须新增 import 和映射版本，以及新的事件/证据定位 ID，不覆盖旧时间或证据。GET 和查询使用选定 import 的有效映射与事件 UTC，不把 Recording 首次对齐值当作所有版本的时钟。校准凭据和换算一致性由服务端验证，SQL 不能证明实机时钟正确。

默认按每个 Recording 的最大 **完整** `index_version` 查询，不按最新时间选择未完成批次，也不混用该录像两个版本；保存 Journey 时固定本次 `import_ids`，回读使用原批次。

## 状态分别保存

- 输入来源：`source_mode=prerecorded|sdk|mock`；检测来源：`automatic|manual|sdk_event|mock`；事件强度：`observation_status=observed|candidate`。三者不能相互替代。
- 采集类型：`continuous|frame_sample|gap`；索引状态：`pending|partial|complete|failed`；媒体可用性：`available|missing|expired|deleted|unverified`。有视频但未索引不算零事件，有索引但媒体过期也不算无采集。
- Recording 的 `media_kind=video|frame_set|event_metadata`；Evidence 为 `keyframe|video_clip`。仅 SDK 元数据时使用 `event_metadata + missing`，不说已经取得帧；该输入不能产生连续或帧样本覆盖。一条 `frame_sample` 是离散时间点，frame_set 不填成连续覆盖，`gap` 必须保留原因。只有实际采集凭据才能产生帧覆盖。
- Track 消失记录 `end_reason`，遮挡/断流记录未知区间；不等于离站。复杂动作保留增强候选标签，不生成未观测身份、运单或盲区路径。

## 导入、快照与并发

导入以 `producer_id + idempotency_key` 唯一并保存内容 SHA-256；同键同内容返回原批次，同键不同内容应返回冲突。`recording_id + index_version` 唯一，每个来源事件/局部片段在该批次内唯一。导入事务先建立 pending 批次，写事实和证据引用，最后设置 complete；完整批次及其事实被触发器封存。部分或失败批次不可冒充默认查询结果。

`event_evidence` 用真实 FK 保存事件与证据关系，复合 FK 保证同录像/摄像头，已知时间映射不能错配。`journey_items` 用显式 Event/TrackSegment/Evidence FK 保存当前选择；CHECK 保证每项只选一个类型。JSON 则保存 query、固定 import_ids、原始引用元数据、未知区间及前后结果，支持媒体失效或新索引出现后重现历史。JSON 内部引用属于应用层逻辑关系，不能算数据库 FK；服务端必须生成和校验快照，不能直接信任客户端。

SQL 的 `server_snapshot_json` 为 `{baseline, human_selection}`：baseline 是 API 的只读 `Journey.server_snapshot`，创建后不能变；human_selection 是当前人工分组、选择和决定，可随复核更新。ReviewAction 审计完整前后状态，确认不会把原始 Event 从 candidate 提升 observed。API 的 `Journey.items` 对应当前选择，SQL `journey_items` 只约束选中的真实引用；`group_id/selected/decision` 等分组和决定细节保存在 human_selection，不从这些字段推断身份。SDK 原始类型放在 `events.metadata_json`，其他非检索补充元数据可放 Evidence 的 `metadata_json`。

Evidence 的短片上下文为 `context_start_offset_ms/context_end_offset_ms` 两个可空整数列；提供时必须成对、满足 `0 <= start <= offset_ms <= end`。偏移未知时不能声称可定位上下文；总时长边界、对应文件和校准是否真实仍由导入器验证。

Journey 初始 `review_version=1`。复核步骤在一个事务完成：

1. 先按当前操作表的 `actor_id + idempotency_key` 检查重放：请求哈希相同返回已保存响应，不同则冲突；不要在版本检查失败后丢失幂等重放。
2. 读取当前 Journey 并生成前后服务端快照，只改变 human_selection；动作使用 `confirm|reject|split|comment`，提交 `expected_review_version`，`from=expected`、`to=from+1`。
3. 本 SQLite 草案的 `INSERT review_actions` 触发器检查版本/前快照，再原子更新 Journey；不需要调用方再更新一次版本。
4. 在同一事务替换 `journey_items` 为新版本选择，再提交。其版本复合 FK 延迟到提交检查，旧版本选择不能留在当前记录中。

旧版本、前快照或更新时间冲突映射为 HTTP 409。ReviewAction 只能追加，不更新/删除；确认/拒绝/拆分不改原始事件。本地触发器比较已保存的快照文本，服务端应复用 baseline 并一致序列化；生产实现可改为事务内 CAS 和规范化 JSON 比较，但必须保持同样的原子性与幂等行为。

## 保留与清理

RetentionPolicy 首版为后端的版本化配置，不新增策略表，也不预填尚未确认的保留时长。资产入库时保存策略版本与到期时间；每次实际清理尝试把策略快照、目标、时间、结果和失败原因追加到 `retention_cleanup_logs`。同一清理任务的尝试编号唯一，失败可重试，文件已不存在可记为 `already_missing`。

视频和关键帧独立保留。清理只删除媒体文件并更新可用状态，数据库不级联删除 Recording/Event/Evidence/Journey/ReviewAction；历史引用和人工说明继续可读。到期但清理失败仍需明确日志；媒体状态为 expired/deleted 时不能再签发可用 URL。`storage_key` 由服务端分配，available 需要实际文件验证元数据，DDL 并不能证明文件存在或 SDK 可用。

## 本地结构导出与验证

从仓库根目录运行，使用 Python 标准库，无需业务服务：

```sh
python3 scripts/export-schema-catalog.py
python3 scripts/export-schema-catalog.py --check
python3 scripts/build-schema-canvas.py
python3 -m json.tool docs/database/schema-catalog.json >/dev/null
```

导出器仅在 `:memory:` 执行 SQL，检查 `foreign_keys=ON`、`foreign_key_check` 和 `integrity_check`；结构字段从 PRAGMA 生成，CHECK 从执行后的 CREATE TABLE SQL 提取。文件元数据记录来源、日期、草案状态及 schema 哈希；没有连接现有数据库或执行现有数据迁移。

首版仍需接口实现验证：请求鉴权、快照内引用及查询范围校验、最新完整批次选择、真实时间映射、文件上传与签发、保留配置和实际清理。跨镜 CandidateAssociation、向量检索、独立 RetentionPolicy 表均后置。

本轮本地验证已实际覆盖：错配摄像头/录像/索引/时间版本的引用拒绝、SDK 原生事件无轨迹、离散帧覆盖、未知时钟、幂等唯一键、完整批次封存、复核版本冲突及追加历史、延迟 FK 的提交检查、媒体过期后保留引用。测试输入仅在内存生成，未导入业务数据或操作真实媒体；未验证业务服务、真实文件清理和 SDK。
