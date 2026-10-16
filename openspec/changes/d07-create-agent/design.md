# 实现设计

- Agent 只依赖纯Python协议，Backend独占DB与事务；框架类型不进入公开契约。模型与编码器由环境配置适配，凭据不进入prompt、工具参数、摘要或错误。
- App先保存问题、查看可修改计划，再带稳定run_id确认。执行固定使用已保存的五字段Query及query_text。steps仅说明，不接受任意执行指令。
- `search_events`、`semantic_search_events`、`get_event`、`get_evidence`、`read_recording` 五类工具仅可读取当前范围。每次调用、读取run和提交完成重新校验当前凭据及资源。完整答复引用还须落在本次工具读取ledger、对应可用帧与同录像Event。
- 首次run先持久化再受控异步调度；run与唯一assistant完成消息同事务，停止先终态再取消，迟到结果不可覆。会话问题不要求data:read，助手历史按run当前授权整页验证。
- 调度分配失败补写failed；失败终结遇短暂DB断开时保留本进程待终结项，占用并发位，每5秒重试。队列不恢复模型过程；进程退出后仍按独占启动恢复遗留running。
- 首版执行实例使用单独PG advisory session锁，避免第二进程清理第一实例的running。获取锁后遗留running标failed/service_restarted，不自动续跑。框架不另建checkpoint持久化权威。
- 静态开发鉴权保持原边界。cursor使用持久私有HMAC key，绑定owner/会话/limit/排序。模型限制工具次数、图步数、执行超时和并发；供应商错误脱敏。
- 使用真实create_agent图和确定性测试模型验证工具循环，另以真实PG验证持久化与竞争；未配置外部密钥时不宣称供应商实测。
