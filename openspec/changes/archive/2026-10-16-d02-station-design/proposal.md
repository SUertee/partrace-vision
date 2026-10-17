# Proposal

状态：按本轮用户提供的五张 Android 设计图实现界面；实现与验证凭据另行记录，不代表 D02 或 D10 已验收。

## Why

现有 Android 沿用官方 SDK 示例入口，尚未呈现用户设计的驿站首页、消息中心、AI 页面及两个浮层。本次关联 [D02](../../../../docs/DEVELOPMENT_PLAN.md#d02--android-四-tab-骨架)，先交付可操作的 Compose 设计壳。

关联真实 [Issue #2 · Android 四 Tab 骨架](https://github.com/SUertee/partrace-vision/issues/2)，已用本地 GitHub CLI 只读核对。本次只实现用户设计图对应的视觉范围，不关闭该 Issue。

## What Changes

- 默认 Android 启动驿站设计壳，以首页、AI、我的三个入口呈现本轮设计；独立消息中心从首页进入。
- 还原首页设备样例、消息通知提示、AI 建议与输入、通知定时静音浮动底部弹层、站点选择浮层。
- 我的仅说明输入来源与未连接能力，并保留进入原官方 `SampleApp` 的 SDK 调试入口。
- 本轮用户三入口设计覆盖既有“四 Tab”视觉草案；事件查询、证据联动、核查记录与完整 D02 验收仍在后续工作范围。
- 设备卡、“68”及 AI 本地交互明确属于设计样例；真实直播、报警、AI 服务不在本次接入范围。

## Capabilities

### New Capabilities

- `station-design`: Compose 驿站设计壳、导航、浮层及明确来源的本地交互。

### Modified Capabilities

无已归档能力修改。此次调整的是 D02 当前视觉入口方案，不声明已有业务验收完成。

## Impact

影响 `android/` 的默认界面、Compose 页面与说明，以及 [M01](../../../../docs/modules/README.md#m01) 的视觉入口说明；保留 Android/Common 同级工程与官方 SDK 示例。真实系统状态栏、导航栏和键盘 inset 由父层处理，浮层阻止底层触摸。

视觉实现依赖现有 Kotlin/Compose 工具链、官方工程与用户提供的五张设计图；不依赖运行中的 Backend/Agent。后续业务接入仍按 D01、D05、D07、D08、D09、D10 推进。无数据库、API、认证或 SDK 业务调用修改；不包含真实直播、报警订阅、远端 AI、证据、人工核查及业务闭环验收。
