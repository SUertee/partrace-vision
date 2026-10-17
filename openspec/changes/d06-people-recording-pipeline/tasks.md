## 1. Contracts and persistence
- [x] 1.1 Define job lifecycle, SQL migration, OpenAPI and examples
- [x] 1.2 Implement authorized durable upload, exclusive claim and result publication
- [x] 1.3 Verify idempotency, failure, permission and stale lease handling with PostgreSQL

## 2. Camera and Vision clients
- [x] 2.1 Connect real SDK People discovery and completed video download to API
- [x] 2.2 Preserve task identity and surface upload/analysis/error status in Android
- [x] 2.3 Implement independent Vision worker and explicit algorithm command adapter

## 3. Verification and handoff
- [x] 3.1 Build/lint Android and run relevant tests
- [x] 3.2 Run worker and Backend tests, document real device checks and missing dependencies
- [x] 3.3 Regenerate DB/API design artifacts and validate OpenSpec


## 4. B 现场配置与验收（等待现场输入）
- [ ] 4.1 提供与球机当前画面匹配的货架标定及 zone_id 映射，发布新配置版本
- [ ] 4.2 用同一真实 B 事件验证算法产物、最终事实发布和 App completed 回传；明确旧 v1 原片的版本迁移，不覆盖旧配置

## 5. 用户确认的先入库后解析
- [x] 5.1 recordings 显式 pending/published 与保留数据的增量迁移
- [x] 5.2 POST recordings source 模式兼容旧完整结果导入
- [x] 5.3 发布事务与所有事实查询的 pending 守卫
- [x] 5.4 Android 切换公开原片入口并保留持久任务/完成回调
- [x] 5.5 更新契约/快照/模块文档，验证真实待解析记录与完成状态

- [x] 5.6 API 停机后的已受理任务持续回读与旧快照恢复，不误报 Vision 失败

## 6. Aurora 公网真实历史验收（2026-10-17）
- [x] 6.1 基于云端39aa4ab兼容原片任务，保留shelf_level和现有UTC语义；15项真实PG任务测试通过
- [x] 6.2 备份并增量迁移Aurora，逐值保留原11表数据，部署独立People容器与受限凭据
- [x] 6.3 公网IP受信任HTTPS接通新API，保留原API供回退
- [x] 6.4 App真实SDK历史录像经公网POST原片，取得Aurora pending凭据
- [x] 6.5 实际A算法发布、Aurora持久回读与App完成回调同ID验证
- [x] 6.6 提供Aurora查询SQL和部署/运行边界
