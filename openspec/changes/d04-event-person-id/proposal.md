# D04/D05 · 事件匿名档案编号

用户要求events新增可空person_id，保存A图库管理的G编号；B匹配未知时为null。关联现有Issue #4/#5/#6，涉及Backend、Vision、SQL/OpenAPI和文档。track_key保留录像内局部轨迹，person_id不表示自然人身份，也不关联App users。增量迁移保留旧行null，不猜测回填；结果仍不可覆盖。实现须保持旧导入幂等摘要兼容并覆盖匹配、冲突、未知、非法ID、迁移及发布回读。B现场标定缺失不在本次伪造验收。

本次 GitHub 合入从最新 dev 提取独立字段变更，保留 shelf_level，通过既有 Vision 事件出口交付 G/null 语义；People 任务链路及其 worker、Android、Agent 并行改动仍独立整合。
