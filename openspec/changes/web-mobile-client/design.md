## Context

Android 用户端位于 `android/core/.../main/station`：`StationApp` 管理首页、事件、AI 追溯、我的四入口及消息、审核、浮层；后端数据经 `TraceAgentClient` 以独立 Bearer 读取 `/api/v1`。SDK 设备、直播和检测记录来自官方 ESIotKit，浏览器无法调用。

## Goals / Non-Goals

- 目标：手机浏览器可完成 Android 中基于后端的查询、证据查看、AI 追溯与审核记录回读。
- 目标：同一后端、同一 Agent、同一契约，两端互不影响。
- 非目标：PC 管理后台、修改后端接口、模拟 SDK 设备、3D 场景（Android 未实现）。

## Decisions

### 1. 同源 API 与凭据

Web 只请求相对路径 `/api/v1/...`，开发期由 Vite 代理到 `PARTRACE_API_TARGET`（默认 `http://127.0.0.1:8000`），部署时由同源反向代理提供。Backend 无 CORS 中间件，也无登录接口，Web 沿用 Android 的独立 Bearer：默认保存在 sessionStorage，用户勾选后才保存到 localStorage。401 引导回服务设置。

### 2. 运行推送

`GET /agent/runs/{id}/events` 需要 Authorization 头，EventSource 不能携带，故用 fetch 流解析 SSE。快照 `id:1` 与终态 `id:2` 之外的 keep-alive 忽略；流结束未见终态时回读运行，仍为 running 则携带 `Last-Event-ID` 退避重连。后端当前不返回工具 `progress`；若返回则显示，否则只显示已确认的计划步骤，不虚构工具进度。

### 3. SDK 能力

首页设备按已发布录像的 `camera_id` 聚合，名称取配置；在线状态和直播属于 SDK，标为未知／仅 App。事件页展示后端区域事件时间线，SDK 检测记录标为仅 App。

## Risks / Trade-offs

- Bearer 存在浏览器存储中，适合 development；正式认证仍待 Backend 方案。
- 无真实后端联调凭据时，只能用契约样例验证界面，不能宣称业务闭环。
