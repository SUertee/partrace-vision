## Why

用户 2026-10-28 要求在仓库根目录新增 `web/`，用 React 把当前 Android 用户端的页面与功能迁移为手机浏览器打开的 Mobile Web。开发计划原将 Web 查阅后置；本提案记录用户最新确认的范围调整，不代表 D08／D10 已验收。

## What Changes

- 新增 `web/` 模块：React + TypeScript + Vite + React Router，Mobile First，底部四入口沿用 Android 的首页、事件、AI 追溯、我的。
- 复用现有 `/api/v1` 数据与 Agent 接口：当前用户、配置、录像、事件、关键帧、审核记录、会话、消息、计划、运行、停止及 SSE 运行推送；所有请求集中在 `web/src/api/`。
- AI 追溯保留 Android 的录像／区域范围、问题、可编辑计划确认、运行、停止、重试、结果卡片和历史；运行状态通过 SSE 推送并在异常断开时回读运行。
- 官方摄像头 SDK 能力（设备列表与在线状态、直播、SDK 检测记录与回放、设备设置、People 自动上传）在浏览器中不可用，Web 明确标注仅 App 可用，不用模拟数据冒充。
- 开发期通过 Vite 代理同源访问 Backend，不修改 Backend、Android 或 API／DB 契约。

## Impact

- 工作包：D08（Issue #8）的另一入口，依赖 D05（Issue #5）与 D07（Issue #7）已有接口；不关闭任何 Issue。
- 影响模块：新增 `web/`；更新根 README 与模块说明。`android/`、`backend/`、`agent/`、`vision/` 代码不变。
- 不包含：新增 API／DB 字段、Backend CORS 或正式认证、核查保存界面（Android 当前也没有）、原片回看（`/recordings/{id}/media` 尚未实现）、P1 包裹与订单关联、D10 端到端验收。

## Verification

运行 `npm run typecheck`、`npm test`、`npm run build`，并用 Playwright 在 320–430px 视口截图检查布局；契约样例只用于测试夹具，不进入应用。真实后端联调、真机及微信浏览器验证按实际凭据另行记录。
