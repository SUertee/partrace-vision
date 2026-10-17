## Context

P1/P2 为用户视觉参考，P3 为当前 SDK 列表。SDK 检测不等于团队区域动作；Agent 当前只支持单录像单区域，不能将截图设备01/02当作已存在跨镜关联。

## Decisions

- 共用纯展示详情布局与独立数据仓库，入口传真实 event/recording/evidence ID 或 SDK deviceSn+eventId；SDK MegaEvent 仅在内存供原播放器使用，不放入持久状态。
- 聊天相关片段由受权的 event_evidence 卡或真实引用产生；详情重新鉴权读事件和关键帧，必须核对属于预期录像／事件。时间线使用录像相对偏移；UTC 起点缺失时不拼出时钟时间。
- SDK 分支计算 peopleJobId 候选并核对后端 job 的 sdk_event_id、recording_id、camera/config 及当前 People 目标服务。没有解析、排队、失败与发布后零返回分别展示，SDK 原始检测及回放仍可用。
- 展示黄色真实缺口提示，不将 media_missing、unindexed 或离散点解释为已确定的遮挡区间。只显示确有数据的设备选项，不画推断连续轨迹。
- 首轮详情展示真实关键帧、时间线和显式 SDK 回放入口；2026-10-17 用户追加要求：从 Agent 相关片段进入后可回看已入库的真实原片。通过现有 `GET /api/v1/recordings/{recording_id}/media` 实现后台原片回放，不将后台 URL 构造为伪 MegaEvent。
- 工具进度具体方案见 design-progress.md。进度来自工具生命周期，不是模型思考；旧服务字段缺省兼容。内存失效后的历史不伪造执行步骤。

## Verification

运行只读详情身份／来源／媒体归属／失败与取消的实际单元或本机网络回归，既有 Agent／SDK 相关回归、Debug 构建和 lint。增加明确虚构数据的对话与事件详情 Compose Preview。后端运行相关离线测试，严格验证 OpenSpec 与接口和文档生成同步；只按实际记录设备／模型／SDK／真机结果，未覆盖项明确列出。

## 原片回看追加 · 2026-10-17

- 详情已授权会话同时绑定经过回读的录像 ID、真实来源及当前媒体状态。仅 `sdk/prerecorded + available` 提供后台原片入口，不依赖摄像头 SDK 登录，不复用 SDK 播放器。
- 用户点击后使用聊天 `AndroidTraceAgentStore` 当前同源、同凭据请求固定录像媒体端点；凭据只放 Bearer 请求头，不放 URL、导航、日志或公共状态。读取前后重验会话边界；不跟随重定向。
- MP4 流式写入应用私有缓存，最大 64 MiB；只接受成功、非空 `video/mp4` 响应。原片不外发、不写共享相册；错误、取消、配置变化、切换事件、离前台和销毁均停止并清理临时文件。
- 使用系统 VideoView 的本地文件输入及原生播放控制，从当前事件 `startOffsetMs` 定位，保留整段上下文；准备或解码失败明确显示错误，不以文件下载成功冒充播放成功。
- 验证网络身份／固定路径、归属边界、MIME／体积／取消清理及编译、lint；实际 seek、画面、音频和生命周期另以设备操作留证。

## SDK 会话失效与后台详情隔离

- 根导航器不随 SDK authenticated 状态重建。SDK 失效关闭 SDK 页面、销毁 SDK 根 ScreenModel 请求范围，清除 SDK 详情缓存和下载任务；People 管道继续按 SDK 状态停止。
- 仅保留 Home 和栈顶的后台事件目标，不恢复藏在 SDK 页面下的历史后台目标。首次 SDK 失效以同一组事件／录像／证据 ID 创建新详情，由其正常只读仓库重新验证后台身份；不因 SDK 失效放宽后台访问权限。
- SDK 事件流会重放最后一个 Logout。已经失效后的重复 Logout 不重建当前后台详情、不打断其播放器、不重复清空聊天返回来源。后台身份本身失效仍按正常详情读取失败处理。
