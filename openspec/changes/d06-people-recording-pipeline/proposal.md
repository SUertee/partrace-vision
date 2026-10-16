# People 录像异步处理

## Why

用户要求首页连接的两个真实摄像头出现 People 事件时自动取得录像、通过 API 存入 PostgreSQL 管理的媒体任务，并通知 Vision 解析与回传完成状态。当前 `POST /recordings` 只发布已完成分析包，不能用空事件占位后修改事实。

## What Changes

- Android 仅处理真实 SDK People 事件，等待完整录像下载完成后上传，按设备和原生事件稳定去重，回显上传与解析各阶段。
- Backend 新增持久 `recording_jobs` 与受权的提交、查询、租约领取、媒体读取、完成及失败接口；原有完整包接口保持兼容。
- Vision 增加独立 API worker，通过显式配置的算法命令生成原有完整结果包；缺少算法时明确报告未配置，不伪造成功。
- 同步 SQL 增量迁移、OpenAPI、模块交接与本地运行说明。
- 根据用户 2026-10-17 最新确认，公开原片入口改为 `POST /recordings` 的显式 source 模式，同事务先写入 recordings 待发布行和任务；Vision 完成后一次发布。旧完整包导入保持兼容，旧任务入口保留为兼容入口。

## Capabilities

### New Capabilities
- `people-recording-pipeline`: SDK People 原片到最终事实的持久异步处理与状态反馈。

## Impact

对应 D06 [#6](https://github.com/SUertee/partrace-vision/issues/6)、D05 [#5](https://github.com/SUertee/partrace-vision/issues/5)，依赖 D04 [#4](https://github.com/SUertee/partrace-vision/issues/4) 的真实算法与配置。涉及 Android、Backend、Vision、DB/API。此交付不自动关闭这些工作包或 D10 [#10](https://github.com/SUertee/partrace-vision/issues/10)。验证覆盖真实 SDK 字段、稳定幂等、并发租约、过期回调、权限、失败与进程恢复；真实算法效果仍取决于算法入口和模型。
