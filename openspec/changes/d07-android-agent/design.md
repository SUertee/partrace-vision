## Context

Android 使用现有 Kotlin/Compose Common 壳和 Android 平台实现。后端 Agent 与数据 API 共用地址，开发 Bearer 来自私有引导文件，外部模型只在 Backend `.env` 中配置。

## Decisions

- 平台控制器以 StateFlow 提供无凭据的视图状态；网络使用现有 OkHttp 和 IO dispatcher，禁用重定向。活动页使用至少两秒间隔读取运行，不将 SSE 快照误当完整运行。
- 录像与区域来自授权 API；完整视频窗口使用真实 duration_ms，保留数据来源。计划 query/query_text/steps 展示后显式确认，按既有合同启动。
- 确认前可编辑 query_text 和 steps；首次分配 run_id 后固定实际请求载荷，网络重试不能使用修改后的计划覆盖同一 ID。
- 创建会话、消息、运行前持久保存稳定 ID 和相应载荷；请求结果不明时重放同一载荷，运行 ID 跨进程回读。停止以服务端终态为准。
- 本地可选资产只含 base_url/bearer_token；运行时配置和恢复信息放 noBackupFilesDir。错误不泄露响应内部日志或配置秘密。
- 结果按四种 kind 渲染；点击证据重新鉴权取当前元数据和图片，不依赖持久卡片的媒体快照。历史按 opaque cursor 翻页。
- 旧开发数据库通过显式命令添加 dev 已有 cards_json 及约束；已有 completed 运行缺少卡片时拒绝迁移，不回填伪造卡片，不自动建库或删除历史。

## Limits

不新增运行列表、不实现自动业务保存、摄像头控制或后台常驻分析。现有服务关闭 Agent、模型缺失、无已发布录像时明确显示失败或空态。
