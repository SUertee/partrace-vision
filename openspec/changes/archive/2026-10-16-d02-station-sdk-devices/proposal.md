# Proposal

状态：根据用户“两个 device 直接接入这个首页”的授权提出；实现与本轮验证待执行，不代表 D02 或 D06 全部验收。

## Why

当前驿站首页仍显示两张设计样例卡，已登录账号的真实 SDK 设备只能从官方调试界面访问。用户要求将这些设备直接接入新首页，沿用已还原的设计与官方 SDK 能力。

关联 [D02 / Issue #2](https://github.com/SUertee/partrace-vision/issues/2) 与 [D06 / Issue #6](https://github.com/SUertee/partrace-vision/issues/6)，真实 Issue 已在本轮主任务中通过 GitHub CLI 只读核对。此 change 只交付首页 SDK 设备接入，不自动关闭任一 Issue。

## What Changes

- 驿站设计壳与官方界面共用原 SDK Koin 初始化，恢复有效缓存的业务登录会话，首页读取当前账号的真实设备列表。
- 以 SDK 返回的设备名称、在线状态及最近缩略图替换“深圳”“安克黑客松”“68”等样例，不硬编码设备 SN、账号或设备数量；当前账号返回两个设备时显示两张真实设备卡。
- 设备卡打开原 SDK 单路直播；基站打开原设备设置，卡片更多与状态入口均进入该设备真实设置。
- 区分加载、成功空列表、请求错误、未登录或 401 与重试；错误不能退回模拟设备。多画面入口显示真实最近缩略图网格，可点击打开单路直播。
- 保留页面设计、消息与 AI 本地交互；站点标题仅为本地界面分组，不虚构 SDK 房屋或设备归属映射。消息、AI 与本地静音的未连接边界保留。

## Capabilities

### New Capabilities

- `station-sdk-devices`: 当前 SDK 会话恢复、真实设备首页、状态处理、最近缩略图网格及官方直播／设置导航。

### Modified Capabilities

- `station-design`: 设备卡来源由设计样例改为真实 SDK；保留报警、AI、本地静音与其余未连接能力的来源说明，并明确 SDK 接入的验收边界。

## Impact

影响 `android/` 设计壳、共享 SDK 会话／设备状态、原官方页面导航及 Android 说明；对应 [M01](../../../../docs/modules/README.md#m01)、[M09](../../../../docs/modules/README.md#m09)。依赖现有官方 SDK 2.2.7、有效获授权账号／缓存会话、联网设备与 Android 工具链；没有可用会话时使用原登录入口。

不新增或修改 [SQL](../../../../docs/database/schema.sql)、[OpenAPI](../../../../docs/api/openapi.json) 或 [共享样例](../../../../docs/api/examples/v0.3)。不包含两路同时直播、设备配网、SDK 房屋分组同步、报警订阅、事件／录像查询、远端 AI、证据或 D10 闭环。设备列表服务验证、UI 构建／模拟器检查和真实摄像头播放验证分别记录，不将任一单项成功描述为 D06 全部完成。
