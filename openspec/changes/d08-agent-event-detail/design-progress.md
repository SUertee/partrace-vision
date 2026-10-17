# D07 / D08：真实工具进度边界

工具进度仅来自实际工具 coroutine 的进入、返回、异常和取消，不读取模型思考，不由计划 steps 或运行终态推导工具成功。工具白名单为 load_skill、search_events、semantic_search_events、get_event、get_evidence、read_recording；每次调用使用服务端独立 call_id。

首版不迁移数据库。执行器在有界、线程安全的内存 LRU 中保存至多 128 次运行、每运行至多 64 次工具调用；终态封闭后拒绝迟到更新，未结束的调用标为 cancelled 或 failed，不推断 completed。重启、淘汰及旧运行没有进度，返回空数组，不补造历史。

GET /api/v1/agent/runs/{run_id} 先完成既有 owner、data:read、录像与引用授权，再附可选 progress 数组。其他运行响应可省略此字段；SSE 的 id=1 快照和 id=2 终态以及数据库权威状态保持既定含义。

progress 项定稿为 call_id、tool、status、sequence、started_at、finished_at。sequence 为该运行内调用开始顺序，UTC 时间由服务端产生；status 仅 running/completed/failed/unavailable/cancelled。语义服务返回 unavailable 不能显示为 completed；Skill 未命中同样为 unavailable。无参数、结果、提示词、模型思考、凭据或原始异常字段。

Agent 的可选同步 observer 不参与事实与证据验证。observer 故障隔离，不改变工具返回或 Agent 结果；停止/失败/完成仍以既有 PostgreSQL 事务为准。Android 继续轮询既有 GET；无 progress 或空数组显示没有执行记录，不将其理解为没有调用工具。

验证使用真实 create_agent 图配显式 fake 模型、受控 gateway 和 runtime/history doubles；不启动真实服务、不连接数据库或外部模型。验证并发独立调用、重复调用、降级、异常/取消、observer 故障、终态迟到、容量淘汰和先授权后读取。
