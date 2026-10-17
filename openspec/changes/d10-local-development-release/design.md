## Context

原工作区混合暂存、未提交和新文件，并有持续运行的服务。使用临时 Git index 固定源码树，不改变原 index 或文件；发布分支从最新 dev 建立后在独立目录三方合并。

## Decisions

保留 dev 的 shelf_level、person_id、cards/SSE 和 UTC 声明规则，同时接入本地 People 持久任务、Android 和 worker。使用空凭据模板构建 Android，不复制原 SDK 登录或后端资产。新空库真实加载再导出系统目录；已有库只提供显式迁移，不执行初始化或云端重部署。

## Verification

重新执行 Backend/Agent、Vision、Android 目标测试/构建/lint、SQL 真实目录导出、五项离线图检查及固定 OpenSpec。检查新增文件、私有配置排除、UI 图片边界、相对链接和凭据模式后通过 PR 交付 dev。
