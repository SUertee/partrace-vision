# Mobile Web 用户端

`web/` 是 ParTrace Vision · 驿站追迹的手机网页入口，用 React + TypeScript + Vite + React Router 实现，迁移 Android 用户端中不依赖官方摄像头 SDK 的页面与功能。行为要求见 [web-mobile-client](../openspec/changes/web-mobile-client/proposal.md)。

## 本地运行

需要 Node.js 20+。在本目录执行：

```powershell
npm install
npm run dev          # http://localhost:5173，局域网地址可供手机访问
npm run typecheck
npm test
npm run build        # 产物输出到 dist/（不提交）
npm run preview
```

浏览器只请求同源 `/api/v1`；开发与预览服务器把 `/api` 转发到 `PARTRACE_API_TARGET`，默认 `http://127.0.0.1:8000`。后端启动方式见 [Backend README](../backend/README.md)；AI 追溯需要后端以 `PARTRACE_AGENT_ENABLED=true` 挂载 Agent 接口。

```powershell
$env:PARTRACE_API_TARGET = "http://127.0.0.1:8000"; npm run dev
```

打开页面后进入「我的 → 设置 → AI 服务设置」填写后端 Bearer 凭据，保存时会调用 `GET /api/v1/user` 测试连接。

## 路由

| Android | Web |
| --- | --- |
| 首页 `HomeScreen` 及站点、静音、设备菜单、状态、多画面浮层 | `/`（浮层改为底部面板） |
| 事件 `StationEventsScreen` | `/events`，读取后端已发布的区域事件 |
| 事件详情 `StationEventDetailScreen` | `/events/:eventId` |
| AI 追溯 `TraceAssistantScreen` | `/assistant` |
| 我的 `ProfileScreen` | `/profile` |
| 审核记录 `ReviewRecordsScreen` | `/reviews`、`/reviews/:reviewId` |
| 消息中心 `MessageCenterScreen` | `/messages` |
| AI 追溯内服务设置对话框 | `/settings` |

## 源码

```text
src/
  api/          唯一 HTTP 入口：client、station、events、reviews、agent、sse、overview、types
  assistant/    AI 追溯会话状态、页面、计划编辑、运行结果卡片、历史会话
  components/   底部导航、面板、状态视图、关键帧图片等共享组件
  pages/        首页、事件、事件详情、我的、审核记录、消息中心、服务设置
  format.ts     标签与中文错误提示
  hooks.ts      加载、对象 URL、本地偏好
  station.tsx   录像／配置／当前用户共享读取
```

## 仅 App 可用

官方 ESIotKit SDK 只在 Android 运行，以下能力网页端只显示「仅 App」或未知状态：SDK 登录与设备列表、在线状态、实时画面、SDK 检测记录与原片回放、设备设置、People 原片自动解析、SDK 调试。原始录像播放需后端 `/recordings/{id}/media`，当前未实现；报警消息尚无数据源；人工核查保存 Android 目前也没有入口，网页端只读审核记录。

## 安全说明

- Bearer 默认存于 `sessionStorage`，勾选「在此设备上记住」才写入 `localStorage`；这只适合开发联调，正式认证待 D05 后续方案。
- 后端未开放 CORS，部署时须让静态页面与 `/api` 同源（反向代理），不要把凭据放进构建配置。
- 关键帧通过带凭据的 fetch 读取后以 blob URL 显示；SSE 用 fetch 流读取，因为 `EventSource` 不能携带 `Authorization`。
