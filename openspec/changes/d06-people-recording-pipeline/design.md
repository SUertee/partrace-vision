## Context

现有已发布录像事实不可更新；原生 People 检测也不能等同于团队区域动作事件。用户最新确认的顺序是原片先通过 API 写入 recordings，再交给 Vision。原片保存于 Backend 私有媒体存储，同事务建立 recordings 待发布行和持久任务；完整算法结果提交后才成为可查询的已发布事实。

## Goals / Non-Goals

目标是两台及更多授权摄像头使用同一数据流，并让代码区分原片上传完成与算法解析完成。真实输入失败保留错误与重试入口。不会凭原生 People 检测推断身份、区域动作、包裹出入库或连续轨迹。

## Decisions

采用 PostgreSQL 持久队列与短租约，由独立 Vision worker 主动领取，避免 API 线程运行推理及 App 持有算法凭证。领取与终态以服务器控制，旧租约不能覆盖新结果。Android 不访问 PG，Vision 不访问业务数据库。SDK 时间仅保留来源元数据，未独立校准不填 recordings.started_at。算法通过配置命令接入，输出既有 RecordingManifest 及真实帧文件；没有算法不宣称解析完成。

recordings 使用 `publication_status=pending|published` 区分生命周期，queued/processing/failed 仍由任务表维护，不复制两套处理状态。pending 的 manifest 摘要为 null，不能用空事件包伪装已分析。原片公开提交使用 multipart `mode=source + manifest + files`（一个 source.mp4），完整结果导入默认 result 模式保持旧请求有效。默认录像发现只列 published；直接元数据读取明确区分 pending，事件/语义/Agent 事实入口在区域与覆盖判断之前返回 recording_not_ready。仅允许 pending 一次转 published，发布后不可覆盖。

## Risks / Trade-offs

App 内事件监听受进程生命周期限制，前台接入须明确其范围；后台持续采集需要后续独立服务与系统权限。SDK 有事件但录像尚未落盘时需稍后重试。模型及真实时间校准不能从 UI 设计中推导。

## Migration Plan

新空库 DDL 增加独立任务表；已有库通过显式增量迁移创建新表和索引，不重建数据库，不修改已发布事实。旧接口保持行为。

后续 source-first 增量迁移给旧录像默认 published，仅为未发布任务补齐 pending recordings 行，关联现有原片键，保留所有任务、来源、摘要、状态和尝试次数；不重复上传真实录像。远程 dev@97067ce 的 Agent SSE 不等同于 Vision 完成，本次保留已有持久状态回读与 App 完成回调，不借用 Agent run 身份。
