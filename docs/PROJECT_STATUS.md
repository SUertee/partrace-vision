# 实际进展与待验收项

更新：2026-10-17（Asia/Shanghai）。本次以 `dev@8fc6771` 为基线整合原本地开发源码，完整范围和重新执行的验证见[开发整合版](releases/DEVELOPMENT_20261017.md)。源码、已部署服务和业务验收分别记录，不用 Issue 未关闭推断功能完全未实现。

## 本次源码状态

| 模块 | 本版已有实现 | 尚未验收 |
| --- | --- | --- |
| Android UI | 六图、首页/AI/我的、通知弹层、消息空态、审核只读与详情；新 SDK 详情及事件/回放导航 | 真机、多分辨率、全部硬件动作 |
| People / SDK | 真实 HUMAN 发现、完整原片等待/下载、稳定 ID、source 上传、重试、持久状态回读及完成回调 | 前台外的常驻、Android 真机 |
| Backend | 21 个数据/任务操作，pending/published、租约、原子事实发布、显式迁移；启用 Agent 后 30 个 | 正式生产鉴权与业务效果 |
| Vision | A 建档、B 离线货架算法、HTTP worker、图库 staging/CURRENT 与恢复 | 当前 B 标定、真实 B 事件/证据/G 关联 |
| Agent | 计划/只读检索/运行控制、cards/SSE、Android 接入、白名单 Skill 和可选语义降级 | 真实模型完整成功、Embedding 与真实证据引用 |
| 人工核查 | Backend 保存/回读，App 列表/详情/搜索 | App 新建保存及重启回读 |
| P1 包裹 | 2 表/5 接口设计 | 业务实现与扫码流程 |
| AWS 后续集成 | Bedrock/S3/监控设计提案 | 实施尚未开始 |

契约为 12 表/106 列、35 个操作，其中 5 个包裹操作仍是待实现设计。保留 shelf_level、person_id、cards_json。People 源码、Android、worker 和部署脚本已随本版纳入，不再只有私有部署快照。

## 本次验证

Backend/Agent 完整回归 **401 passed、0 skipped**；Vision **103 tests，OK**；Android **34 项目标测试、assembleDebug、lintDebug 通过**，lint 70 Warning、0 Error/Fatal。使用空凭据模板在独立目录构建，没有覆盖安装或重新触发摄像头。SQL 在专用空 PostgreSQL/pgvector 库实际加载并导出，五项设计同步、OpenSpec 及源码检查见版本验证。

## 已部署 AWS 与真实硬件凭据

公网 `https://35.84.190.14` 已配置受信任 IP HTTPS 与自动续期。云端仍运行独立 People/person_id 镜像，development 最小 Bearer，21 操作、Agent 关闭；Aurora 12 表/105 列，尚无 cards_json。本次提交不重部署云端、不初始化已有库。

最近已记录的只读快照：A completed 15、B queued 2，旧 3 条 events 保留且 person_id 均为空。真实 A 的上传、CPU 建档、发布及 App 回调已经验证；A 不生成区域动作事件，因此不能把 completed 当作已有货架事件。B 等待当前画面的标定与区域映射。A worker 在 Mac CPU 上运行；App 自动发现需要前台和 SDK 登录。快照数量不是实时监控。

见[AWS 部署/稳定 ID/查询](deployment/AWS_PEOPLE_20261017.md)及[People 原始凭据](../openspec/changes/d06-people-recording-pipeline/verification.md)。Android 使用模拟器连接真实摄像头，不能代替 Android 真机验收。

## 后续优先级

1. B 现场标定、新配置与旧任务迁移策略，验证真实货架事件、关键帧、G 编号。
2. 真实 Agent/Embedding 配置和成功引用链，完成 App 人工保存及重启回读。
3. worker 服务器交付、Android 真机/后台，完成 D10 同一事件演示与录屏。

GitHub D01–D10 的完整业务验收未因本次源码整合而自动完成。Skill 离线回归、界面预览、HTTPS 可达和 A 建档完成均按各自边界判断，不替代 D10。
