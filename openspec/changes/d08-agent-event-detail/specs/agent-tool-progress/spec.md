## ADDED Requirements

### Requirement: Actual bounded tool execution progress
GET Agent run MAY 返回可选 progress 数组；服务 SHALL 仅在原运行授权成功后暴露同一 run 的实际工具生命周期，限制内存条目，不包含思考、参数、凭据或全文工具输出。

#### Scenario: Tool completes or becomes unavailable
- **WHEN** 实际只读工具成功、失败、取消或返回 unavailable
- **THEN** 对应独立调用显示真实状态，不把 unavailable 或未调用步骤标为完成

#### Scenario: Old or restarted process
- **WHEN** 旧服务不提供 progress 或历史记录在重启／淘汰后不可得
- **THEN** 客户端兼容显示无过程记录，不依据 plan.steps 或 run completed 生成假工具轨迹

#### Scenario: Stopped run receives a late callback
- **WHEN** 运行已停止或终态后工具回调迟到
- **THEN** 服务不追加新进度，不覆盖运行终态或产生助手答复
