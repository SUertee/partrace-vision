# 设计

新增events.person_id TEXT NULL，格式G加至少4位数字、最长100字符。字段表示同一A图库内的匿名档案关联，不建立users外键，不改track_key。旧清单可省略或传null，数据库与读取返回null；未知/null在清单摘要中省略以保留旧包幂等。匹配编号进入摘要，换编号构成不同结果。

B adapter只采用identity.status=matched且有效的profile_id，并尊重review对局部人物不一致的否决；缺少匹配、冲突、P局部编号均不得写成G编号。既有六字段事件出口及Backend同时校验；独立 People 部署的 adapter/worker 边界另有相同约束，完整源码仍待整合。A仍只建图库，不凭空生成区域动作events。

004迁移仅增可空列与CHECK，旧行和事实不可变触发器保留；独立管理命令显式执行、重复拒绝。验证先在隔离PG测试，再对本地/云端运行库逐值核对迁移；Cloud API按已部署source-first基线单独兼容，不覆盖其他进行中的功能。
